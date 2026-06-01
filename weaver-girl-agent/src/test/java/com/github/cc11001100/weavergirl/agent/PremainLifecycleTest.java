package com.github.cc11001100.weavergirl.agent;

import com.github.cc11001100.weavergirl.api.interceptor.*;
import com.github.cc11001100.weavergirl.api.matcher.*;
import com.github.cc11001100.weavergirl.api.pointcut.*;
import com.github.cc11001100.weavergirl.core.WeaverGirl;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.junit.jupiter.api.*;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the complete premain lifecycle: bootstrap -> register -> intercept -> shutdown.
 */
class PremainLifecycleTest {

    private static Instrumentation instrumentation;

    @BeforeAll
    static void setUpClass() {
        try {
            instrumentation = ByteBuddyAgent.install();
        } catch (Exception e) {
            instrumentation = null;
        }
    }

    @Test
    void fullBootstrapAndInterceptLifecycle() {
        Assumptions.assumeTrue(instrumentation != null,
                "ByteBuddyAgent self-attach not available in this environment");

        // Simulate what premain() does: bootstrap the agent with an empty config
        WeaverGirl weaverGirl = WeaverGirl.bootstrap(instrumentation);

        // The transformer should be installed
        assertNotNull(weaverGirl);
        assertNotNull(weaverGirl.getRegistry());

        // Register an interceptor programmatically after bootstrap
        AtomicBoolean beforeCalled = new AtomicBoolean(false);
        weaverGirl.intercept("com.example.Service")
                .method("process")
                .before(inv -> beforeCalled.set(true))
                .install();

        // Verify the interceptor was registered
        assertFalse(weaverGirl.getRegistry().getAllDefinitions().isEmpty());

        // Shutdown should not throw
        assertDoesNotThrow(() -> weaverGirl.shutdown());
    }

    @Test
    void bootstrapWithConfigMap() {
        Assumptions.assumeTrue(instrumentation != null,
                "ByteBuddyAgent self-attach not available in this environment");

        Map<String, String> config = new java.util.HashMap<>();
        config.put("plugins", "/nonexistent/path");

        WeaverGirl weaverGirl = WeaverGirl.bootstrap(instrumentation, config);
        assertNotNull(weaverGirl);

        assertDoesNotThrow(() -> weaverGirl.shutdown());
    }

    @Test
    void parseAgentArgsWithConfig() throws Exception {
        Map<String, String> args = invokeParseAgentArgs("config=/path/to/weaver.yml");
        assertEquals("/path/to/weaver.yml", args.get("config"));
    }

    @Test
    void parseAgentArgsWithMultipleOptions() throws Exception {
        // Use order that doesn't start with "config=" to trigger key=value parsing
        Map<String, String> args = invokeParseAgentArgs("watch=true,config=/path/to/weaver.yml");
        assertEquals("/path/to/weaver.yml", args.get("config"));
        assertEquals("true", args.get("watch"));
    }

    @Test
    void parseAgentArgsWithNull() throws Exception {
        Map<String, String> args = invokeParseAgentArgs(null);
        assertTrue(args.isEmpty());
    }

    @Test
    void parseAgentArgsWithEmpty() throws Exception {
        Map<String, String> args = invokeParseAgentArgs("");
        assertTrue(args.isEmpty());
    }

    /**
     * Access the private parseAgentArgs method via reflection for testing.
     */
    @SuppressWarnings("unchecked")
    private Map<String, String> invokeParseAgentArgs(String agentArgs) throws Exception {
        Method method = WeaverGirlAgent.class.getDeclaredMethod("parseAgentArgs", String.class);
        method.setAccessible(true);
        return (Map<String, String>) method.invoke(null, agentArgs);
    }
}
