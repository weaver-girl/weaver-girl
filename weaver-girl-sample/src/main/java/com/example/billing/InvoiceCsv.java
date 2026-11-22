package com.example.billing;

/**
 * The contract an introduction adds to {@link Invoice}: "this object can render itself as a CSV
 * row".
 *
 * <p>It lives with the application only because both the plugin and the demo need the same
 * {@code Class} object to implement — an interface the plugin defined in its own loader would be a
 * different type to the application. Everything else about the introduction (the delegate, the
 * target selection) stays in the plugin.
 */
public interface InvoiceCsv {

  /** One CSV row for this invoice. */
  String toCsvRow();
}
