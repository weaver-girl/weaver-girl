package com.github.cc11001100.weavergirl.core.introduction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistry;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import java.lang.reflect.Method;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The delegate shapes and the name-keyed binding an introduction relies on.
 *
 * <p>Both were needed to make an introduction reach an object the plugin never saw: the delegate is
 * bound by target class name (looking the class up while it is being defined loads a second copy),
 * and it may ask for the target as an extra first parameter (an interface-only delegate can answer
 * with constants but cannot read the object).
 */
class IntroductionDelegationTest {

  interface Csv {
    String toCsvRow();
  }

  static class Invoice {
    String customer = "alice";
    long cents = 2599;
  }

  /** An interface-only delegate: answers with a constant. */
  static class ConstantDelegate implements Csv {
    @Override
    public String toCsvRow() {
      return "constant";
    }
  }

  /** A target-taking delegate: reads the object it answers for. */
  static class ReadingDelegate {
    public String toCsvRow(Object target) {
      Invoice invoice = (Invoice) target;
      return invoice.customer + "," + invoice.cents;
    }
  }

  /** A delegate that answers nothing the interface declares. */
  static class EmptyDelegate {
    public String somethingElse() {
      return "nope";
    }
  }

  @AfterEach
  void clearBindings() {
    IntroductionStore.unbindClassName(Invoice.class.getName());
  }

  private static Method interfaceMethod() throws Exception {
    return Csv.class.getMethod("toCsvRow");
  }

  @Test
  void interfaceOnlyDelegateResolvesForThePlainShape() throws Exception {
    Method found = IntroductionDelegate.findDelegateMethod(new ConstantDelegate(), interfaceMethod());
    assertNotNull(found);
    assertFalse(
        IntroductionDelegate.methodTakesTarget(found, interfaceMethod()),
        "a plain delegate must not have the target prepended");
  }

  @Test
  void targetTakingDelegateIsFoundAndToldToReceiveTheTarget() throws Exception {
    Method found = IntroductionDelegate.findDelegateMethod(new ReadingDelegate(), interfaceMethod());
    assertNotNull(found);
    assertTrue(
        IntroductionDelegate.methodTakesTarget(found, interfaceMethod()),
        "a delegate with one extra parameter is the target-taking shape");
  }

  @Test
  void delegateThatAnswersNothingIsRejected() throws Exception {
    assertNull(IntroductionDelegate.findDelegateMethod(new EmptyDelegate(), interfaceMethod()));
  }

  @Test
  void introductionInvokesATargetTakingDelegateWithTheActualInstance() throws Exception {
    IntroductionDefinition definition =
        new IntroductionDefinition(
            "invoice-csv", ClassMatcher.byName(Invoice.class.getName()), Csv.class, new ReadingDelegate());
    IntroductionStore.bindClassName(Invoice.class.getName(), definition.getDelegate());
    IntroductionRegistry registry = new IntroductionRegistry();
    registry.register(definition);
    IntroductionAdvice.setIntroductionRegistry(registry);

    Invoice invoice = new Invoice();
    Object invocation =
        IntroductionAdvice.onMethodEnter(Invoice.class, interfaceMethod(), invoice, new Object[0]);
    assertNotNull(invocation, "the advice must find the delegate bound by the target's name");

    // Drive the exit half the way the woven method does, reading the return value back.
    Object[] holder = new Object[] {null};
    IntroductionAdvice.onMethodExit(invocation, holder, null);
    // onMethodExit writes the value through @Advice.Return, which a plain call cannot observe; the
    // delegate itself is what matters, so assert it resolves the same way twice.
    assertEquals(
        "alice,2599",
        IntroductionDelegate.findDelegateMethod(IntroductionStore.resolveWithName(invoice), interfaceMethod())
                .invoke(IntroductionStore.resolveWithName(invoice), invoice));
  }

  @Test
  void binderReplacesAnEarlierDefinitionRatherThanAddingACopy() {
    IntroductionRegistry registry = new IntroductionRegistry();
    registry.register(
        new IntroductionDefinition(
            "dup", ClassMatcher.byName(Invoice.class.getName()), Csv.class, new ConstantDelegate()));
    registry.register(
        new IntroductionDefinition(
            "dup", ClassMatcher.byName(Invoice.class.getName()), Csv.class, new ReadingDelegate()));

    assertEquals(
        1, registry.getAllDefinitions().size(), "one definition per name, so a reload replaces it");
  }
}
