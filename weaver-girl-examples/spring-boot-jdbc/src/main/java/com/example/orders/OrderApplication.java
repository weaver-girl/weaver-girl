package com.example.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A small orders service: one REST controller over one JDBC table.
 *
 * <p>Nothing in this application imports a weaver-girl class. The agent is attached with {@code
 * -javaagent} and instruments Spring MVC, the servlet container and JDBC by class name. That is the
 * point of the example: the application is an ordinary Spring Boot app, and the only thing that
 * changes between "instrumented" and "not instrumented" is the JVM flags in {@code run.sh}.
 */
@SpringBootApplication
public class OrderApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderApplication.class, args);
  }
}
