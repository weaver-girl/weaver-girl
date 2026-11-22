package com.github.cc11001100.weavergirl.sample;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.plugin.WeaverPlugin;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The field/catch, introduction and call-site examples must keep existing and registering.
 *
 * <p>Each demo's value is the hole it fills in the sample's coverage — a join point no method
 * interceptor can express. This pins them so one cannot be deleted while the demo script that
 * proves it stays.
 */
class JoinPointExamplesTest {

  private static InterceptorRegistry recording(List<InterceptorDefinition> into) {
    return new InterceptorRegistry() {
      @Override
      public void register(InterceptorDefinition definition) {
        into.add(definition);
      }

      @Override
      public boolean unregister(String name) {
        return true;
      }

      @Override
      public List<InterceptorDefinition> getInterceptorsForClass(String className) {
        return java.util.Collections.emptyList();
      }

      @Override
      public List<InterceptorDefinition> getAllDefinitions() {
        return java.util.Collections.unmodifiableList(into);
      }
    };
  }

  @Test
  void accountGuardRegistersAFieldReadAFieldWriteAndACatchHook() throws Exception {
    WeaverPlugin plugin =
        (WeaverPlugin)
            Class.forName("com.github.cc11001100.weavergirl.sample.account.AccountGuardPlugin")
                .getDeclaredConstructor()
                .newInstance();
    List<InterceptorDefinition> definitions = new ArrayList<InterceptorDefinition>();
    plugin.registerInterceptors(recording(definitions));

    boolean read = false;
    boolean write = false;
    boolean catcher = false;
    for (InterceptorDefinition definition : definitions) {
      Interceptor interceptor = definition.getInterceptor();
      if (interceptor instanceof FieldInterceptor) {
        // A field interceptor carries its pointcuts; the two callbacks are separate definitions.
        read |= "account-password-read".equals(definition.getName());
        write |= "account-password-write".equals(definition.getName());
      }
      if (definition.getAdviceMode()
          == InterceptorDefinition.AdviceMode.CATCH) {
        catcher = true;
      }
    }
    assertTrue(read, "the field read hook is missing");
    assertTrue(write, "the field write hook is missing");
    assertTrue(catcher, "the catch hook is missing");
  }

  @Test
  void callsitePluginAsksForCallSiteTracking() throws Exception {
    WeaverPlugin plugin =
        (WeaverPlugin)
            Class.forName("com.github.cc11001100.weavergirl.sample.search.CallsiteTracePlugin")
                .getDeclaredConstructor()
                .newInstance();
    List<InterceptorDefinition> definitions = new ArrayList<InterceptorDefinition>();
    plugin.registerInterceptors(recording(definitions));

    assertEquals(1, definitions.size());
    assertTrue(
        definitions.get(0).isCallSiteTracking(),
        "without call-site tracking the caller fields are null and the plugin records nothing");
  }

  @Test
  void csvExportPluginIsDiscoverableAndCarriesNoInterceptorDefinitions() throws Exception {
    WeaverPlugin plugin =
        (WeaverPlugin)
            Class.forName("com.github.cc11001100.weavergirl.sample.billing.CsvExportPlugin")
                .getDeclaredConstructor()
                .newInstance();
    assertEquals("csv-export", plugin.name());
    List<InterceptorDefinition> definitions = new ArrayList<InterceptorDefinition>();
    plugin.registerInterceptors(recording(definitions));
    assertTrue(
        definitions.isEmpty(),
        "an introduction is not an interceptor, so nothing goes into the interceptor registry");
  }

  /**
   * An invoice that already implements the contract, so the delegate can be exercised without the
   * runtime introduction. The real demo reaches the same delegate through an Invoice the agent
   * shaped.
   */
  static final class CsvInvoice extends com.example.billing.Invoice
      implements com.example.billing.InvoiceCsv {
    CsvInvoice(String customer, long cents) {
      super(customer, cents);
    }

    @Override
    public String toCsvRow() {
      return getCustomer() + "," + getCents();
    }
  }

  @Test
  void csvDelegateReadsTheInvoiceItIsHanded() throws Exception {
    Class<?> delegateClass =
        Class.forName("com.github.cc11001100.weavergirl.sample.billing.CsvExportPlugin$CsvDelegate");
    Object delegate = delegateClass.getDeclaredConstructor().newInstance();
    Object invoice = new CsvInvoice("alice", 2599L);

    java.lang.reflect.Method method =
        delegateClass.getMethod("toCsvRow", com.example.billing.InvoiceCsv.class);
    assertEquals("alice,2599", method.invoke(delegate, invoice));
  }

  @Test
  void theIntroduceContractIsVisibleToTheApplication() {
    // The interface stays with the application so both sides see one Class; a plugin-defined one
    // would be a different type under its loader and instanceof would answer false.
    assertTrue(
        com.example.billing.InvoiceCsv.class.isInterface(),
        "InvoiceCsv must live on the application class path");
    assertNotNull(com.example.billing.InvoiceCsv.class.getClassLoader());
    assertFalse(
        com.example.billing.InvoiceCsv.class.isAssignableFrom(com.example.billing.Invoice.class),
        "the invoice must not implement it until the introduction is installed");
  }
}
