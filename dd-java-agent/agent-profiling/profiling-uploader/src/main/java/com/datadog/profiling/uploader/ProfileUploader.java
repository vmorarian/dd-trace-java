/*
 * Copyright 2019 Datadog
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.datadog.profiling.uploader;

import static datadog.common.socket.SocketUtils.discoverApmSocket;
import static datadog.trace.util.AgentThreadFactory.AgentThread.PROFILER_HTTP_DISPATCHER;

import com.datadog.profiling.controller.RecordingData;
import com.datadog.profiling.controller.RecordingType;
import com.datadog.profiling.uploader.util.JfrCliHelper;
import com.datadog.profiling.uploader.util.PidHelper;
import datadog.common.container.ContainerInfo;
import datadog.common.socket.UnixDomainSocketFactory;
import datadog.trace.api.Config;
import datadog.trace.api.IOLogger;
import datadog.trace.util.AgentProxySelector;
import datadog.trace.util.AgentThreadFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The class for uploading profiles to the backend. */
public final class ProfileUploader {

  private static final Logger log = LoggerFactory.getLogger(ProfileUploader.class);

  static final String FORMAT_PARAM = "format";
  static final String TYPE_PARAM = "type";
  static final String RUNTIME_PARAM = "runtime";

  static final String PROFILE_START_PARAM = "start";
  static final String PROFILE_END_PARAM = "end";

  static final String ATTACHMENT_PARAM = "0.jfr";

  static final String TAGS_PARAM = "tags[]";

  static final String HEADER_DD_API_KEY = "DD-API-KEY";
  static final String HEADER_DD_CONTAINER_ID = "Datadog-Container-ID";
  static final String HEADER_DD_PROFILE_ID = "Datadog-Profile-ID";
  static final String HEADER_DD_EVP_ORIGIN = "DD-EVP-ORIGIN";
  static final String HEADER_DD_EVP_ORIGIN_VERSION = "DD-EVP-ORIGIN-VERSION";
  static final String HEADER_DD_EVP_REQUEST_ID = "DD-REQUEST-ID";

  static final String JAVA_LANG = "java";
  static final String JAVA_PROFILING_LIBRARY = "Java profiling library";
  static final String DATADOG_META_LANG = "Datadog-Meta-Lang";

  static final int MAX_RUNNING_REQUESTS = 10;
  static final int MAX_ENQUEUED_REQUESTS = 20;

  static final String PROFILE_FORMAT = "jfr";
  static final String PROFILE_TYPE_PREFIX = "jfr-";
  static final String PROFILE_RUNTIME = "jvm";

  static final int TERMINATION_TIMEOUT = 5;

  static final String EVENT_PARAM = "event";
  static final String DATA_PARAM = "0.jfr";

  private static final Headers EVENT_HEADER =
      Headers.of(
          "Content-Disposition",
          "form-data; name=\"" + EVENT_PARAM + "\"; filename=\"event.json\"");

  private static final Headers DATA_HEADERS =
      Headers.of(
          "Content-Disposition", "form-data; name=\"" + DATA_PARAM + "\"; filename=\"0.jfr\"");

  private final ExecutorService okHttpExecutorService;
  private final OkHttpClient client;
  private final IOLogger ioLogger;
  private final boolean agentless;
  private final boolean summaryOn413;
  private final String apiKey;
  private final String url;
  private final String containerId;
  private final int terminationTimeout;
  private final List<String> tags;
  private final CompressionType compressionType;

  public ProfileUploader(final Config config) throws IOException {
    this(config, new IOLogger(log), ContainerInfo.get().getContainerId(), TERMINATION_TIMEOUT);
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  ProfileUploader(
      final Config config,
      final IOLogger ioLogger,
      final String containerId,
      final int terminationTimeout)
      throws IOException {
    url = config.getFinalProfilingUrl();
    apiKey = config.getApiKey();
    agentless = config.isProfilingAgentless();
    summaryOn413 = config.isProfilingUploadSummaryOn413Enabled();
    this.ioLogger = ioLogger;
    this.containerId = containerId;
    this.terminationTimeout = terminationTimeout;

    log.debug("Started ProfileUploader with target url {}", url);
    /*
    FIXME: currently `Config` class cannot get access to some pieces of information we need here:
    * PID (see PidHelper for details),
    * Profiler version
    Since Config returns unmodifiable map we have to do copy here.
    Ideally we should improve this logic and avoid copy, but performance impact is very limited
    since we are doing this once on startup only.
    */
    final Map<String, String> tagsMap = new HashMap<>(config.getMergedProfilingTags());
    tagsMap.put(VersionInfo.PROFILER_VERSION_TAG, VersionInfo.VERSION);
    // PID can be null if we cannot find it out from the system
    if (PidHelper.PID != null) {
      tagsMap.put(PidHelper.PID_TAG, PidHelper.PID.toString());
    }
    tags = tagsToList(tagsMap);

    // This is the same thing OkHttp Dispatcher is doing except thread naming and daemonization
    okHttpExecutorService =
        new ThreadPoolExecutor(
            0,
            Integer.MAX_VALUE,
            60,
            TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            new AgentThreadFactory(PROFILER_HTTP_DISPATCHER));
    // Reusing connections causes non daemon threads to be created which causes agent to prevent app
    // from exiting. See https://github.com/square/okhttp/issues/4029 for some details.
    final ConnectionPool connectionPool =
        new ConnectionPool(MAX_RUNNING_REQUESTS, 1, TimeUnit.SECONDS);

    // Use same timeout everywhere for simplicity
    final Duration requestTimeout = Duration.ofSeconds(config.getProfilingUploadTimeout());
    final OkHttpClient.Builder clientBuilder =
        new OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(requestTimeout)
            .writeTimeout(requestTimeout)
            .readTimeout(requestTimeout)
            .callTimeout(requestTimeout)
            .proxySelector(AgentProxySelector.INSTANCE)
            .dispatcher(new Dispatcher(okHttpExecutorService))
            .connectionPool(connectionPool);

    String apmSocketPath = discoverApmSocket(config);
    if (apmSocketPath != null) {
      clientBuilder.socketFactory(new UnixDomainSocketFactory(new File(apmSocketPath)));
    }

    if (url.startsWith("http://")) {
      // force clear text when using http to avoid failures for JVMs without TLS
      // see: https://github.com/DataDog/dd-trace-java/pull/1582
      clientBuilder.connectionSpecs(Collections.singletonList(ConnectionSpec.CLEARTEXT));
    }

    if (config.getProfilingProxyHost() != null) {
      final Proxy proxy =
          new Proxy(
              Proxy.Type.HTTP,
              new InetSocketAddress(
                  config.getProfilingProxyHost(), config.getProfilingProxyPort()));
      clientBuilder.proxy(proxy);
      if (config.getProfilingProxyUsername() != null) {
        // Empty password by default
        final String password =
            config.getProfilingProxyPassword() == null ? "" : config.getProfilingProxyPassword();
        clientBuilder.proxyAuthenticator(
            (route, response) -> {
              final String credential =
                  Credentials.basic(config.getProfilingProxyUsername(), password);
              return response
                  .request()
                  .newBuilder()
                  .header("Proxy-Authorization", credential)
                  .build();
            });
      }
    }

    client = clientBuilder.build();
    client.dispatcher().setMaxRequests(MAX_RUNNING_REQUESTS);
    // We are mainly talking to the same(ish) host so we need to raise this limit
    client.dispatcher().setMaxRequestsPerHost(MAX_RUNNING_REQUESTS);

    compressionType = CompressionType.of(config.getProfilingUploadCompression());
  }

  /**
   * Enqueue an upload request. Do not receive any notification when the upload has been completed.
   *
   * @param type {@link RecordingType recording type}
   * @param data {@link RecordingData recording data}
   */
  public void upload(final RecordingType type, final RecordingData data) {
    upload(type, data, () -> {});
  }

  /**
   * Enqueue an upload request and run the provided hook when that request is completed
   * (successfully or failing).
   *
   * @param type {@link RecordingType recording type}
   * @param data {@link RecordingData recording data}
   * @param onCompletion call-back to execute once the request is completed (successfully or
   *     failing)
   */
  public void upload(
      final RecordingType type, final RecordingData data, @Nonnull Runnable onCompletion) {
    if (canEnqueueMoreRequests()) {
      try {
        makeUploadRequest(
            type,
            data,
            () -> {
              data.release();
              onCompletion.run();
            });
      } catch (IOException e) {
        log.warn("Cannot upload profile data", e);
        data.release();
      }
      return;
    } else {
      log.warn("Cannot upload profile data: too many enqueued requests!");
    }
    // the request was not made; release the recording data
    data.release();
  }

  public void shutdown() {
    okHttpExecutorService.shutdownNow();
    try {
      okHttpExecutorService.awaitTermination(terminationTimeout, TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      // Note: this should only happen in main thread right before exiting, so eating up interrupted
      // state should be fine.
      log.warn("Wait for executor shutdown interrupted");
    }
    client.connectionPool().evictAll();
  }

  /**
   * Note that this method is only visible for testing and should not be used from outside this
   * class.
   */
  OkHttpClient getClient() {
    return client;
  }

  private static String quote(String str) {
    return "\"" + str + "\"";
  }

  private byte[] createEvent(@Nonnull final RecordingData data, UUID profileId) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    OutputStreamWriter os = new OutputStreamWriter(baos);
    os.append('{');
    os.append(quote("attachments") + ":[\"0.jfr\"],");
    os.append(
        quote("tags_profiler") + ":\"" + tags.stream().collect(Collectors.joining(",")) + "\",");
    os.append(quote(PROFILE_START_PARAM) + ":\"" + data.getStart() + "\",");
    os.append(quote(PROFILE_END_PARAM) + ":\"" + data.getEnd() + "\",");
    os.append(quote("profile-id") + ":\"" + profileId + "\",");
    os.append(quote("family") + ":\"java\",");
    os.append(quote("version") + ":\"4\"");
    os.append("}");
    os.flush();
    return baos.toByteArray();
  }

  private void makeUploadRequest(
      @Nonnull final RecordingType type,
      @Nonnull final RecordingData data,
      @Nonnull Runnable onCompletion)
      throws IOException {

    final MultipartBody.Builder bodyBuilder =
        new MultipartBody.Builder().setType(MultipartBody.FORM);

    UUID profileId = UUID.randomUUID();
    byte[] event = createEvent(data, profileId);
    RequestBody eventBody = RequestBody.create(null, event);
    bodyBuilder.addPart(EVENT_HEADER, eventBody);

    final CompressingRequestBody body =
        new CompressingRequestBody(compressionType, data::getStream);
    bodyBuilder.addPart(DATA_HEADERS, body);

    final RequestBody requestBody = bodyBuilder.build();
    final Request.Builder requestBuilder =
        new Request.Builder()
            .url(url)
            // Set chunked transfer
            .addHeader("Transfer-Encoding", "chunked")
            // Note: this header is used to disable tracing of profiling requests
            .addHeader(DATADOG_META_LANG, JAVA_LANG)
            .addHeader(HEADER_DD_EVP_ORIGIN, JAVA_PROFILING_LIBRARY)
            .addHeader(HEADER_DD_EVP_ORIGIN_VERSION, VersionInfo.VERSION)
            .addHeader(HEADER_DD_EVP_REQUEST_ID, profileId.toString())
            .addHeader(HEADER_DD_PROFILE_ID, profileId.toString())
            .post(requestBody);
    if (agentless && apiKey != null) {
      // we only add the api key header if we know we're doing agentless profiling. No point in
      // adding it to other agent-based requests since we know the datadog-agent isn't going to
      // make use of it.
      requestBuilder.addHeader(HEADER_DD_API_KEY, apiKey);
    }
    if (containerId != null) {
      requestBuilder.addHeader(HEADER_DD_CONTAINER_ID, containerId);
    }
    client
        .newCall(requestBuilder.build())
        .enqueue(
            new Callback() {
              @Override
              public void onFailure(Call call, IOException e) {
                if (isEmptyReplyFromServer(e)) {
                  ioLogger.error(
                      "Failed to upload profile, received empty reply from "
                          + call.request().url()
                          + " after uploading profile");
                } else {
                  ioLogger.error("Failed to upload profile to " + call.request().url(), e);
                }

                onCompletion.run();
              }

              @Override
              public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                  ioLogger.success("Upload done");
                } else {
                  final String apiKey = call.request().header(HEADER_DD_API_KEY);
                  if (response.code() == 404 && apiKey == null) {
                    // if no API key and not found error we assume we're sending to the agent
                    ioLogger.error(
                        "Failed to upload profile. Datadog Agent is not accepting profiles. Agent-based profiling deployments require Datadog Agent >= 7.20");
                  } else if (response.code() == 413 && summaryOn413) {
                    ioLogger.error(
                        "Failed to upload profile, it's too big. Dumping information about the profile");
                    JfrCliHelper.invokeOn(data, ioLogger);
                  } else {
                    ioLogger.error("Failed to upload profile", getLoggerResponse(response));
                  }
                }

                // Note: this whole callback never touches body and would be perfectly happy even if
                // server
                // never sends it.
                response.close();

                onCompletion.run();
              }

              private void logDebug(String msg) {
                if (log.isDebugEnabled()) {
                  log.debug(
                      "{} {} [{}] (Size={}/{} bytes)",
                      msg,
                      data.getName(),
                      type,
                      body.getReadBytes(),
                      body.getWrittenBytes());
                }
              }

              private IOLogger.Response getLoggerResponse(final okhttp3.Response response) {
                if (response != null) {
                  try {
                    return new IOLogger.Response(
                        response.code(), response.message(), response.body().string().trim());
                  } catch (final NullPointerException | IOException ignored) {
                  }
                }
                return null;
              }

              private boolean isEmptyReplyFromServer(final IOException e) {
                // The server in datadog-agent triggers 'unexpected end of stream' caused by
                // EOFException.
                // The MockWebServer in tests triggers an InterruptedIOException with SocketPolicy
                // NO_RESPONSE. This is because in tests we can't cleanly terminate the connection
                // on the
                // server side without resetting.
                return (e instanceof InterruptedIOException)
                    || (e.getCause() != null && e.getCause() instanceof java.io.EOFException);
              }
            });
  }

  private boolean canEnqueueMoreRequests() {
    return client.dispatcher().queuedCallsCount() < MAX_ENQUEUED_REQUESTS;
  }

  private List<String> tagsToList(final Map<String, String> tags) {
    return tags.entrySet().stream()
        .filter(e -> e.getValue() != null && !e.getValue().isEmpty())
        .map(e -> e.getKey() + ":" + e.getValue())
        .collect(Collectors.toList());
  }
}
