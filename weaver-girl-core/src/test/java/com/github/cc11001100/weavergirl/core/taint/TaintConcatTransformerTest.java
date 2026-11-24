package com.github.cc11001100.weavergirl.core.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.taint.Taint;
import com.github.cc11001100.weavergirl.api.taint.TaintRange;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Compiles a probe class with the current JDK (which lowers {@code +} to {@code invokedynamic}),
 * runs it through the transformer and executes the rewritten class. This is the end-to-end proof
 * that the call site rewrite verifies and keeps taint.
 */
class TaintConcatTransformerTest {

  private static final String PROBE_SOURCE =
      "public final class ConcatProbe {\n"
          + "  public static String sql(String user) {\n"
          + "    return \"SELECT * FROM t WHERE name='\" + user + \"'\";\n"
          + "  }\n"
          + "  public static String withPrimitive(int id, String name) {\n"
          + "    return \"id=\" + id + \"&name=\" + name;\n"
          + "  }\n"
          + "  public static String plain(String a, String b) {\n"
          + "    return a + b;\n"
          + "  }\n"
          + "}\n";

  private Path tempDir;

  @BeforeEach
  void setUp() throws IOException {
    tempDir = Files.createTempDirectory("taint-concat-probe");
    Taint.openScope();
  }

  @AfterEach
  void tearDown() throws IOException {
    Taint.closeScope();
    Files.walk(tempDir)
        .sorted(java.util.Comparator.reverseOrder())
        .forEach(path -> path.toFile().delete());
  }

  @Test
  void taintSurvivesStringConcatenation() throws Exception {
    Class<?> probe = loadRewrittenProbe();

    String user = new String("' OR 1=1 --");
    Taint.tag(user, "http.parameter:user");
    Object built = probe.getMethod("sql", String.class).invoke(null, user);

    String result = (String) built;
    assertEquals("SELECT * FROM t WHERE name='" + user + "'", result);
    List<TaintRange> ranges = Taint.ranges(result);
    assertEquals(1, ranges.size(), "the argument must stay tainted inside the concat result");
    int prefix = "SELECT * FROM t WHERE name='".length();
    assertEquals(prefix, ranges.get(0).getStart());
    assertEquals(prefix + user.length(), ranges.get(0).getEnd());
    assertEquals("http.parameter:user", ranges.get(0).getSource());
  }

  @Test
  void primitiveArgumentsAreBoxedWithoutBreakingTheResult() throws Exception {
    Class<?> probe = loadRewrittenProbe();

    String name = new String("bob");
    Taint.tag(name, "http.parameter:name");
    Object built = probe.getMethod("withPrimitive", int.class, String.class).invoke(null, 7, name);

    String result = (String) built;
    assertEquals("id=7&name=bob", result);
    List<TaintRange> ranges = Taint.ranges(result);
    assertEquals(1, ranges.size(), "only the tainted argument contributes a range");
    assertEquals(result.indexOf("bob"), ranges.get(0).getStart());
    assertEquals(result.indexOf("bob") + name.length(), ranges.get(0).getEnd());
  }

  @Test
  void cleanConcatenationStaysClean() throws Exception {
    Class<?> probe = loadRewrittenProbe();

    String left = new String("a");
    String right = new String("b");
    Object built = probe.getMethod("plain", String.class, String.class).invoke(null, left, right);

    assertEquals("ab", built);
    assertFalse(Taint.isTainted(built));
  }

  @Test
  void transformIsIdempotent() throws Exception {
    byte[] original = compileProbe();
    byte[] rewritten =
        TaintConcatTransformer.INSTANCE.transform(
            new ProbeLoader(), "ConcatProbe", null, null, original);

    assertNotNull(rewritten, "the first pass rewrites the class");
    assertNull(
        TaintConcatTransformer.INSTANCE.transform(
            new ProbeLoader(), "ConcatProbe", null, null, rewritten),
        "a second pass must leave the rewritten class alone");
  }

  @Test
  void bootstrapAndAgentClassesAreSkipped() throws Exception {
    byte[] original = compileProbe();

    assertNull(
        TaintConcatTransformer.INSTANCE.transform(null, "ConcatProbe", null, null, original),
        "the bootstrap loader cannot resolve the helper reliably");
    assertNull(
        TaintConcatTransformer.INSTANCE.transform(
            new ProbeLoader(),
            "com/github/cc11001100/weavergirl/Probe",
            null,
            null,
            original),
        "agent classes are never rewritten");
    assertNull(
        TaintConcatTransformer.INSTANCE.transform(
            new ProbeLoader(), "jdk/proxy1/$Proxy0", null, null, original),
        "JDK-generated classes are never rewritten");
  }

  @Test
  void classWithoutConcatIsReturnedUntouched() throws Exception {
    byte[] plain = compile("class NoConcat { static String id(String s) { return s; } }", "NoConcat");

    assertNull(
        TaintConcatTransformer.INSTANCE.transform(
            new ProbeLoader(), "NoConcat", null, null, plain));
  }

  @Test
  void classMentioningConcatWithoutACallSiteIsReturnedUntouched() throws Exception {
    byte[] mention =
        compile(
            "class Mention { static final String NAME = \"makeConcatWithConstants\"; }",
            "Mention");

    assertNull(
        TaintConcatTransformer.INSTANCE.transform(
            new ProbeLoader(), "Mention", null, null, mention),
        "the byte scan hit must not cause a needless rewrite");
  }

  private Class<?> loadRewrittenProbe() throws Exception {
    byte[] rewritten =
        TaintConcatTransformer.INSTANCE.transform(
            new ProbeLoader(), "ConcatProbe", null, null, compileProbe());
    assertNotNull(rewritten, "the probe must be rewritten");
    return new ProbeLoader().define("ConcatProbe", rewritten);
  }

  private byte[] compileProbe() throws Exception {
    return compile(PROBE_SOURCE, "ConcatProbe");
  }

  private byte[] compile(String source, String className) throws Exception {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    assertNotNull(compiler, "tests run on a JDK");
    StringWriter diagnostics = new StringWriter();
    StandardJavaFileManager fileManager =
        compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8);
    try {
      JavaCompiler.CompilationTask task =
          compiler.getTask(
              diagnostics,
              fileManager,
              null,
              Arrays.asList("-d", tempDir.toString(), "-classpath",
                  System.getProperty("java.class.path")),
              null,
              Arrays.asList(new SourceFile(className, source)));
      assertTrue(task.call(), "probe compilation failed: " + diagnostics);
    } finally {
      fileManager.close();
    }
    return Files.readAllBytes(tempDir.resolve(className + ".class"));
  }

  /** In-memory source so the test needs no source files on disk. */
  private static final class SourceFile extends SimpleJavaFileObject {
    private final String source;

    SourceFile(String className, String source) {
      super(URI.create("string:///" + className + ".java"), Kind.SOURCE);
      this.source = source;
    }

    @Override
    public CharSequence getCharContent(boolean ignoreEncodingErrors) {
      return source;
    }
  }

  /** Defines rewritten bytes with the test's class loader as parent, so Taint resolves. */
  private static final class ProbeLoader extends ClassLoader {
    ProbeLoader() {
      super(TaintConcatTransformerTest.class.getClassLoader());
    }

    Class<?> define(String name, byte[] bytes) {
      return defineClass(name, bytes, 0, bytes.length);
    }
  }
}
