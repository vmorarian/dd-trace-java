package datadog.trace.instrumentation.netty41;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.nameStartsWith;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.NettyChannelPipelineInstrumentation.ADDITIONAL_INSTRUMENTATION_NAMES;
import static datadog.trace.instrumentation.netty41.NettyChannelPipelineInstrumentation.INSTRUMENTATION_NAME;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator;
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator;
import io.netty.channel.ChannelHandlerContext;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class NettyChannelHandlerContextInstrumentation extends Instrumenter.Tracing {

  public NettyChannelHandlerContextInstrumentation() {
    super(INSTRUMENTATION_NAME, ADDITIONAL_INSTRUMENTATION_NAMES);
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    // Optimization for expensive typeMatcher.
    return hasClassesNamed("io.netty.channel.ChannelHandlerContext");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.netty.channel.ChannelHandlerContext"));
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".AttributeKeys",
      packageName + ".AttributeKeys$1",
      packageName + ".client.NettyHttpClientDecorator",
      packageName + ".server.NettyHttpServerDecorator",
    };
  }

  @Override
  public Map<? extends ElementMatcher<? super MethodDescription>, String> transformers() {
    return singletonMap(
        // this may be overly aggressive:
        isMethod().and(nameStartsWith("fire")).and(isPublic()),
        NettyChannelHandlerContextInstrumentation.class.getName() + "$FireAdvice");
  }

  public static class FireAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static TraceScope scopeSpan(@Advice.This final ChannelHandlerContext ctx) {
      final AgentSpan channelSpan = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).get();
      if (channelSpan == null || channelSpan == activeSpan()) {
        // don't modify the scope
        return AgentTracer.NoopAgentScope.INSTANCE;
      }
      return activateSpan(channelSpan);
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void close(@Advice.Enter final TraceScope scope) {
      scope.close();
    }

    private void muzzleCheck() {
      NettyHttpClientDecorator.DECORATE.afterStart(null);
      NettyHttpServerDecorator.DECORATE.afterStart(null);
    }
  }
}
