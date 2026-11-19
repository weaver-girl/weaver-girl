package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers the launcher-jar layouts {@link ApplicationClasspath} exists to index. */
class ApplicationClasspathTest {

  private String originalClassPath;

  @BeforeEach
  void setUp() {
    originalClassPath = System.getProperty("java.class.path");
    ApplicationClasspath.reset();
  }

  @AfterEach
  void tearDown() {
    System.setProperty("java.class.path", originalClassPath);
    ApplicationClasspath.reset();
  }

  @Test
  void present_findsAClassInAPlainJarOnTheClassPath() throws Exception {
    File jar = jarOf("example/Target.class");
    System.setProperty("java.class.path", jar.getAbsolutePath());

    assertTrue(ApplicationClasspath.present("example.Target"));
  }

  @Test
  void present_reportsFalseForAMissingClass() throws Exception {
    File jar = jarOf("example/Target.class");
    System.setProperty("java.class.path", jar.getAbsolutePath());

    assertFalse(ApplicationClasspath.present("example.Absent"));
  }

  @Test
  void present_findsClassesBundledUnderBootInfClasses() throws Exception {
    File fatJar = fatJarOf("BOOT-INF/classes/com/example/AppServlet.class", null, null);
    System.setProperty("java.class.path", fatJar.getAbsolutePath());

    assertTrue(ApplicationClasspath.present("com.example.AppServlet"));
  }

  @Test
  void present_findsClassesInsideAJarNestedInBootInfLib() throws Exception {
    File inner = jarOf("javax/servlet/http/HttpServlet.class");
    File fatJar = fatJarOf(null, "BOOT-INF/lib/servlet-api-4.0.1.jar", inner);
    System.setProperty("java.class.path", fatJar.getAbsolutePath());

    assertTrue(ApplicationClasspath.present("javax.servlet.http.HttpServlet"));
  }

  @Test
  void present_reportsFalseWhenTheNestedJarDoesNotContainTheClass() throws Exception {
    File inner = jarOf("javax/servlet/http/HttpServlet.class");
    File fatJar = fatJarOf(null, "BOOT-INF/lib/servlet-api-4.0.1.jar", inner);
    System.setProperty("java.class.path", fatJar.getAbsolutePath());

    assertFalse(ApplicationClasspath.present("jakarta.servlet.http.HttpServlet"));
  }

  @Test
  void classPresent_fallsBackToTheLaunchClassPath() throws Exception {
    File fatJar = fatJarOf("BOOT-INF/classes/com/example/NestedOnly.class", null, null);
    System.setProperty("java.class.path", fatJar.getAbsolutePath());
    ApplicationClasspath.reset();

    // The loader chain cannot see a class inside BOOT-INF/classes; classPresent must not report it
    // missing, or every servlet/Spring plugin deactivates itself in a Spring Boot fat jar.
    assertTrue(new NoOpContext().classPresent(null, "com.example.NestedOnly"));
  }

  @Test
  void classPresent_stillUsesTheLoaderChainFirst() {
    assertTrue(new NoOpContext().classPresent(null, "java.lang.String"));
  }

  private static File jarOf(String entry) throws IOException {
    File jar = File.createTempFile("acp-plain-", ".jar");
    jar.deleteOnExit();
    OutputStream out = new FileOutputStream(jar);
    JarOutputStream jarOut = new JarOutputStream(out);
    try {
      jarOut.putNextEntry(new JarEntry(entry));
      jarOut.write("stub".getBytes(StandardCharsets.UTF_8));
      jarOut.closeEntry();
    } finally {
      jarOut.close();
    }
    return jar;
  }

  private static File fatJarOf(String classEntry, String nestedEntry, File nested) throws IOException {
    File jar = File.createTempFile("acp-fat-", ".jar");
    jar.deleteOnExit();
    OutputStream out = new FileOutputStream(jar);
    JarOutputStream jarOut = new JarOutputStream(out);
    try {
      if (classEntry != null) {
        jarOut.putNextEntry(new JarEntry(classEntry));
        jarOut.write("stub".getBytes(StandardCharsets.UTF_8));
        jarOut.closeEntry();
      }
      if (nestedEntry != null) {
        jarOut.putNextEntry(new JarEntry(nestedEntry));
        java.io.InputStream input = new java.io.FileInputStream(nested);
        try {
          byte[] buffer = new byte[8192];
          int read;
          while ((read = input.read(buffer)) != -1) {
            jarOut.write(buffer, 0, read);
          }
        } finally {
          input.close();
        }
        jarOut.closeEntry();
      }
    } finally {
      jarOut.close();
    }
    return jar;
  }

  /** A context that answers nothing itself, so only the default classPresent logic runs. */
  private static final class NoOpContext implements PluginContext {
    @Override
    public com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry getRegistry() {
      return null;
    }

    @Override
    public String getConfig(String key) {
      return null;
    }

    @Override
    public String getConfig(String key, String defaultValue) {
      return defaultValue;
    }

    @Override
    public java.util.Map<String, String> getAllConfig() {
      return java.util.Collections.emptyMap();
    }

    @Override
    public String getPluginName() {
      return "test";
    }
  }
}
