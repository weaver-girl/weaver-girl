package com.github.cc11001100.weavergirl.core.scanner;

import com.github.cc11001100.weavergirl.annotation.WeaveClass;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import com.github.cc11001100.weavergirl.core.plugin.AnnotationPluginLoader;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scans the classpath for classes annotated with {@link WeaveClass} and registers them as
 * interceptors.
 *
 * <p>Scanning is package-scoped: provide one or more base package names, and the scanner will
 * search for classes under those packages.
 *
 * <h3>Usage:</h3>
 *
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
 * <p><strong>Performance note:</strong> Classpath scanning can be expensive. Only scan the packages
 * you know contain interceptor classes, not the entire classpath. For large codebases, prefer
 * explicit registration via {@link AnnotationPluginLoader#loadAnnotatedInterceptors}.
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
   * Scan the classpath for classes annotated with {@code @WeaveClass} under the given base
   * packages.
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

    log.info(
        "Scanned {} packages, found {} @WeaveClass-annotated classes",
        basePackages.length,
        annotatedClasses.size());
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

  private void findClassesInDirectory(
      File directory, String packageName, ClassLoader classLoader, Set<Class<?>> classes) {
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
        String className =
            packageName + "." + file.getName().substring(0, file.getName().length() - 6);
        tryLoadClass(className, classLoader, classes, file);
      }
    }
  }

  private void findClassesInJar(
      URL jarUrl, String packageName, ClassLoader classLoader, Set<Class<?>> classes) {
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
        if (entryName.startsWith(packagePath)
            && entryName.endsWith(".class")
            && !entryName.contains("$")) {
          String className = entryName.replace('/', '.').substring(0, entryName.length() - 6);
          tryLoadClass(className, classLoader, classes, jarFile, entry);
        }
      }
      jarFile.close();
    } catch (IOException e) {
      log.warn("Failed to scan JAR {}: {}", jarUrl, e.getMessage());
    }
  }

  /**
   * Whether the class-file bytes name {@code @WeaveClass} among their runtime-visible annotations.
   *
   * <p>Checked on the bytes before the class is loaded. {@link Class#forName} defines the class,
   * and the transformer is installed after plugin loading — a target class loaded here is past the
   * transformer and is never woven at premain. Reading only this one attribute keeps the scan from
   * loading every class in the package.
   */
  static boolean hasWeaveClassInBytes(byte[] classFile) {
    try (java.io.DataInputStream in =
        new java.io.DataInputStream(new java.io.ByteArrayInputStream(classFile))) {
      if (readU4(in) != 0xCAFEBABEL) {
        return false;
      }
      readU2(in); // minor
      readU2(in); // major
      int constantPoolCount = readU2(in);
      // The class-file annotation attribute names its annotation type by a UTF8 entry; collect
      // those now so the attribute scan below is a substring test over the pool.
      String[] utf8 = new String[constantPoolCount];
      for (int i = 1; i < constantPoolCount; i++) {
        int tag = readU1(in);
        switch (tag) {
          case 1: // Utf8
            utf8[i] = readModifiedUtf8(in);
            break;
          case 7: // Class
          case 8: // String
          case 16: // MethodType
          case 19: // Module
          case 20: // Package
            readU2(in);
            break;
          case 15: // MethodHandle
            readU1(in);
            readU2(in);
            break;
          case 3: // Integer
          case 4: // Float
          case 9: // Fieldref
          case 10: // Methodref
          case 11: // InterfaceMethodref
          case 12: // NameAndType
          case 17: // Dynamic
          case 18: // InvokeDynamic
            skip(in, 4);
            break;
          case 5: // Long
          case 6: // Double
            skip(in, 8);
            i++; // a long/double takes two pool slots
            break;
          default:
            return false; // unknown tag — refuse rather than guess at the layout
        }
      }
      readU2(in); // access_flags
      readU2(in); // this_class
      readU2(in); // super_class
      int interfaces = readU2(in);
      skip(in, 2L * interfaces);
      int fields = readU2(in);
      for (int i = 0; i < fields; i++) {
        skip(in, 6); // name_index, descriptor_index, attributes_count
        skipAttributes(in);
      }
      int methods = readU2(in);
      for (int i = 0; i < methods; i++) {
        skip(in, 6);
        skipAttributes(in);
      }
      // The class-level attributes, where RuntimeVisibleAnnotations lives.
      int attributes = readU2(in);
      for (int i = 0; i < attributes; i++) {
        int nameIndex = readU2(in);
        int length = (int) readU4(in);
        if ("RuntimeVisibleAnnotations".equals(utf8[nameIndex])) {
          return annotationsNameWeaveClass(in, utf8, length);
        }
        skip(in, length);
      }
      return false;
    } catch (java.io.IOException | RuntimeException unreadable) {
      return false;
    }
  }

  /** Walk a RuntimeVisibleAnnotations payload looking for a runtime-visible {@code WeaveClass}. */
  private static boolean annotationsNameWeaveClass(
      java.io.DataInputStream in, String[] utf8, int length) throws java.io.IOException {
    java.io.DataInputStream body =
        new java.io.DataInputStream(
            new java.io.ByteArrayInputStream(readBytes(in, length)));
    int annotations = body.readUnsignedShort();
    String weaveClassName = WeaveClass.class.getName();
    for (int i = 0; i < annotations; i++) {
      int typeIndex = body.readUnsignedShort();
      // The pool entry is a field descriptor — Lcom/…/WeaveClass; — not a dotted class name.
      if (weaveClassName.equals(descriptorToClassName(utf8[typeIndex]))) {
        return true;
      }
      // type_index (already read) then num_element_value_pairs, each pair: name + value.
      int pairs = body.readUnsignedShort();
      for (int p = 0; p < pairs; p++) {
        body.readUnsignedShort(); // element_name_index
        skipAnnotationValue(body);
      }
    }
    return false;
  }

  /** {@code Lcom/example/Foo;} → {@code com.example.Foo}; anything else is returned unchanged. */
  static String descriptorToClassName(String descriptor) {
    if (descriptor == null
        || descriptor.length() < 3
        || descriptor.charAt(0) != 'L'
        || descriptor.charAt(descriptor.length() - 1) != ';') {
      return descriptor;
    }
    return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
  }

  private static void skipAnnotationValue(java.io.DataInputStream in) throws java.io.IOException {
    int tag = in.readUnsignedByte();
    switch (tag) {
      case 'B':
      case 'C':
      case 'D':
      case 'F':
      case 'I':
      case 'J':
      case 'S':
      case 'Z':
      case 'e': // enum: type_name_index + const_name_index
        in.readUnsignedShort();
        in.readUnsignedShort();
        break;
      case 'c': // class_info
      case 's': // string const
        in.readUnsignedShort();
        break;
      case '@': // annotation
        in.readUnsignedShort(); // type_index
        int pairs = in.readUnsignedShort();
        for (int p = 0; p < pairs; p++) {
          in.readUnsignedShort();
          skipAnnotationValue(in);
        }
        break;
      case '[': // array
        int values = in.readUnsignedShort();
        for (int v = 0; v < values; v++) {
          skipAnnotationValue(in);
        }
        break;
      default:
        throw new java.io.IOException("unknown annotation value tag " + tag);
    }
  }

  private static void skipAttributes(java.io.DataInputStream in) throws java.io.IOException {
    int count = readU2(in);
    for (int i = 0; i < count; i++) {
      readU2(in); // attribute_name_index
      skip(in, readU4(in));
    }
  }

  private static int readU1(java.io.DataInputStream in) throws java.io.IOException {
    return in.readUnsignedByte();
  }

  private static int readU2(java.io.DataInputStream in) throws java.io.IOException {
    return in.readUnsignedShort();
  }

  private static long readU4(java.io.DataInputStream in) throws java.io.IOException {
    return in.readInt() & 0xFFFFFFFFL;
  }

  private static void skip(java.io.DataInputStream in, long bytes) throws java.io.IOException {
    if (bytes <= 0) {
      return;
    }
    in.readFully(new byte[(int) bytes]);
  }

  private static byte[] readBytes(java.io.DataInputStream in, int length) throws java.io.IOException {
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return bytes;
  }

  private static String readModifiedUtf8(java.io.DataInputStream in) throws java.io.IOException {
    int length = in.readUnsignedShort();
    byte[] bytes = new byte[length];
    in.readFully(bytes);
    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
  }

  private void tryLoadClass(String className, ClassLoader classLoader, Set<Class<?>> classes) {
    try {
      Class<?> clazz = Class.forName(className, false, classLoader);
      if (isWeaveClass(clazz)) {
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

  private void tryLoadClass(
      String className, ClassLoader classLoader, Set<Class<?>> classes, File classFile) {
    try {
      if (!hasWeaveClassInBytes(readFully(new java.io.FileInputStream(classFile)))) {
        return;
      }
    } catch (java.io.IOException unreadable) {
      return;
    }
    tryLoadClass(className, classLoader, classes);
  }

  private void tryLoadClass(
      String className, ClassLoader classLoader, Set<Class<?>> classes, JarFile jar, JarEntry entry) {
    try {
      if (!hasWeaveClassInBytes(readFully(jar.getInputStream(entry)))) {
        return;
      }
    } catch (java.io.IOException unreadable) {
      return;
    }
    tryLoadClass(className, classLoader, classes);
  }

  /** Read a stream to the end and close it. Java 8 has no {@code InputStream.readAllBytes}. */
  private static byte[] readFully(java.io.InputStream in) throws java.io.IOException {
    try (java.io.InputStream source = in) {
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      while ((read = source.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toByteArray();
    }
  }

  /**
   * Whether {@code clazz} carries {@code @WeaveClass}, asked by name rather than by class object.
   *
   * <p>Under the agent the annotation is loaded twice: once on the system class path, where the
   * application's own annotated classes resolve it, and once inside the isolated agent loader, where
   * this scanner runs. {@link Class#isAnnotationPresent} compares annotation <em>types</em>, so the
   * two copies are different types and every application class looks un-annotated — the scan
   * activated, logged a count of zero, and nothing was ever woven. Matching on the fully qualified
   * name makes the two copies agree, which is the only thing the annotation's identity carries
   * across the boundary.
   */
  private static boolean isWeaveClass(Class<?> clazz) {
    for (java.lang.annotation.Annotation annotation : clazz.getAnnotations()) {
      if (WeaveClass.class.getName().equals(annotation.annotationType().getName())) {
        return true;
      }
    }
    return false;
  }
}
