package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.AppSecEventWrapper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;

public class AppSecEventSerializer {
  private static final JsonAdapter<AppSecEventWrapper> APPSEC_EVENT_JSON_ADAPTER;

  static {
    Moshi moshi = new Moshi.Builder().build();
    APPSEC_EVENT_JSON_ADAPTER = moshi.adapter(AppSecEventWrapper.class);
  }

  private AppSecEventSerializer() {}

  public static JsonAdapter<AppSecEventWrapper> getAppSecEventAdapter() {
    return APPSEC_EVENT_JSON_ADAPTER;
  }
}
