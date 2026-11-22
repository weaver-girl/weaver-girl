package com.example.search;

/**
 * A search service that builds a SQL query from whatever text it is given.
 *
 * <p>The {@code q} value comes from a request. The service concatenates it into the query and runs
 * it, which is exactly the injection path an IAST tool is meant to find. Nothing here knows about
 * weaver-girl, and nothing here sanitises the input.
 */
public class SearchService {

  private final Ledger ledger = new Ledger();

  /**
   * Searches for {@code q}.
   *
   * @param q the raw search text
   * @return the query that was run
   */
  public String search(String q) {
    String sql = buildQuery(q);
    ledger.run(sql);
    return sql;
  }

  /** Concatenation is the propagation point: the tainted {@code q} flows into {@code sql}. */
  private String buildQuery(String q) {
    return "SELECT * FROM orders WHERE email = '".concat(q).concat("'");
  }

  /** The sink: the query reaches something that executes SQL. */
  public static class Ledger {
    public void run(String sql) {
      System.out.println("RESULT ledger ran: " + sql);
    }
  }
}
