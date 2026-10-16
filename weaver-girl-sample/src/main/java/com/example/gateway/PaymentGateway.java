package com.example.gateway;

/**
 * Forwards every charge straight to the provider.
 *
 * <p>This is the stand-in for an application a user already has. It does not know about weaver-girl
 * and it does not protect itself: a provider that is down fails the charge, and every charge reaches
 * the provider no matter how many just failed.
 */
public class PaymentGateway {

  private final Upstream upstream;

  public PaymentGateway(Upstream upstream) {
    this.upstream = upstream;
  }

  /** Charges a card by asking the provider. Whatever the provider does comes straight back. */
  public String charge(long cents) {
    return upstream.authorize(cents);
  }
}
