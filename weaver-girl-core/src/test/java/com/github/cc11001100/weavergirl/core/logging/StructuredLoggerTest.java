package com.github.cc11001100.weavergirl.core.logging;

import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for StructuredLogger.
 */
class StructLoggerTest {

    private ByteArrayOutputStream captured;
    private PrintStream originalErr;

    @BeforeEach
    void setUp() {
        captured = new ByteArrayOutputStream();
        originalErr = System.err;
        // Redirect to capture output
        StructLogger.setOutputStream(new PrintStream(captured));
        StructLogger.setGlobalFormat("text");
    }

    @AfterEach
    void tearDown() {
        StructLogger.setOutputStream(originalErr);
        StructLogger.setGlobalFormat("text");
    }

    @Test
    void textFormatInfo() {
        StructLogger log = StructLogger.getLogger("TestComponent");
        log.info("Plugin loaded").with("name", "jdbc").log();

        String output = captured.toString().trim();
        assertTrue(output.contains("[weaver-girl] INFO [TestComponent] Plugin loaded"));
        assertTrue(output.contains("name=jdbc"));
    }

    @Test
    void textFormatWarn() {
        StructLogger log = StructLogger.getLogger("TestComponent");
        log.warn("Something wrong").log();

        String output = captured.toString().trim();
        assertTrue(output.contains("[weaver-girl] WARN [TestComponent] Something wrong"));
    }

    @Test
    void textFormatError() {
        StructLogger log = StructLogger.getLogger("TestComponent");
        log.error("Failed").log();

        String output = captured.toString().trim();
        assertTrue(output.contains("[weaver-girl] ERROR [TestComponent] Failed"));
    }

    @Test
    void textFormatMultipleFields() {
        StructLogger log = StructLogger.getLogger("Test");
        log.info("Test message").with("key1", "value1").with("key2", 42).log();

        String output = captured.toString().trim();
        assertTrue(output.contains("key1=value1"));
        assertTrue(output.contains("key2=42"));
    }

    @Test
    void jsonFormatOutput() {
        StructLogger.setGlobalFormat("json");
        StructLogger log = StructLogger.getLogger("TestComponent");
        log.info("Plugin loaded").with("plugin", "jdbc").with("count", 5).log();

        String output = captured.toString().trim();
        assertTrue(output.startsWith("{"));
        assertTrue(output.contains("\"level\":\"INFO\""));
        assertTrue(output.contains("\"logger\":\"TestComponent\""));
        assertTrue(output.contains("\"message\":\"Plugin loaded\""));
        assertTrue(output.contains("\"plugin\":\"jdbc\""));
        assertTrue(output.contains("\"count\":5"));
        assertTrue(output.endsWith("}"));
    }

    @Test
    void jsonFormatEscapesSpecialChars() {
        StructLogger.setGlobalFormat("json");
        StructLogger log = StructLogger.getLogger("Test");
        log.info("Message with \"quotes\" and \nnewlines").log();

        String output = captured.toString().trim();
        assertTrue(output.contains("\\\"quotes\\\""));
        assertTrue(output.contains("\\n"));
    }

    @Test
    void setGlobalFormatText() {
        StructLogger.setGlobalFormat("text");
        assertEquals(StructLogger.Format.TEXT, StructLogger.getGlobalFormat());
    }

    @Test
    void setGlobalFormatJson() {
        StructLogger.setGlobalFormat("json");
        assertEquals(StructLogger.Format.JSON, StructLogger.getGlobalFormat());
    }

    @Test
    void setGlobalFormatCaseInsensitive() {
        StructLogger.setGlobalFormat("JSON");
        assertEquals(StructLogger.Format.JSON, StructLogger.getGlobalFormat());
        StructLogger.setGlobalFormat("Text");
        assertEquals(StructLogger.Format.TEXT, StructLogger.getGlobalFormat());
    }

    @Test
    void setGlobalFormatUnknownDefaultsToText() {
        StructLogger.setGlobalFormat("unknown");
        assertEquals(StructLogger.Format.TEXT, StructLogger.getGlobalFormat());
    }
}
