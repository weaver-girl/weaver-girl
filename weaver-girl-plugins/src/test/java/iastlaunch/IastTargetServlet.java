package iastlaunch;

import java.io.IOException;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;

/** Application servlet whose {@code service} method is instrumented by the servlet plugin. */
public class IastTargetServlet extends HttpServlet {

  public static volatile String lastDerived;

  @Override
  public void service(ServletRequest req, ServletResponse res) throws ServletException, IOException {
    HttpServletRequest http = (HttpServletRequest) req;
    String value = http.getParameter("cmd");
    String derived = "cmd:".concat(value);
    lastDerived = derived;
    // Advice on ProcessBuilder.start observes the derived argument and, when suppressed, does not
    // spawn a process.
    new ProcessBuilder(derived).start();
  }
}
