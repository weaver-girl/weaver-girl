package com.github.cc11001100.weavergirl.core.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Detects runtime environment capabilities for compatibility-aware behavior.
 *
 * <p>Checks for:</p>
 * <ul>
 *   <li>Java version and features (Virtual Threads, sealed classes, etc.)</li>
 *   <li>Framework presence (Spring Boot, Quarkus, Micronaut)</li>
 *   <li>Reactive stack (WebFlux, Reactor)</li>
 *   <li>GraalVM Native Image</li>
 * </ul>
 *
 * @since 1.1.0
 */
public final class RuntimeCompatibility {

    private static final Logger log = LoggerFactory.getLogger(RuntimeCompatibility.class);

    private static final Map<String, Boolean> CAPABILITIES = new LinkedHashMap<>();
    private static final Map<String, String> FRAMEWORK_VERSIONS = new LinkedHashMap<>();

    static {
        detectAll();
    }

    private RuntimeCompatibility() {
    }

    private static void detectAll() {
        // Java version
        String javaVersion = System.getProperty("java.version", "unknown");
        String javaSpec = System.getProperty("java.specification.version", "1.8");
        int majorVersion = parseMajorVersion(javaSpec);
        CAPABILITIES.put("java.version." + majorVersion, true);
        FRAMEWORK_VERSIONS.put("java", javaVersion);

        // Virtual Threads (Java 21+)
        CAPABILITIES.put("virtual-threads", majorVersion >= 21);

        // Sealed classes (Java 17+)
        CAPABILITIES.put("sealed-classes", majorVersion >= 17);

        // Record classes (Java 16+)
        CAPABILITIES.put("record-classes", majorVersion >= 16);

        // GraalVM Native Image
        boolean isNativeImage = System.getProperty("org.graalvm.nativeimage.imagecode") != null;
        CAPABILITIES.put("graalvm-native", isNativeImage);
        if (isNativeImage) {
            log.info("[Compat] Running in GraalVM Native Image mode — bytecode instrumentation limited");
        }

        // Framework detection
        detectFramework("Spring Boot", "org.springframework.boot.SpringBootVersion");
        detectFramework("Spring Framework", "org.springframework.core.SpringVersion");
        detectFramework("Quarkus", "io.quarkus.runtime.Quarkus");
        detectFramework("Micronaut", "io.micronaut.core.version.VersionUtils");
        detectFramework("Reactor", "reactor.core.publisher.Flux");
        detectFramework("WebFlux", "org.springframework.web.reactive.DispatcherHandler");
        detectFramework("gRPC", "io.grpc.Version");
        detectFramework("Kafka Client", "org.apache.kafka.common.utils.Utils");
        detectFramework("Redis (Jedis)", "redis.clients.jedis.Jedis");
        detectFramework("MongoDB Driver", "com.mongodb.MongoClientSettings");

        log.info("[Compat] Runtime: Java {} (spec={}), capabilities={}",
                javaVersion, javaSpec, CAPABILITIES);
    }

    private static void detectFramework(String name, String className) {
        try {
            Class<?> clazz = Class.forName(className);
            CAPABILITIES.put(name.toLowerCase().replace(' ', '-'), true);
            // Try to get version
            try {
                if (name.equals("Spring Boot")) {
                    String ver = (String) clazz.getMethod("getVersion").invoke(null);
                    FRAMEWORK_VERSIONS.put(name, ver);
                } else if (name.equals("Spring Framework")) {
                    String ver = (String) clazz.getMethod("getVersion").invoke(null);
                    FRAMEWORK_VERSIONS.put(name, ver);
                }
            } catch (Exception ignored) {
                // Version not available
            }
            log.debug("[Compat] {} detected", name);
        } catch (ClassNotFoundException e) {
            CAPABILITIES.put(name.toLowerCase().replace(' ', '-'), false);
        }
    }

    private static int parseMajorVersion(String spec) {
        try {
            if (spec.startsWith("1.")) {
                return Integer.parseInt(spec.substring(2));
            }
            return Integer.parseInt(spec);
        } catch (NumberFormatException e) {
            return 8; // assume minimum
        }
    }

    // ===== Public API =====

    /**
     * Check if a specific capability is available.
     *
     * @param capability the capability name (e.g. "virtual-threads", "spring-boot")
     * @return true if available
     */
    public static boolean hasCapability(String capability) {
        return CAPABILITIES.getOrDefault(capability, false);
    }

    /**
     * Check if Virtual Threads are available (Java 21+).
     */
    public static boolean hasVirtualThreads() {
        return hasCapability("virtual-threads");
    }

    /**
     * Check if running in GraalVM Native Image mode.
     */
    public static boolean isNativeImage() {
        return hasCapability("graalvm-native");
    }

    /**
     * Check if Spring Boot is present.
     */
    public static boolean hasSpringBoot() {
        return hasCapability("spring-boot");
    }

    /**
     * Check if reactive stack (Reactor/WebFlux) is present.
     */
    public static boolean hasReactiveStack() {
        return hasCapability("reactor") || hasCapability("webflux");
    }

    /**
     * Get the Java major version.
     */
    public static int getJavaMajorVersion() {
        String spec = System.getProperty("java.specification.version", "1.8");
        return parseMajorVersion(spec);
    }

    /**
     * Get all detected capabilities.
     */
    public static Map<String, Boolean> getAllCapabilities() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(CAPABILITIES));
    }

    /**
     * Get detected framework versions.
     */
    public static Map<String, String> getFrameworkVersions() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(FRAMEWORK_VERSIONS));
    }

    /**
     * Generate a compatibility summary for logging.
     */
    public static String getSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Runtime Compatibility:\n");
        sb.append("  Java: ").append(FRAMEWORK_VERSIONS.get("java")).append("\n");
        sb.append("  Virtual Threads: ").append(hasVirtualThreads()).append("\n");
        sb.append("  GraalVM Native: ").append(isNativeImage()).append("\n");

        for (Map.Entry<String, Boolean> entry : CAPABILITIES.entrySet()) {
            if (!entry.getKey().startsWith("java.") && entry.getValue()) {
                sb.append("  ").append(entry.getKey()).append(": present\n");
            }
        }
        return sb.toString();
    }
}
