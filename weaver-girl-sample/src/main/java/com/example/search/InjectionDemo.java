package com.example.search;

import com.github.cc11001100.weavergirl.api.event.InterceptorEvent;
import com.github.cc11001100.weavergirl.api.event.InterceptorEventPublisher;
import com.github.cc11001100.weavergirl.api.taint.Taint;

/**
 * Sends an injected search term through the service, with taint tracking on.
 *
 * <p>The value is marked as coming from an HTTP parameter, and the service concatenates it into a
 * query. Under the agent with {@code taint=true} the concatenation carries the taint forward and
 * the SQL sink reports exactly which slice of the query came from the request. Without the agent
 * nothing marks or observes anything.
 *
 * <pre>
 * java -javaagent:weaver-girl-agent.jar=taint=true -cp weaver-girl-sample.jar \
 *      com.example.search.InjectionDemo
 * </pre>
 *
 * <p>Exit code: 0 when a finding names the parameter as the source of the query, 2 when none was
 * produced.
 */
public final class InjectionDemo {

  private static final String EVENT_TYPE = "iast-finding";

  private InjectionDemo() {}

  public static void main(String[] args) {
    System.out.println("--- searching with an injected term ---");

    Taint.openScope();
    String sql;
    try {
      // A real app gets this from request.getParameter("q"). Marking it here is the same thing the
      // servlet plugin's capture does for a servlet request.
      String q = "x' OR '1'='1";
      Taint.tag(q, Taint.PARAMETER_PREFIX + "q");
      System.out.println("tainted input: " + Taint.isTainted(q));

      sql = new SearchService().search(q);
      System.out.println("tainted query: " + Taint.isTainted(sql));

      // The SQL sink: the JDBC plugin calls this for every execute, so a real query is observed the
      // same way. Calling it directly keeps this demo to one class.
      com.github.cc11001100.weavergirl.api.taint.SqlExecutionSink.observe(sql);
    } finally {
      Taint.closeScope();
    }

    int findings = 0;
    String source = null;
    String sink = null;
    for (InterceptorEvent event : InterceptorEventPublisher.getInstance().getHistory()) {
      if (!EVENT_TYPE.equals(event.getType())) {
        continue;
      }
      findings++;
      if (source == null) {
        source = event.getAttributes().get("source");
        sink = event.getAttributes().get("sink");
      }
    }

    System.out.println();
    System.out.println("what the IAST hooks found:");
    System.out.println("  findings : " + findings);
    System.out.println("  source   : " + source);
    System.out.println("  sink     : " + sink);

    boolean identified = findings > 0 && (Taint.PARAMETER_PREFIX + "q").equals(source);
    System.out.println();
    if (identified) {
      System.out.println(
          "iast active: the finding names the request parameter as the source of the SQL");
      System.exit(0);
    }
    System.out.println(
        "iast not active: run this under the agent with taint=true; the query above is still"
            + " built from the raw parameter");
    System.exit(2);
  }
}
