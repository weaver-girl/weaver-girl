package com.github.cc11001100.weavergirl.agent;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * JDK 1.8 loads every class at the root of a packaged jar. Descriptors that need a newer
 * class-file version stay under {@code META-INF/versions/}. The check reads packaged jars only.
 */
class ShippedBytecodeVersionTest {

  /** Java 8 class-file major version. Older majors are also loadable on JDK 8. */
  private static final int JAVA_8_MAJOR = 52;

  @Test
  void packagedJarsAreLoadableOnJdk8() throws Exception {
    boolean required = Boolean.parseBoolean(System.getProperty("weaver.checkPackagedJars", "false"));
    System.out.println("weaver.checkPackagedJars=" + required);
    Path agent = findAgentJar();
    List<Path> plugins = findPluginJars();
    List<Path> reactor = findOtherReactorJars();
    // A jar that is already on disk is scanned. Skipping is only for a clean tree
    // whose package phase has not run yet. One present jar must not be dropped
    // because the other side is missing.
    if (agent == null && plugins.isEmpty() && reactor.isEmpty()) {
      if (required) {
        fail("packaged agent jar, plugin jars, and other reactor runtime jars are absent");
      }
      Assumptions.assumeTrue(false, "packaged jars are not built yet");
    }
    if (required && (agent == null || plugins.isEmpty() || reactor.isEmpty())) {
      fail(
          "packaged jars are absent: agent="
              + agent
              + " plugins="
              + plugins.size()
              + " reactor="
              + reactor.size());
    }
    int jars = 0;
    int classes = 0;
    if (agent != null) {
      int agentClasses = scan(agent);
      jars++;
      classes += agentClasses;
      System.out.println(
          "scanned agent jar " + agent.getFileName() + " root classes " + agentClasses
              + " max major version " + JAVA_8_MAJOR);
    }
    for (Path plugin : plugins) {
      int pluginClasses = scan(plugin);
      jars++;
      classes += pluginClasses;
      System.out.println(
          "scanned plugin jar " + plugin.getFileName() + " root classes " + pluginClasses
              + " max major version " + JAVA_8_MAJOR);
    }
    for (Path runtime : reactor) {
      int runtimeClasses = scan(runtime);
      jars++;
      classes += runtimeClasses;
      System.out.println(
          "scanned reactor jar " + runtime.getFileName() + " root classes " + runtimeClasses
              + " max major version " + JAVA_8_MAJOR);
    }
    assertTrue(jars > 0 && classes > 0, "no packaged root classes were scanned");
    System.out.println(
        "packaged bytecode check passed: jars " + jars + " root classes " + classes
            + " reactor jars " + reactor.size() + " skipped false");
  }

  private static int scan(Path jar) throws IOException {
    int seen = 0;
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      if (jarFile.getJarEntry("module-info.class") != null) {
        fail("module-info.class is at the jar root: " + jar);
      }
      Enumeration<JarEntry> entries = jarFile.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (entry.isDirectory() || !name.endsWith(".class")) {
          continue;
        }
        if (name.startsWith("META-INF/versions/")) {
          continue;
        }
        if (name.equals("module-info.class") || name.endsWith("/module-info.class")) {
          fail("module-info.class is visible to a JDK 1.8 loader: " + jar + "!" + name);
        }
        int major = majorVersion(readFully(jarFile.getInputStream(entry)));
        assertTrue(major <= JAVA_8_MAJOR, jar.getFileName() + "!" + name + " major " + major);
        seen++;
      }
    }
    assertTrue(seen > 0, "jar has no root classes: " + jar);
    return seen;
  }

  /**
   * Runtime jars from reactor modules other than the agent fat jar and the plugin jars. JDK 8 loads
   * these roots too: api, core, annotation, bridges, runtime plugins, the sample, and the testkit.
   */
  private static List<Path> findOtherReactorJars() {
    List<Path> jars = new ArrayList<Path>();
    File root = reactorRoot();
    File[] children = root.listFiles();
    if (children == null) {
      return jars;
    }
    for (File child : children) {
      if (!child.isDirectory()) {
        continue;
      }
      String name = child.getName();
      if (!name.startsWith("weaver-girl-")) {
        continue;
      }
      if ("weaver-girl-agent".equals(name) || "weaver-girl-plugins".equals(name)) {
        continue;
      }
      collectModuleJar(child, jars);
      File[] nested = child.listFiles();
      if (nested == null) {
        continue;
      }
      for (File sub : nested) {
        if (sub.isDirectory() && sub.getName().startsWith("weaver-girl-")) {
          collectModuleJar(sub, jars);
        }
      }
    }
    return jars;
  }

  private static void collectModuleJar(File module, List<Path> jars) {
    File target = new File(module, "target");
    if (!target.isDirectory()) {
      return;
    }
    File[] files = target.listFiles();
    if (files == null) {
      return;
    }
    String prefix = module.getName() + "-";
    for (File file : files) {
      String name = file.getName();
      if (!name.startsWith(prefix) || !isRuntimeJar(name)) {
        continue;
      }
      if (name.startsWith("weaver-girl-agent-") || name.startsWith("weaver-girl-plugin-")) {
        continue;
      }
      jars.add(file.toPath());
    }
  }

  private static File reactorRoot() {
    // Surefire's cwd is the module directory. user.dir is that path with no trailing dot,
    // so the parent is the reactor root. getAbsoluteFile() on "." keeps the dot and
    // makes getParentFile() point at the module itself.
    File here = new File(System.getProperty("user.dir", "."));
    if (new File(here, "weaver-girl-agent").isDirectory()) {
      return here;
    }
    File parent = here.getParentFile();
    if (parent != null && new File(parent, "weaver-girl-agent").isDirectory()) {
      return parent;
    }
    return here;
  }

  private static Path findAgentJar() {
    File[] roots = {new File("target"), new File("weaver-girl-agent/target")};
    for (File dir : roots) {
      Path found = firstJar(dir, "weaver-girl-agent-");
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  private static List<Path> findPluginJars() {
    List<Path> jars = new ArrayList<Path>();
    File copied = new File("target/plugins");
    if (!copied.isDirectory()) {
      copied = new File("weaver-girl-agent/target/plugins");
    }
    if (copied.isDirectory()) {
      File[] files = copied.listFiles();
      if (files != null) {
        for (File file : files) {
          if (isRuntimeJar(file.getName())) {
            jars.add(file.toPath());
          }
        }
      }
    }
    if (!jars.isEmpty()) {
      return jars;
    }
    File plugins = new File("../weaver-girl-plugins");
    if (!plugins.isDirectory()) {
      plugins = new File("weaver-girl-plugins");
    }
    File[] modules = plugins.listFiles();
    if (modules == null) {
      return jars;
    }
    for (File module : modules) {
      File target = new File(module, "target");
      Path jar = firstJar(target, "weaver-girl-plugin-");
      if (jar != null) {
        jars.add(jar);
      }
    }
    return jars;
  }

  private static Path firstJar(File dir, String prefix) {
    if (dir == null || !dir.isDirectory()) {
      return null;
    }
    File[] files = dir.listFiles();
    if (files == null) {
      return null;
    }
    for (File file : files) {
      String name = file.getName();
      if (name.startsWith(prefix) && isRuntimeJar(name)) {
        return file.toPath();
      }
    }
    return null;
  }

  private static boolean isRuntimeJar(String name) {
    return name.endsWith(".jar")
        && !name.contains("-sources")
        && !name.contains("-javadoc")
        && !name.startsWith("original-");
  }

  private static int majorVersion(byte[] bytes) {
    assertTrue(bytes.length >= 8, "class file too short");
    assertTrue(
        bytes[0] == (byte) 0xCA
            && bytes[1] == (byte) 0xFE
            && bytes[2] == (byte) 0xBA
            && bytes[3] == (byte) 0xBE,
        "not a class file");
    return ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
  }

  private static byte[] readFully(InputStream in) throws IOException {
    try (InputStream source = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = source.read(buf)) >= 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    }
  }
}
