package com.github.cc11001100.weavergirl.core.transformer;

import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.InterceptorHolder;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

import java.lang.annotation.*;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that MethodMatcher.ANNOTATION and MethodMatcher.SIGNATURE
 * are correctly handled by WeaverTransformer during bytecode instrumentation.
 */
public class WeaverTransformerAnnotationTest {

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    public @interface TestTraced {}

    public static class TargetService {
        @TestTraced
        public String annotatedMethod() {
            return "traced";
        }

        public String nonAnnotatedMethod() {
            return "plain";
        }

        public String process(String input, int count) {
            return input + ":" + count;
        }

        public String process(String input) {
            return input;
        }
    }

    private static final String TARGET_CLASS =
            "com.github.cc11001100.weavergirl.core.transformer.WeaverTransformerAnnotationTest$TargetService";

    private static Instrumentation instrumentation;
    private DefaultInterceptorRegistry registry;

    @BeforeAll
    static void setUpClass() {
        try {
            instrumentation = ByteBuddyAgent.install();
        } catch (Exception e) {
            instrumentation = null;
        }
    }

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(instrumentation != null,
                "ByteBuddyAgent self-attach not available in this environment");
        Assumptions.assumeTrue(instrumentation.isRetransformClassesSupported(),
                "JVM does not support class retransformation");

        registry = new DefaultInterceptorRegistry();
        InterceptorHolder.setRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        InterceptorHolder.setRegistry(null);
        if (registry != null) {
            registry.clear();
        }
    }

    @Test
    void methodAnnotationMatcher_shouldInterceptAnnotatedMethod() {
        AtomicBoolean intercepted = new AtomicBoolean(false);

        InterceptorDefinition def = new InterceptorDefinition(
                "test-method-annotation",
                new Pointcut(
                        ClassMatcher.byName(TARGET_CLASS),
                        MethodMatcher.byAnnotation(TestTraced.class.getName())
                ),
                new Interceptor() {
                    @Override
                    public void before(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation) {
                        intercepted.set(true);
                    }
                },
                0
        );
        registry.register(def);

        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setIgnoreAgentClasses(false);
        transformer.install(instrumentation);

        new TargetService().annotatedMethod();
        assertTrue(intercepted.get(), "Method annotated with @TestTraced should be intercepted");
    }

    @Test
    void methodAnnotationMatcher_shouldNotInterceptNonAnnotatedMethod() {
        AtomicBoolean intercepted = new AtomicBoolean(false);

        InterceptorDefinition def = new InterceptorDefinition(
                "test-method-annotation-exclude",
                new Pointcut(
                        ClassMatcher.byName(TARGET_CLASS),
                        MethodMatcher.byAnnotation(TestTraced.class.getName())
                ),
                new Interceptor() {
                    @Override
                    public void before(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation) {
                        intercepted.set(true);
                    }
                },
                0
        );
        registry.register(def);

        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setIgnoreAgentClasses(false);
        transformer.install(instrumentation);

        new TargetService().nonAnnotatedMethod();
        assertFalse(intercepted.get(), "Non-annotated method should NOT be intercepted");
    }

    @Test
    void methodSignatureMatcher_shouldMatchByParameterCount() {
        AtomicBoolean intercepted = new AtomicBoolean(false);

        InterceptorDefinition def = new InterceptorDefinition(
                "test-signature",
                new Pointcut(
                        ClassMatcher.byName(TARGET_CLASS),
                        MethodMatcher.bySignature("process", "java.lang.String,int")
                ),
                new Interceptor() {
                    @Override
                    public void before(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation) {
                        intercepted.set(true);
                    }
                },
                0
        );
        registry.register(def);

        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setIgnoreAgentClasses(false);
        transformer.install(instrumentation);

        new TargetService().process("test", 1);
        assertTrue(intercepted.get(), "process(String, int) should be intercepted by signature match");
    }

    @Test
    void methodSignatureMatcher_shouldNotMatchWrongParameterCount() {
        AtomicBoolean intercepted = new AtomicBoolean(false);

        InterceptorDefinition def = new InterceptorDefinition(
                "test-signature-exclude",
                new Pointcut(
                        ClassMatcher.byName(TARGET_CLASS),
                        MethodMatcher.bySignature("process", "java.lang.String,int")
                ),
                new Interceptor() {
                    @Override
                    public void before(com.github.cc11001100.weavergirl.api.interceptor.MethodInvocation invocation) {
                        intercepted.set(true);
                    }
                },
                0
        );
        registry.register(def);

        WeaverTransformer transformer = new WeaverTransformer(registry);
        transformer.setIgnoreAgentClasses(false);
        transformer.install(instrumentation);

        // process(String) has only 1 param — should NOT match signature(String, int)
        new TargetService().process("test");
        assertFalse(intercepted.get(), "process(String) should NOT match signature(String,int)");
    }
}
