package com.github.cc11001100.weavergirl.api.taint;

/** SQL sink hook used by the JDBC execute interceptor. Does not open a connection. */
public final class SqlExecutionSink {

  static {
    TaintFindings.install();
  }

  private SqlExecutionSink() {}

  public static void observe(String sql) {
    TaintPropagation.observeSql(sql);
  }
}
