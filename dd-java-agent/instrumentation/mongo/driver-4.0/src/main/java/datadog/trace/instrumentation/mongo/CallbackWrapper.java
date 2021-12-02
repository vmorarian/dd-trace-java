package datadog.trace.instrumentation.mongo;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;

import com.mongodb.internal.async.SingleResultCallback;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;

public class CallbackWrapper<T> implements SingleResultCallback<Object> {
  private final AgentScope.Continuation continuation;
  private final SingleResultCallback<Object> wrapped;

  public CallbackWrapper(
      AgentScope.Continuation continuation, SingleResultCallback<Object> wrapped) {
    this.continuation = continuation;
    this.wrapped = wrapped;
  }

  @Override
  public void onResult(Object result, Throwable t) {
    AgentScope scope = continuation.activate();
    try {
      wrapped.onResult(result, t);
    } finally {
      scope.close();
    }
  }

  public static SingleResultCallback<Object> wrapIfRequired(SingleResultCallback<Object> callback) {
    AgentScope scope = activeScope();
    if (null != scope && scope.isAsyncPropagating()) {
      return new CallbackWrapper<>(scope.capture(), callback);
    }
    return callback;
  }
}
