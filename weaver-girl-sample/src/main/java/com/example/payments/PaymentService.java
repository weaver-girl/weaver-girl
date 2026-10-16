package com.example.payments;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A small payment service that knows nothing about weaver-girl.
 *
 * <p>It is the stand-in for an application a user already has. The audit plugin instruments it from
 * the outside, so this class carries no annotations, no agent calls and no logging of its own.
 */
public class PaymentService {

  private final List<Payment> payments = new ArrayList<>();

  /** Charges a card. A non-positive amount is rejected. The card number is sensitive. */
  public Payment charge(String cardNumber, long amountCents) {
    if (amountCents <= 0) {
      throw new IllegalArgumentException("amount must be positive, got " + amountCents);
    }
    Payment payment = new Payment(payments.size() + 1, cardNumber, amountCents, "CHARGED");
    payments.add(payment);
    return payment;
  }

  /** Refunds a payment that was charged earlier. */
  public Payment refund(int paymentId) {
    for (Payment payment : payments) {
      if (payment.id == paymentId) {
        payment.status = "REFUNDED";
        return payment;
      }
    }
    throw new IllegalArgumentException("no such payment: " + paymentId);
  }

  /** Everything charged so far, oldest first. */
  public List<Payment> history() {
    return Collections.unmodifiableList(payments);
  }

  /** One charge. The card number is what the audit plugin has to keep out of its report. */
  public static final class Payment {
    public final int id;
    public final String cardNumber;
    public final long amountCents;
    public String status;

    Payment(int id, String cardNumber, long amountCents, String status) {
      this.id = id;
      this.cardNumber = cardNumber;
      this.amountCents = amountCents;
      this.status = status;
    }

    @Override
    public String toString() {
      return "Payment{id=" + id + ", card=" + cardNumber + ", amount=" + amountCents + ", status="
          + status + "}";
    }
  }
}
