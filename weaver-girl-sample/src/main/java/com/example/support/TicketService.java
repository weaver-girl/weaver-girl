package com.example.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * A service that logs like any application would. It has no trace-aware code: it reads whatever the
 * logging backend happens to have put in the MDC.
 *
 * <p>{@link #handleTicket(int)} is the intercepted call in {@link LogCorrelationDemo}. What makes it
 * intercepted is the sample's {@code LogCorrelationPlugin}, which is loaded from the drop-in plugin
 * jar — the call site below stays clean.
 */
public class TicketService {

  private static final Logger log = LoggerFactory.getLogger(TicketService.class);

  /** The trace id this class saw in the MDC during the last {@link #handleTicket(int)} call. */
  static volatile String traceIdSeenInsideCall;

  public String handleTicket(int ticketId) {
    traceIdSeenInsideCall = MDC.get("traceId");
    log.info("handling ticket {}", ticketId);
    return "ticket-" + ticketId;
  }
}
