package com.github.cc11001100.weavergirl.core.scanner;

import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Scans the classpath for classes annotated with {@link WeaveClass}
 * and registers them as interceptors.
 *
 * <p>Scanning is package-scoped: provide one or more base package names,
 * and the scanner will search for classes under those packages.</p>
 *
 * <h3>Usage:</h3>
 * <pre>
 * AnnotatedClassScanner scanner = new AnnotatedClassScanner();
 *
 * // Scan specific packages
 * Set&lt;Class&lt;?&gt;&gt; found = scanner.scan("com.example.interceptors", "com.example.aspects");
 *
 * // Load them into the registry
 * scanner.loadIntoRegistry(found, registry);
 *
 * // Or use the combined convenience method:
 * int count = scanner.scanAndLoad(registry, "com.example.interceptors");
 * </pre>
 *
 * <p><strong>Performance note:</strong> Classpath scanning can be expensive.
 * Only scan the packages you know contain interceptor classes, not the entire classpath.
 * For large codebases, prefer explicit registration via
 * {@link AnnotationPluginLoader#loadAnnotatedInterceptors}.</p>
 *
 * @since 1.2.0
 */
public class AnnotatedClassScanner {

    private static final Logger log = LoggerFactory.getLogger(AnnotatedClassScanner.class);

    private final AnnotationPluginLoader annotationPluginLoader;

    public AnnotatedClassScanner() {
        this.annotationPluginLoader = new AnnotationPluginLoader();
    }

    /**
     * Scan the classpath for classes annotated with {@code @WeaveClass}
     * under the given base packages.
     *
     * @param basePackages one or more base package names to scan
     * @return the set of classes found with {@code @WeaveClass} annotation
     */
    public Set<Class<?>> scan(String... basePackages) {
        Set<Class<?>> annotatedClasses = new HashSet<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }

        for (String basePackage : basePackages) {
            String packagePath = basePackage.replace('.', '/');
            try {
                Enumeration<URL> resources = classLoader.getResources(packagePath);
                while (resources.hasMoreElements()) {
                    URL resource = resources.nextElement();
                    Set<Class<?>> found = findClasses(resource, basePackage, classLoader);
                    annotatedClasses.addAll(found);
                }
            } catch (IOException e) {
                log.warn("Failed to scan package {}: {}", basePackage, e.getMessage());
            }
        }

        log.info("Scanned {} packages, found {} @WeaveClass-annotated classes",
                basePackages.length, annotatedClasses.size());
        return annotatedClasses;
    }

    /**
     * Load the discovered annotated classes into the given interceptor registry.
     *
     * @param annotatedClasses the classes to load (from {@link #scan})
     * @param registry the interceptor registry
     */
    public void loadIntoRegistry(Set<Class<?>> annotatedClasses, InterceptorRegistry registry) {
        annotationPluginLoader.loadAnnotatedInterceptors(annotatedClasses, registry);
    }

    /**
     * Convenience method: scan the given packages and load all discovered
     * {@code @WeaveClass}-annotated classes into the registry.
     *
     * @param registry the interceptor registry
     * @param basePackages the base packages to scan
     * @return the number of interceptor classes loaded
     */
    public int scanAndLoad(InterceptorRegistry registry, String... basePackages) {
        Set<Class<?>> found = scan(basePackages);
        loadIntoRegistry(found, registry);
        return found.size();
    }

    // --- Internal classpath scanning ---

    private Set<Class<?>> findClasses(URL resource, String packageName, ClassLoader classLoader) {
        Set<Class<?>> classes = new HashSet<>();
        String protocol = resource.getProtocol();

        if ("file".equals(protocol)) {
            findClassesInDirectory(new File(resource.getFile()), packageName, classLoader, classes);
        } else if ("jar".equals(protocol)) {
            findClassesInJar(resource, packageName, classLoader, classes);
        }

        return classes;
    }

    private void findClassesInDirectory(File directory, String packageName, ClassLoader classLoader,
                                        Set<Class<?>> classes) {
        if (!directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        for (File file : files) {
            if (file.isDirectory()) {
                findClassesInDirectory(file, packageName + "." + file.getName(), classLoader, classes);
            } else if (file.getName().endsWith(".class")) {
                String className = packageName + "." + file.getName().substring(0, file.getName().length() - 6);
                tryLoadClass(className, classLoader, classes);
            }
        }
    }

    private void findClassesInJar(URL jarUrl, String packageName, ClassLoader classLoader,
                                  Set<Class<?>> classes) {
        try {
            String jarPath = jarUrl.getPath();
            if (jarPath.contains("!")) {
                jarPath = jarPath.substring(0, jarPath.indexOf("!"));
            }
            if (jarPath.startsWith("file:")) {
                jarPath = jarPath.substring(5);
            }

            JarFile jarFile = new JarFile(jarPath);
            String packagePath = packageName.replace('.', '/');
            Enumeration<JarEntry> entries = jarFile.entries();

            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String entryName = entry.getName();
                if (entryName.startsWith(packagePath) && entryName.endsWith(".class")
                        && !entryName.contains("$")) {
                    String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
                    tryLoadClass(className, classLoader, classes);
                }
            }
            jarFile.close();
        } catch (IOException e) {
            log.warn("Failed to scan JAR {}: {}", jarUrl, e.getMessage());
        }
    }

    private void tryLoadClass(String className, ClassLoader classLoader, Set<Class<?>> classes) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            if (clazz.isAnnotationPresent(WeaveClass.class)) {
                classes.add(clazz);
            }
        } catch (ClassNotFoundException e) {
            // Class not found — skip silently
        } catch (NoClassDefFoundError e) {
            // Dependency not available — skip silently
        } catch (Exception e) {
            log.debug("Skipping class {}: {}", className, e.getMessage());
        }
    }
}
