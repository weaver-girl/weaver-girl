package com.example.billing;

/**
 * A billing invoice with no support for being exported as CSV.
 *
 * <p>It is the stand-in for an application a team already ships: it has the fields its domain
 * needs and nothing else. Under the agent the {@code csv-export} plugin adds an interface to this
 * class — {@code getClass().getInterfaces()} starts reporting it, and the interface's method
 * answers through the plugin — without this file changing.
 */
public class Invoice {

  private final String customer;

  private final long cents;

  public Invoice(String customer, long cents) {
    this.customer = customer;
    this.cents = cents;
  }

  public String getCustomer() {
    return customer;
  }

  public long getCents() {
    return cents;
  }

  @Override
  public String toString() {
    return customer + ":" + cents;
  }
}
