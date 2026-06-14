package com.github.cc11001100.weavergirl.core.plugin;

import com.github.cc11001100.weavergirl.api.annotation.AnnotationHandler;
import com.github.cc11001100.weavergirl.api.annotation.AnnotationHandlerRegistry;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AnnotationHandlerRegistry}.
 *
 * @since 1.1.0
 */
class AnnotationHandlerRegistryTest {

    /** Custom test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    @interface CustomMonitored {
        String value() default "";
    }

    /** A sample class annotated with @CustomMonitored for testing. */
    @CustomMonitored("test-service")
    static class SampleService {
        public void doWork() {
        }
    }

    @AfterEach
    void tearDown() {
        AnnotationHandlerRegistry.getInstance().unregister(CustomMonitored.class.getName());
    }

    @Test
    void registerCustomHandler_shouldBeInvokedForMatchingAnnotation() {
        // Given
        final boolean[] handlerInvoked = {false};
        final AnnotatedElement[] capturedElement = {null};

        AnnotationHandler handler = new AnnotationHandler() {
            @Override
            public Class<? extends Annotation> supportedAnnotation() {
                return CustomMonitored.class;
            }

            @Override
            public void handle(AnnotatedElement element, Object instance, InterceptorRegistry registry) {
                handlerInvoked[0] = true;
                capturedElement[0] = element;
            }
        };

        AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();
        registry.register(handler);

        // When — handleAnnotations on the annotated class
        SimpleInterceptorRegistry simpleRegistry = new SimpleInterceptorRegistry();
        registry.handleAnnotations(SampleService.class, null, simpleRegistry);

        // Then
        assertTrue(handlerInvoked[0], "Handler should have been invoked");
        assertEquals(SampleService.class, capturedElement[0], "Element should be SampleService.class");
    }

    @Test
    void registerHandler_canRegisterInterceptor() {
        // Given
        final Interceptor testInterceptor = new Interceptor() {
        };
        final Pointcut testPointcut = new Pointcut(
                ClassMatcher.byName("com.example.Service"),
                MethodMatcher.byName("process")
        );

        AnnotationHandler handler = new AnnotationHandler() {
            @Override
            public Class<? extends Annotation> supportedAnnotation() {
                return CustomMonitored.class;
            }

            @Override
            public void handle(AnnotatedElement element, Object instance, InterceptorRegistry registry) {
                InterceptorDefinition definition = new InterceptorDefinition(
                        "custom-monitored-interceptor",
                        testPointcut,
                        testInterceptor
                );
                registry.register(definition);
            }
        };

        AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();
        registry.register(handler);

        SimpleInterceptorRegistry simpleRegistry = new SimpleInterceptorRegistry();
        registry.handleAnnotations(SampleService.class, null, simpleRegistry);

        // Then — the handler should have registered an interceptor
        assertFalse(simpleRegistry.definitions.isEmpty(), "An interceptor should have been registered");
        assertEquals("custom-monitored-interceptor", simpleRegistry.definitions.get(0).getName());
        assertSame(testPointcut, simpleRegistry.definitions.get(0).getPointcut());
    }

    @Test
    void unregister_shouldRemoveHandler() {
        // Given
        AnnotationHandler handler = new AnnotationHandler() {
            @Override
            public Class<? extends Annotation> supportedAnnotation() {
                return CustomMonitored.class;
            }

            @Override
            public void handle(AnnotatedElement element, Object instance, InterceptorRegistry registry) {
            }
        };

        AnnotationHandlerRegistry registry = AnnotationHandlerRegistry.getInstance();
        registry.register(handler);
        assertTrue(registry.hasHandler(CustomMonitored.class), "Handler should be registered");

        // When
        boolean removed = registry.unregister(CustomMonitored.class.getName());

        // Then
        assertTrue(removed, "unregister should return true");
        assertFalse(registry.hasHandler(CustomMonitored.class), "Handler should no longer be registered");
    }

    /**
     * Simple in-memory InterceptorRegistry for testing purposes.
     */
    private static class SimpleInterceptorRegistry implements InterceptorRegistry {

        final List<InterceptorDefinition> definitions = new ArrayList<>();

        @Override
        public void register(InterceptorDefinition definition) {
            definitions.add(definition);
        }

        @Override
        public boolean unregister(String name) {
            return definitions.removeIf(d -> d.getName().equals(name));
        }

        @Override
        public List<InterceptorDefinition> getInterceptorsForClass(String className) {
            return new ArrayList<>(definitions);
        }

        @Override
        public List<InterceptorDefinition> getAllDefinitions() {
            return new ArrayList<>(definitions);
        }
    }
}
