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
                        + "plugin.targetFrameworks=spring, kafka\n"
                        + "plugin.minimumAgentVersion=1.0.0\n"
                        + "plugin.sha256=abc\n")
                    .getBytes(StandardCharsets.UTF_8)));
    assertEquals("demo", metadata.getName());
    assertEquals("1.2.3", metadata.getVersion());
    assertEquals("Demo", metadata.getDescription());
    assertEquals("Team", metadata.getAuthor());
    assertEquals(java.util.List.of("jdbc", "redis"), metadata.getDepends());
    assertEquals(java.util.List.of("spring", "kafka"), metadata.getTargetFrameworks());
    assertEquals("1.0.0", metadata.getMinimumAgentVersion());
    assertEquals("abc", metadata.getExpectedSha256());
    assertTrue(metadata.toString().contains("demo"));
    assertThrows(UnsupportedOperationException.class, () -> metadata.getDepends().add("x"));
  }

  @Test
  void defaultsAndBuilderAreUsable() throws Exception {
    PluginMetadata defaults =
        PluginMetadata.fromProperties(new ByteArrayInputStream(new byte[0]));
    assertEquals("unknown", defaults.getName());
    assertEquals("0.0.0", defaults.getVersion());
    assertEquals("", defaults.getDescription());
    assertTrue(defaults.getDepends().isEmpty());

    PluginMetadata built =
        PluginMetadata.builder()
            .name("n")
            .version("v")
            .description("d")
            .author("a")
            .depend("one")
            .targetFramework("java")
            .minimumAgentVersion("m")
            .expectedSha256("s")
            .build();
    assertEquals("n", built.getName());
    assertEquals(java.util.List.of("one"), built.getDepends());
    assertEquals(java.util.List.of("java"), built.getTargetFrameworks());
  }
}
