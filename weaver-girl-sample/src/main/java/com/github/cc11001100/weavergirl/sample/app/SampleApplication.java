package com.github.cc11001100.weavergirl.sample.app;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.*;

/**
 * Sample application demonstrating weaver-girl agent with real frameworks.
 *
 * <p>Runs an embedded Jetty server with an H2 database, so the built-in
 * Servlet and JDBC plugins actually intercept real requests and queries.</p>
 *
 * <p>Usage:</p>
 * <pre>
 * # Build the agent JAR first
 * mvn package -DskipTests -pl weaver-girl-agent
 *
 * # Run with the agent
 * java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar \
 *      -jar weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar
 *
 * # Run with JSON event output
 * java -javaagent:weaver-girl-agent/target/weaver-girl-agent-1.0.0-SNAPSHOT.jar=jsonEvents=true \
 *      -jar weaver-girl-sample/target/weaver-girl-sample-1.0.0-SNAPSHOT.jar
 * </pre>
 */
public class SampleApplication {

    private static final int PORT = 8080;
    private static final String DB_URL = "jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1";

    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║     Weaver-Girl Sample Application                  ║");
        System.out.println("║     Embedded Jetty + H2 Database Demo               ║");
        System.out.println("╚══════════════════════════════════════════════════════╝\n");

        // Initialize H2 database
        initDatabase();
        System.out.println("[DB] H2 database initialized with 'users' table\n");

        // Start Jetty server
        Server server = new Server(PORT);
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new UserServlet()), "/users");
        context.addServlet(new ServletHolder(new HealthServlet()), "/health");
        server.setHandler(context);

        server.start();
        System.out.println("[Jetty] Server started on http://localhost:" + PORT);
        System.out.println();
        System.out.println("Available endpoints:");
        System.out.println("  GET  /health           - Health check");
        System.out.println("  GET  /users            - List all users");
        System.out.println("  GET  /users?name=X     - Add a user named X");
        System.out.println("  GET  /users?slow=true  - Trigger a slow query");
        System.out.println();
        System.out.println("Try: curl http://localhost:8080/users?name=Alice");
        System.out.println("Try: curl http://localhost:8080/users?name=Bob");
        System.out.println("Try: curl http://localhost:8080/users         (list all)");
        System.out.println("Try: curl http://localhost:8080/users?slow=true (slow query)");
        System.out.println();

        // Auto-demo: send some requests to show the agent working
        if (args.length == 0 || !args[0].equals("--no-demo")) {
            autoDemo();
        }

        server.join();
    }

    private static void initDatabase() throws SQLException {
        try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY, " +
                    "name VARCHAR(100) NOT NULL, " +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /**
     * Auto-demo: insert some users and query them so the agent
     * intercepts real JDBC and Servlet traffic without manual curl.
     */
    private static void autoDemo() {
        System.out.println("=== Auto Demo (agent should intercept these) ===\n");
        try {
            // Insert users via JDBC (JDBC plugin should intercept)
            System.out.println("[Demo] Inserting users via JDBC...");
            try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name) VALUES (?)")) {
                for (String name : new String[]{"Alice", "Bob", "Charlie"}) {
                    ps.setString(1, name);
                    ps.executeUpdate();
                    System.out.println("  Inserted: " + name);
                }
            }

            // Query users (JDBC plugin should intercept)
            System.out.println("\n[Demo] Querying users via JDBC...");
            try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY id")) {
                while (rs.next()) {
                    System.out.println("  User: id=" + rs.getInt("id") + " name=" + rs.getString("name"));
                }
            }

            // Slow query (should trigger SLOW-QUERY warning if threshold < 500ms)
            System.out.println("\n[Demo] Executing slow query...");
            try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
                 Statement stmt = conn.createStatement()) {
                stmt.execute("SELECT COUNT(*) FROM users u1, users u2, users u3, users u4, users u5");
                System.out.println("  Slow query completed");
            }

            System.out.println("\n=== Auto Demo Complete ===");
            System.out.println("Check the log output above for agent interception messages.");
            System.out.println("Look for: [JDBC], [SLOW-QUERY], [SERVLET] prefixes\n");

        } catch (SQLException e) {
            System.err.println("Demo failed: " + e.getMessage());
        }
    }

    /**
     * Servlet that handles user CRUD operations.
     * The Servlet plugin should intercept service() calls.
     */
    static class UserServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            try {
                String name = req.getParameter("name");
                boolean slow = "true".equals(req.getParameter("slow"));

                if (slow) {
                    // Deliberately slow operation to trigger SLOW-SERVLET
                    Thread.sleep(6000);
                    resp.getWriter().write("{\"status\":\"slow\",\"message\":\"6 second delay\"}");
                    return;
                }

                if (name != null && !name.isEmpty()) {
                    // Insert user via JDBC
                    try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
                         PreparedStatement ps = conn.prepareStatement("INSERT INTO users (name) VALUES (?)")) {
                        ps.setString(1, name);
                        ps.executeUpdate();
                    }
                    resp.getWriter().write("{\"action\":\"inserted\",\"name\":\"" + name + "\"}");
                } else {
                    // List all users
                    StringBuilder json = new StringBuilder("{\"users\":[");
                    try (Connection conn = DriverManager.getConnection(DB_URL, "sa", "");
                         Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery("SELECT * FROM users ORDER BY id")) {
                        boolean first = true;
                        while (rs.next()) {
                            if (!first) json.append(",");
                            json.append("{\"id\":").append(rs.getInt("id"))
                                .append(",\"name\":\"").append(rs.getString("name")).append("\"}");
                            first = false;
                        }
                    }
                    json.append("]}");
                    resp.getWriter().write(json.toString());
                }
            } catch (Exception e) {
                try {
                    resp.setStatus(500);
                    resp.getWriter().write("{\"error\":\"" + e.getMessage() + "\"}");
                } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Simple health check servlet.
     */
    static class HealthServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
            try {
                resp.setContentType("application/json");
                resp.getWriter().write("{\"status\":\"UP\",\"agent\":\"weaver-girl\"}");
            } catch (Exception ignored) {}
        }
    }
}