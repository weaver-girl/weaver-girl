/*
 * Minimal JDK 8+ compatible target for the cross-JDK agent-attach smoke test.
 *
 * Why this exists: the Jetty/H2 sample app cannot run on JDK 8 (H2 2.2 requires
 * Java 11), but weaver-girl targets Java 1.8 bytecode and must attach on JDK 8.
 * This class compiles to 1.8 bytecode with any modern javac and runs on every JDK
 * from 8 onward. It does nothing but stay alive long enough for the agent
 * (attached via -javaagent) to bootstrap and serve its /health, /ready and
 * /metrics endpoints. Built-in plugins still register interceptor definitions, so
 * /ready reports READY even though no app class matches an interceptor.
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
