/*
 * Minimal JDK 8+ compatible target for the cross-JDK agent-attach smoke test.
 *
 * Why this exists: the cross-JDK matrix only needs to prove the agent attaches.
 * This class compiles to 1.8 bytecode with any modern javac and runs on every JDK
 * from 8 onward. It does nothing but stay alive long enough for the agent
 * (attached via -javaagent) to bootstrap and serve its /health, /ready and
 * /metrics endpoints. Built-in plugins still register interceptor definitions, so
 * /ready reports READY even though no app class matches an interceptor.
 * Servlet and JDBC interception is the sample application's job
 * (com.github.cc11001100.weavergirl.sample.app.SampleApplication), which runs on
 * JDK 8 with H2 2.2.224. An attach-only launch of this class does not prove that.
 *
 * Compile:   javac -source 8 -target 8 MinimalApp.java
 * Run:       java -javaagent:weaver-girl-agent.jar=healthPort=9501 MinimalApp
 */
public class MinimalApp {
    public static void main(String[] args) throws Exception {
        System.out.println("MinimalApp started on JDK "
                + System.getProperty("java.version"));
        // Stay alive so the agent's endpoints remain reachable for the test.
        Thread.sleep(60_000);
    }
}
