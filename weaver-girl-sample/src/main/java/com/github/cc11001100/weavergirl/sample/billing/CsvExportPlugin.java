package com.github.cc11001100.weavergirl.sample.billing;

import com.example.billing.Invoice;
import com.example.billing.InvoiceCsv;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionDefinition;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistrar;
import com.github.cc11001100.weavergirl.api.introduction.IntroductionRegistrarHolder;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.plugin.AbstractPlugin;
import com.github.cc11001100.weavergirl.api.plugin.PluginContext;
import com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry;

/**
 * Adds {@link InvoiceCsv} to {@link Invoice}, so an invoice can render itself as a CSV row.
 *
 * <p>This is an <em>introduction</em> (a mixin). The plugin does not intercept a method the class
 * already has — it makes the class implement an interface it never declared, and answers that
 * interface's methods. No interceptor definition can do that: the interface list is part of the
 * class, not of a method body.
 *
 * <p>The plugin is loaded only because its jar sits in {@code plugins/}. {@code com.example.billing}
 * never references it; the demo asks the object whether it happens to be an {@code InvoiceCsv}.
 *
 * <p>The interface is declared with the application, not with the plugin, because a plugin runs in
 * its own class loader: an interface defined in the plugin would be a different {@code Class} from
 * the one the application compiles against, and {@code instanceof} would answer false. The plugin
 * contributes only what the interface cannot: the implementation.
 */
public class CsvExportPlugin extends AbstractPlugin {

  static final String TARGET = "com.example.billing.Invoice";

  @Override
  public String name() {
    return "csv-export";
  }

  @Override
  public void init(PluginContext context) {
    registerIntroduction(context);
  }

  private void registerIntroduction(PluginContext context) {
    IntroductionRegistrar registrar = IntroductionRegistrarHolder.get();
    if (registrar == null) {
      // No agent published a registrar: the plugin is running outside the engine (a unit test that
      // constructs it directly), so there is nothing to install into.
      context.getLogger().info("no introduction registrar published; csv-export inactive");
      return;
    }
    registrar.register(
        new IntroductionDefinition(
            "invoice-csv", ClassMatcher.byName(TARGET), InvoiceCsv.class, new CsvDelegate()));
    context.getLogger().info("csv-export plugin initialised");
  }

  @Override
  public void registerInterceptors(InterceptorRegistry registry) {
    // An introduction is not an interceptor: nothing goes into the interceptor registry.
  }

  /**
   * What the added interface answers.
   *
   * <p>The first parameter is the invoice the call was made on — a delegate that only implements
   * the interface cannot see it, so it could answer with constants only. Declaring the target as an
   * extra parameter is how a delegate reads the object it is answering for.
   */
  public static final class CsvDelegate {

    /** Called for {@link InvoiceCsv#toCsvRow()} on any invoice the introduction reached. */
    public String toCsvRow(InvoiceCsv invoice) {
      Invoice source = (Invoice) invoice;
      return source.getCustomer() + "," + source.getCents();
    }
  }
}
