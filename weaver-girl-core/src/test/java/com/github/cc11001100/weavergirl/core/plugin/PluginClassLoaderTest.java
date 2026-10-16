package com.github.cc11001100.weavergirl.core.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileOutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A plugin's class loader, and the classes it is allowed to see from another plugin. */
class PluginClassLoaderTest {

  @TempDir Path tempDir;

  private static PluginClassLoader loader() {
    return new PluginClassLoader(new URL[0], PluginClassLoaderTest.class.getClassLoader());
  }

  /** A null export, or one a loader grants to itself, is ignored. */
  @Test
  void anExportThatNamesNothingIsIgnored() throws Exception {
    PluginClassLoader own = loader();
    own.addExport(null, loader(), false);
    own.addExport("com.example", null, false);
    own.addExport("com.example", own, false);

    assertEquals(String.class, own.loadClass("java.lang.String"));
  }

  /**
   * A class another plugin exported by name is loaded from that plugin, and asking for it to be resolved
   * links it. A class in the default package has no package, so a package export can never match it.
   */
  @Test
  void anExportedClassIsLoadedFromTheExporter() throws Exception {
    PluginClassLoader exporter = loader();
    PluginClassLoader consumer = loader();
    consumer.addExport(String.class.getName(), exporter, true);

    assertEquals(String.class, consumer.loadClass(String.class.getName(), true));

    PluginClassLoader packaged = loader();
    packaged.addExport("com.example", loader(), false);
    assertThrows(ClassNotFoundException.class, () -> packaged.loadClass("NotInAPackage", true));
  }

  /** A class that lives in the plugin's own jar is loaded from there, and resolving it links it. */
  @Test
  void aClassInThePluginsOwnJarIsResolved() throws Exception {
    Path jar = tempDir.resolve("plugin.jar");
    String entry = Probe.class.getName().replace('.', '/') + ".class";
    JarOutputStream out = new JarOutputStream(new FileOutputStream(jar.toFile()));
    try {
      out.putNextEntry(new JarEntry(entry));
      java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(entry);
      byte[] buf = new byte[4096];
      int read;
      while ((read = in.read(buf)) != -1) {
        out.write(buf, 0, read);
      }
      in.close();
      out.closeEntry();
    } finally {
      out.close();
    }

    PluginClassLoader own = new PluginClassLoader(new URL[] {jar.toUri().toURL()}, getClass().getClassLoader());
    Class<?> loaded = own.loadClass(Probe.class.getName(), true);

    assertEquals(Probe.class.getName(), loaded.getName());
    assertSame(own, loaded.getClassLoader());
  }

  /** A class small enough to pack into a jar on its own. */
  public static class Probe {}
}
