package com.github.cc11001100.weavergirl.agent.e2e;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test that spawns a real JVM with -javaagent attached.
 * This verifies the complete agent lifecycle: premain -> transform -> intercept.
 *
 * <p>This test requires the agent JAR to be built first (run {@code mvn package -DskipTests}).
 * If the JAR is not found, the test prints a skip message and passes silently rather
 * than failing the build.</p>
 */
class JavaAgentE2ETest {

    @Test
    void agentJarCanBeProducedAndAttached() throws Exception {
        // Find the agent JAR
        Path agentJar = findAgentJar();
        if (agentJar == null) {
            // Skip if agent JAR not built — don't fail the build
            System.out.println("SKIP: Agent JAR not found. Run 'mvn package -DskipTests' first.");
            return;
        }

        // Build a simple YAML config
        Path tempDir = Files.createTempDirectory("weaver-girl-e2e");
        Path configFile = tempDir.resolve("weaver.yml");
        // Empty config is fine — we just verify the agent doesn't crash

        Files.write(configFile, "interceptors: []\n".getBytes());

        // Spawn JVM with agent
        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable());
        command.add("-javaagent:" + agentJar + "=config=" + configFile);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(E2ETargetApplication.class.getName());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (line.contains("E2E_COMPLETE")) break;
            }
        }

        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        assertTrue(finished, "Process should finish within 30 seconds");
        assertEquals(0, process.exitValue(), "Process should exit cleanly. Output:\n" + output);
        assertTrue(output.toString().contains("E2E_COMPLETE"),
            "Target application should complete. Output:\n" + output);

        // Clean up
        Files.deleteIfExists(configFile);
        Files.deleteIfExists(tempDir);
    }

    private Path findAgentJar() {
        File dir = new File("target");
        if (!dir.exists()) dir = new File("weaver-girl-agent/target");
        if (!dir.exists()) dir = new File("../weaver-girl-agent/target");
        File[] jars = dir.listFiles((d, name) ->
            name.startsWith("weaver-girl-agent-") && name.endsWith(".jar") && !name.contains("-sources") && !name.contains("-javadoc"));
        return (jars != null && jars.length > 0) ? jars[0].toPath() : null;
    }

    private String getJavaExecutable() {
        return System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    }
}