package com.github.cc11001100.weavergirl.api.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Answers "is this class available to the application?" for class layouts a plain {@code
 * ClassLoader.getResource} cannot see.
 *
 * <p>The case that matters is a Spring Boot executable jar. Launched with {@code java -jar app.jar},
 * the application's own classes and every framework jar live inside the outer jar
 * ({@code BOOT-INF/classes/…}, {@code BOOT-INF/lib/*.jar}) and are reachable only through Spring
 * Boot's own nested-jar launcher, which does not exist yet when the agent runs from {@code premain}.
 * A servlet or Spring plugin that asked the class loader at that moment got "no", shut itself down,
 * and the application ran with the servlet, Spring MVC and Tomcat plugins all inactive — the most
 * common deployment shape in the ecosystem.
 *
 * <p>This class indexes the launch class path instead: plain directories, plain jars, and the nested
 * jars of a Spring Boot ({@code BOOT-INF/}, {@code WEB-INF/}) or thin layout. Nested jars are
 * extracted once to a temporary directory so they can be read with an ordinary {@link JarFile};
 * the extraction happens lazily, on the first lookup that misses, and never if every plugin's
 * library resolves normally.
 *
 * <p>Lookups are cached, including negative ones: the class layout cannot change under a running JVM
 * in a way that matters here.
 *
 * @since 1.0.0
 */
public final class ApplicationClasspath {

  /** Class-file path prefixes inside a launcher jar that hold application classes. */
  private static final String[] NESTED_CLASS_DIRS = {"BOOT-INF/classes/", "WEB-INF/classes/"};

  /** Entry prefixes that identify a nested-jar layout. */
  private static final String[] NESTED_LIB_PREFIXES = {"BOOT-INF/lib/", "WEB-INF/lib/"};

  private static final Map<String, Boolean> CACHE = new ConcurrentHashMap<>();

  private static volatile List<Source> sources;

  private ApplicationClasspath() {}

  /**
   * Whether {@code className}'s class file is reachable from the launch class path, including
   * classes nested inside a launcher jar.
   *
   * @param className a binary class name, for example {@code "javax.servlet.http.HttpServlet"}
   * @return true when the class file exists somewhere on the launch class path
   */
  public static boolean present(String className) {
    if (className == null || className.isEmpty()) {
      return false;
    }
    Boolean cached = CACHE.get(className);
    if (cached != null) {
      return cached;
    }
    String resource = className.replace('.', '/') + ".class";
    boolean found = false;
    for (Source source : sources()) {
      if (source.has(resource)) {
        found = true;
        break;
      }
    }
    CACHE.put(className, found);
    return found;
  }

  /** Forget the index. Test hook; the JVM does not change its class path under a running agent. */
  static void reset() {
    CACHE.clear();
    sources = null;
  }

  private static List<Source> sources() {
    List<Source> current = sources;
    if (current != null) {
      return current;
    }
    synchronized (ApplicationClasspath.class) {
      if (sources == null) {
        sources = build();
      }
      return sources;
    }
  }

  private static List<Source> build() {
    List<Source> built = new ArrayList<>();
    for (String entry : launchClassPath()) {
      addEntry(built, new File(entry));
    }
    // A URLClassLoader on the system class path still carries entries the property misses (some
    // launchers install one); add its URLs too, without duplicating the jars already listed.
    for (ClassLoader loader = Thread.currentThread().getContextClassLoader();
        loader != null;
        loader = loader.getParent()) {
      if (loader instanceof URLClassLoader) {
        for (URL url : ((URLClassLoader) loader).getURLs()) {
          if ("file".equals(url.getProtocol())) {
            addEntry(built, new File(url.getPath()));
          }
        }
      }
    }
    return Collections.unmodifiableList(built);
  }

  private static String[] launchClassPath() {
    String classPath = System.getProperty("java.class.path", "");
    if (classPath.isEmpty()) {
      return new String[0];
    }
    // The agent's own jar is on this path when attached with -javaagent; skipping it keeps the
    // index to application entries and avoids the agent answering for its own bundled libraries.
    List<String> entries = new ArrayList<>();
    File agentJar = agentJarPath();
    for (String entry : classPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
      if (entry.isEmpty()) {
        continue;
      }
      if (agentJar != null && new File(entry).getAbsoluteFile().equals(agentJar)) {
        continue;
      }
      entries.add(entry);
    }
    return entries.toArray(new String[0]);
  }

  private static File agentJarPath() {
    try {
      java.security.ProtectionDomain domain = ApplicationClasspath.class.getProtectionDomain();
      URL location = domain == null || domain.getCodeSource() == null
          ? null
          : domain.getCodeSource().getLocation();
      return location == null || !"file".equals(location.getProtocol())
          ? null
          : new File(location.getPath()).getAbsoluteFile();
    } catch (Throwable e) {
      return null;
    }
  }

  private static void addEntry(List<Source> built, File file) {
    if (file == null || !file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      built.add(new DirectorySource(file));
      return;
    }
    String name = file.getName().toLowerCase(java.util.Locale.ROOT);
    if (!name.endsWith(".jar") && !name.endsWith(".zip")) {
      return;
    }
    JarFile jar;
    try {
      jar = new JarFile(file);
    } catch (IOException e) {
      return;
    }
    built.add(new JarSource(jar));
    addNested(built, jar);
  }

  /**
   * Add the jars and class directories inside a launcher jar. Only the central directory is read
   * here; a nested jar is extracted on the first lookup that reaches for it.
   */
  private static void addNested(List<Source> built, JarFile outer) {
    java.util.Enumeration<JarEntry> entries = outer.entries();
    while (entries.hasMoreElements()) {
      JarEntry entry = entries.nextElement();
      String path = entry.getName();
      for (String prefix : NESTED_CLASS_DIRS) {
        if (path.startsWith(prefix) && path.endsWith(".class") && path.length() > prefix.length()) {
          built.add(new JarPrefixSource(outer, prefix));
          break;
        }
      }
      for (String prefix : NESTED_LIB_PREFIXES) {
        if (path.startsWith(prefix) && path.endsWith(".jar") && path.indexOf('/', prefix.length()) < 0) {
          built.add(new NestedJarSource(outer, path));
          break;
        }
      }
    }
  }

  /** One place a class file can live. */
  private interface Source {
    boolean has(String resource);
  }

  private static final class DirectorySource implements Source {
    private final File directory;

    DirectorySource(File directory) {
      this.directory = directory;
    }

    @Override
    public boolean has(String resource) {
      return new File(directory, resource).isFile();
    }
  }

  private static final class JarSource implements Source {
    private final JarFile jar;

    JarSource(JarFile jar) {
      this.jar = jar;
    }

    @Override
    public boolean has(String resource) {
      return jar.getEntry(resource) != null;
    }
  }

  /** {@code BOOT-INF/classes/} inside the launcher jar: classes are entries of the outer jar. */
  private static final class JarPrefixSource implements Source {
    private final JarFile jar;
    private final String prefix;

    JarPrefixSource(JarFile jar, String prefix) {
      this.jar = jar;
      this.prefix = prefix;
    }

    @Override
    public boolean has(String resource) {
      return jar.getEntry(prefix + resource) != null;
    }
  }

  /** A jar inside the launcher jar, extracted once so it can be read with a plain {@link JarFile}. */
  private static final class NestedJarSource implements Source {
    private final JarFile outer;
    private final String entryName;
    private volatile JarFile extracted;

    NestedJarSource(JarFile outer, String entryName) {
      this.outer = outer;
      this.entryName = entryName;
    }

    @Override
    public boolean has(String resource) {
      JarFile jar = extracted();
      return jar != null && jar.getEntry(resource) != null;
    }

    private JarFile extracted() {
      JarFile current = extracted;
      if (current != null) {
        return current;
      }
      synchronized (this) {
        if (extracted != null) {
          return extracted;
        }
        // Keep the name recognisable in a temp dir: index + original file name.
        String base = entryName.substring(entryName.lastIndexOf('/') + 1);
        try {
          File temp = File.createTempFile("weaver-nested-", "-" + base);
          temp.deleteOnExit();
          InputStream input = outer.getInputStream(outer.getEntry(entryName));
          try {
            OutputStream output = new FileOutputStream(temp);
            try {
              byte[] buffer = new byte[8192];
              int read;
              while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
              }
            } finally {
              output.close();
            }
          } finally {
            input.close();
          }
          extracted = new JarFile(temp);
        } catch (IOException e) {
          return null;
        }
        return extracted;
      }
    }
  }
}
