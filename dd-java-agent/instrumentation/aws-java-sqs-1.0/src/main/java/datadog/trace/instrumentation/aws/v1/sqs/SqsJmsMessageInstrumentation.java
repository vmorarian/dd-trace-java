package datadog.trace.instrumentation.aws.v1.sqs;

import static com.amazon.sqs.javamessaging.SQSMessagingClientConstants.STRING;
import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.amazon.sqs.javamessaging.message.SQSMessage;
import com.amazonaws.services.sqs.model.Message;
import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.Config;
import java.util.Map;
import javax.jms.JMSException;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public class SqsJmsMessageInstrumentation extends Instrumenter.Tracing {
  public SqsJmsMessageInstrumentation() {
    super("aws-sdk");
  }

  @Override
  protected boolean defaultEnabled() {
    return super.defaultEnabled() && Config.get().isSqsPropagationEnabled();
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("com.amazon.sqs.javamessaging.message.SQSMessage");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("com.amazon.sqs.javamessaging.message.SQSMessage");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isConstructor().and(takesArgument(2, named("com.amazonaws.services.sqs.model.Message"))),
        getClass().getName() + "$CopyTracePropertyAdvice");
  }

  public static class CopyTracePropertyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void onExit(
        @Advice.Argument(2) Message sqsMessage, @Advice.FieldValue("properties") Map properties)
        throws JMSException {
      Map<String, String> systemAttributes = sqsMessage.getAttributes();
      if (null != systemAttributes) {
        String awsTraceHeader = systemAttributes.get("AWSTraceHeader");
        if (null != awsTraceHeader && !awsTraceHeader.isEmpty()) {
          properties.put(
              "x__dash__amzn__dash__trace__dash__id", // X-Amzn-Trace-Id, encoded for JMS
              new SQSMessage.JMSMessagePropertyValue(awsTraceHeader, STRING));
        }
      }
    }
  }
}
