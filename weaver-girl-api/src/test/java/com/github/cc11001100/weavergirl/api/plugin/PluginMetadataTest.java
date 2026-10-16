package com.github.cc11001100.weavergirl.api.plugin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginMetadataTest {

  @Test
  void builderAndPropertiesPreserveMetadata() throws Exception {
    PluginMetadata metadata =
        PluginMetadata.fromProperties(
            new ByteArrayInputStream(
                ("plugin.name=demo\n"
                        + "plugin.version=1.2.3\n"
                        + "plugin.description=Demo\n"
                        + "plugin.author=Team\n"
                        + "plugin.depends= jdbc, , redis \n"
                        + "plugin.requires= storage, , audit \n"
                        + "plugin.exports= com.example.clock, , com.example.clock.Clock class \n"
                        + "plugin.targetFrameworks=spring, kafka\n"
                        + "plugin.minimumAgentVersion=1.0.0\n"
                        + "plugin.apiVersion=1.0.0\n"
                        + "plugin.sha256=abc\n")
                    .getBytes(StandardCharsets.UTF_8)));
    assertEquals("demo", metadata.getName());
    assertEquals("1.2.3", metadata.getVersion());
    assertEquals("Demo", metadata.getDescription());
    assertEquals("Team", metadata.getAuthor());
    assertEquals(java.util.List.of("jdbc", "redis"), metadata.getDepends());
    assertEquals(java.util.List.of("storage", "audit"), metadata.getRequires());
    assertEquals(
        java.util.List.of("com.example.clock", "com.example.clock.Clock"), metadata.getExports());
    assertFalse(metadata.isExportedClass(0), "a package was recorded as a single class");
    assertTrue(metadata.isExportedClass(1), "the class marker was dropped");
    assertEquals(java.util.List.of("spring", "kafka"), metadata.getTargetFrameworks());
    assertEquals("1.0.0", metadata.getMinimumAgentVersion());
    assertEquals("1.0.0", metadata.getApiVersion());
    assertEquals("abc", metadata.getExpectedSha256());
    assertTrue(metadata.toString().contains("demo"));
    assertThrows(UnsupportedOperationException.class, () -> metadata.getDepends().add("x"));

    // The jar metadata describes the jar. Judging a plugin that lives in it means the same metadata
    // under that plugin's own name, with everything else untouched.
    PluginMetadata renamed = metadata.withName("other");
    assertEquals("other", renamed.getName());
    assertEquals("1.2.3", renamed.getVersion());
    assertEquals(java.util.List.of("jdbc", "redis"), renamed.getDepends());
    assertEquals(java.util.List.of("storage", "audit"), renamed.getRequires());
    assertEquals(
        java.util.List.of("com.example.clock", "com.example.clock.Clock"), renamed.getExports());
    assertTrue(renamed.isExportedClass(1), "renaming forgot which export names a class");
    assertEquals(java.util.List.of("spring", "kafka"), renamed.getTargetFrameworks());
    assertEquals("abc", renamed.getExpectedSha256());
    assertEquals("demo", metadata.getName(), "renaming returned a view of the original");
  }

  @Test
  void defaultsAndBuilderAreUsable() throws Exception {
    PluginMetadata defaults =
        PluginMetadata.fromProperties(new ByteArrayInputStream(new byte[0]));
    assertEquals("unknown", defaults.getName());
    assertEquals("0.0.0", defaults.getVersion());
    assertEquals("", defaults.getDescription());
    assertTrue(defaults.getDepends().isEmpty());
    assertTrue(defaults.getRequires().isEmpty());
    assertTrue(defaults.getExports().isEmpty());
    assertNull(defaults.getApiVersion());

    PluginMetadata built =
        PluginMetadata.builder()
            .name("n")
            .version("v")
            .description("d")
            .author("a")
            .depend("one")
            .require("clock")
            .export("com.example.clock")
            .targetFramework("java")
            .minimumAgentVersion("m")
            .apiVersion("1.0.0")
            .expectedSha256("s")
            .build();
    assertEquals("n", built.getName());
    assertEquals("1.0.0", built.getApiVersion());
    assertEquals(java.util.List.of("one"), built.getDepends());
    assertEquals(java.util.List.of("clock"), built.getRequires());
    assertEquals(java.util.List.of("com.example.clock"), built.getExports());
    assertEquals(java.util.List.of("java"), built.getTargetFrameworks());
  }
}
