package com.github.cc11001100.weavergirl.sample.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.servlet.http.HttpServlet;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.security.Authenticator;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.component.LifeCycle;
import org.h2.Driver;
import org.junit.jupiter.api.Test;

/**
 * The sample is the JDK 8 HTTP and JDBC attach target. Its runtime jars have to load there, and
 * the SQL {@link SampleApplication} runs has to execute on the H2 it actually ships.
 */
class SampleRuntimeJdk8Test {

  /** Java 8 class-file major version. Older majors are also loadable on JDK 8. */
  private static final int JAVA_8_MAJOR = 52;

  @Test
  void shippedSampleRuntimeJarsLoadOnJdk8() throws Exception {
    Map<String, Path> jars = new LinkedHashMap<String, Path>();
    addJar(jars, "h2", Driver.class);
    addJar(jars, "jetty-server", Server.class);
    addJar(jars, "jetty-servlet", ServletContextHandler.class);
    addJar(jars, "jetty-http", HttpField.class);
    addJar(jars, "jetty-io", EndPoint.class);
    addJar(jars, "jetty-util", LifeCycle.class);
    addJar(jars, "jetty-security", Authenticator.class);
    addJar(jars, "jetty-util-ajax", JSON.class);
    addJar(jars, "servlet-api", HttpServlet.class);
    if (jars.isEmpty()) {
      fail("sample runtime jars are absent");
    }
    int classes = 0;
    for (Map.Entry<String, Path> entry : jars.entrySet()) {
      int seen = scan(entry.getValue());
      classes += seen;
      System.out.println(
          "scanned sample runtime jar "
              + entry.getKey()
              + " "
              + entry.getValue().getFileName()
              + " root classes "
              + seen
              + " max major version "
              + JAVA_8_MAJOR);
    }
    assertTrue(classes > 0, "no sample runtime root classes were scanned");
    System.out.println(
        "sample runtime bytecode check passed: jars " + jars.size() + " root classes " + classes);
  }

  @Test
  void sampleSchemaRunsOnTheShippedH2() throws Exception {
    Class.forName("org.h2.Driver");
    String url = "jdbc:h2:mem:samplejdk8;DB_CLOSE_DELAY=-1";
    try (Connection conn = DriverManager.getConnection(url, "sa", "");
        Statement stmt = conn.createStatement()) {
      stmt.execute(
          "CREATE TABLE IF NOT EXISTS users ("
              + "id INT AUTO_INCREMENT PRIMARY KEY, "
              + "name VARCHAR(100) NOT NULL, "
              + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
      try (PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name) VALUES (?)")) {
        ps.setString(1, "Alice");
        assertEquals(1, ps.executeUpdate());
      }
      try (ResultSet rs = stmt.executeQuery("SELECT name FROM users ORDER BY id")) {
        assertTrue(rs.next());
        assertEquals("Alice", rs.getString("name"));
      }
    }
  }

  private static void addJar(Map<String, Path> jars, String label, Class<?> type) throws Exception {
    Path jar = jarOf(type);
    Path previous = jars.put(label, jar);
    if (previous != null && !previous.equals(jar)) {
      fail("sample runtime jar " + label + " resolved to both " + previous + " and " + jar);
    }
  }

  private static int scan(Path jar) throws IOException {
    int seen = 0;
    try (JarFile jarFile = new JarFile(jar.toFile())) {
      assertTrue(jarFile.getJarEntry("module-info.class") == null, "module-info at root: " + jar);
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
        assertTrue(
            !name.equals("module-info.class") && !name.endsWith("/module-info.class"),
            "module-info visible on JDK 8: " + jar + "!" + name);
        byte[] bytes = readFully(jarFile.getInputStream(entry));
        assertTrue(bytes.length >= 8, "class file too short: " + name);
        int major = ((bytes[6] & 0xFF) << 8) | (bytes[7] & 0xFF);
        assertTrue(major <= JAVA_8_MAJOR, jar.getFileName() + "!" + name + " major " + major);
        seen++;
      }
    }
    assertTrue(seen > 0, "jar has no root classes: " + jar);
    return seen;
  }

  private static Path jarOf(Class<?> type) throws Exception {
    assertTrue(type.getProtectionDomain() != null, "no protection domain for " + type.getName());
    assertTrue(
        type.getProtectionDomain().getCodeSource() != null, "no code source for " + type.getName());
    URL location = type.getProtectionDomain().getCodeSource().getLocation();
    assertTrue(location != null, "no code source location for " + type.getName());
    Path path = Paths.get(location.toURI());
    assertTrue(
        path.toString().endsWith(".jar"),
        "expected a jar for " + type.getName() + ": " + path);
    return path;
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
