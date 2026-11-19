package com.example.orders;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Two endpoints that both go through JDBC, so a request produces servlet, Spring MVC and JDBC
 * interception events:
 *
 * <ul>
 *   <li>{@code GET /orders} — a query per call
 *   <li>{@code POST /orders} — an insert per call
 * </ul>
 *
 * <p>{@code GET /orders/slow} sleeps between two queries so the slow-request and slow-query
 * thresholds have something to trip.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

  private final JdbcTemplate jdbc;

  public OrderController(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @GetMapping
  public List<Map<String, Object>> list() {
    return jdbc.queryForList("SELECT id, item, cents FROM orders ORDER BY id");
  }

  @PostMapping
  public Map<String, Object> create(@RequestBody Map<String, Object> body) {
    jdbc.update(
        "INSERT INTO orders (item, cents) VALUES (?, ?)",
        String.valueOf(body.getOrDefault("item", "unnamed")),
        Long.parseLong(String.valueOf(body.getOrDefault("cents", 0))));
    return Map.of("status", "created", "count", list().size());
  }

  @GetMapping("/{id}")
  public Map<String, Object> byId(@PathVariable long id) {
    return jdbc.queryForMap("SELECT id, item, cents FROM orders WHERE id = ?", id);
  }

  @GetMapping("/slow")
  public Map<String, Object> slow() throws InterruptedException {
    jdbc.queryForList("SELECT COUNT(*) FROM orders");
    Thread.sleep(1_500);
    jdbc.queryForList("SELECT COUNT(*) FROM orders");
    return Map.of("status", "slow");
  }
}
