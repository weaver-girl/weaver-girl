package com.example.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A support desk that stores whatever the customer typed.
 *
 * <p>It is the stand-in for an application a user already runs. Tickets keep the raw message,
 * including card numbers and phone numbers, because that is what the customer sent. Nothing here
 * knows about weaver-girl: no annotations, no agent calls, no redaction of its own.
 */
public class TicketStore {

  private final List<Ticket> tickets = new ArrayList<>();

  /** Files a ticket. The message is stored verbatim. */
  public Ticket file(String customer, String message) {
    Ticket ticket = new Ticket(tickets.size() + 1, customer, message);
    tickets.add(ticket);
    return ticket;
  }

  /**
   * The text an agent pastes into a chat tool or a log line.
   *
   * <p>This is the leak. The store hands the raw message back, card number and all.
   */
  public String render(int ticketId) {
    return find(ticketId).message;
  }

  /** Everything filed so far, oldest first. */
  public List<Ticket> history() {
    return Collections.unmodifiableList(tickets);
  }

  private Ticket find(int ticketId) {
    for (Ticket ticket : tickets) {
      if (ticket.id == ticketId) {
        return ticket;
      }
    }
    throw new IllegalArgumentException("no such ticket: " + ticketId);
  }

  /** One customer ticket. {@code message} is untrusted text and may contain secrets. */
  public static final class Ticket {
    public final int id;
    public final String customer;
    public final String message;

    Ticket(int id, String customer, String message) {
      this.id = id;
      this.customer = customer;
      this.message = message;
    }

    @Override
    public String toString() {
      return "Ticket{id=" + id + ", customer=" + customer + ", message=" + message + "}";
    }
  }
}
