package datadog.trace.instrumentation.vertx_3_4.server;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class RouteHandlerInstrumentation extends Instrumenter.Tracing {
  public RouteHandlerInstrumentation() {
    super("vertx", "vertx-3.4");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".EndHandlerWrapper",
      packageName + ".RouteHandlerWrapper",
      packageName + ".VertxDecorator",
      packageName + ".VertxDecorator$VertxURIDataAdapter",
    };
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("io.vertx.ext.web.impl.RouteImpl");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("handler"))
            .and(isPublic())
            .and(takesArgument(0, named("io.vertx.core.Handler"))),
        packageName + ".RouteHandlerWrapperAdvice");
  }
}
