package com.github.cc11001100.weavergirl.api.taint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Offsets and fallbacks of the runtime half of the indy concatenation hook. */
class TaintConcatTest {

  @BeforeEach
  void openScope() {
    Taint.openScope();
  }

  @AfterEach
  void closeScope() {
    Taint.closeScope();
  }

  @Test
  void resultRangeFollowsTheArgumentPosition() {
    String user = new String("' OR 1=1 --");
    Taint.tag(user, "http.parameter:user");
    String prefix = "WHERE name='";
    String suffix = "' AND role='admin'";
    String template = prefix + '\u0001' + suffix;
    String result = prefix + user + suffix;

    String returned = TaintConcat.propagate(result, template, new Object[] {user});

    assertSame(result, returned, "the rewrite must not replace the concat result");
    List<TaintRange> ranges = Taint.ranges(result);
    assertEquals(1, ranges.size());
    assertEquals(prefix.length(), ranges.get(0).getStart());
    assertEquals(prefix.length() + user.length(), ranges.get(0).getEnd());
    assertEquals("http.parameter:user", ranges.get(0).getSource());
  }

  @Test
  void severalArgumentsKeepTheirOwnOffsets() {
    String user = new String("bob");
    String password = new String("secret");
    Taint.tag(user, "http.parameter:user");
    String result = "u=bob&p=secret&x=1";
    String template = "u=\u0001&p=\u0001&x=1";

    TaintConcat.propagate(result, template, new Object[] {user, password});

    List<TaintRange> ranges = Taint.ranges(result);
    assertEquals(1, ranges.size());
    assertEquals(2, ranges.get(0).getStart());
    assertEquals(5, ranges.get(0).getEnd());
  }

  @Test
  void nonCharSequenceMarkCoversTheWholeRenderedArgument() {
    Integer count = Integer.valueOf(42);
    Taint.tag(count, "http.parameter:count");
    String result = "n=42";
    String template = "n=\u0001";

    TaintConcat.propagate(result, template, new Object[] {count});

    List<TaintRange> ranges = Taint.ranges(result);
    assertEquals(1, ranges.size());
    assertEquals(2, ranges.get(0).getStart());
    assertEquals(4, ranges.get(0).getEnd());
  }

  @Test
  void recipeConstantsDoNotShiftOffsets() {
    String user = new String("x");
    Taint.tag(user, "http.parameter:user");
    String result = "SELECT * FROM t WHERE n = 'x'";
    String template = "SELECT * FROM t WHERE n = '\u0001'";

    TaintConcat.propagate(result, template, new Object[] {user});

    assertEquals(27, Taint.ranges(result).get(0).getStart());
    assertEquals(28, Taint.ranges(result).get(0).getEnd());
  }

  @Test
  void cleanArgumentsLeaveTheResultUnmarked() {
    String user = new String("bob");
    String result = "u=bob";

    TaintConcat.propagate(result, "u=\u0001", new Object[] {user});

    assertFalse(Taint.isTainted(result));
  }

  @Test
  void noScopeIsANoOp() {
    Taint.closeScope();
    String user = new String("bob");
    TaintBridge.tag(user, "http.parameter:user");
    String result = "u=bob";

    assertSame(result, TaintConcat.propagate(result, "u=\u0001", new Object[] {user}));
    assertTrue(Taint.ranges(result).isEmpty());
    Taint.openScope();
  }

  @Test
  void malformedRecipeFallsBackToTheWholeResult() {
    String user = new String("bob");
    Taint.tag(user, "http.parameter:user");
    String result = "u=bob";

    TaintConcat.propagate(result, "u=\u0001&\u0001", new Object[] {user});

    List<TaintRange> ranges = Taint.ranges(result);
    assertEquals(1, ranges.size());
    assertEquals(0, ranges.get(0).getStart());
    assertEquals(result.length(), ranges.get(0).getEnd());
  }

  @Test
  void nullResultIsReturnedAsIs() {
    assertNull(TaintConcat.propagate(null, "\u0001", new Object[] {"x"}));
  }
}
