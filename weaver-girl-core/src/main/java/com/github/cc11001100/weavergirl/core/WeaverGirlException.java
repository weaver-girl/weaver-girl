package com.github.cc11001100.weavergirl.core;

/**
 * Unified exception hierarchy for the weaver-girl agent.
 *
 * <p>Provides structured error codes so that consumers can programmatically distinguish between
 * different failure modes instead of parsing error messages.
 *
 * <h3>Error code ranges</h3>
 *
 * <table>
 *   <tr><th>Range</th><th>Category</th></tr>
 *   <tr><td>1000–1099</td><td>Bootstrap / initialization errors</td></tr>
 *   <tr><td>2000–2099</td><td>Plugin loading errors</td></tr>
 *   <tr><td>3000–3099</td><td>Transformer / bytecode errors</td></tr>
 *   <tr><td>4000–4099</td><td>Interceptor execution errors</td></tr>
 *   <tr><td>5000–5099</td><td>Configuration errors</td></tr>
 * </table>
 *
 * @since 1.0.0
 */
public class WeaverGirlException extends RuntimeException {

  private final ErrorCode errorCode;

  /** Standard error codes for the weaver-girl agent. */
  public enum ErrorCode {
    // Bootstrap errors (1000–1099)
    BOOTSTRAP_FAILED(1000, "Agent bootstrap failed"),
    INSTRUMENTATION_NULL(1001, "Instrumentation instance is null"),

    // Plugin errors (2000–2099)
    PLUGIN_LOAD_FAILED(2000, "Plugin loading failed"),
    PLUGIN_INIT_FAILED(2001, "Plugin initialization failed"),
    PLUGIN_DESTROY_FAILED(2002, "Plugin destruction failed"),

    // Transformer errors (3000–3099)
    TRANSFORM_FAILED(3000, "Bytecode transformation failed"),
    TRANSFORMER_INSTALL_FAILED(3001, "Transformer installation failed"),

    // Interceptor errors (4000–4099)
    INTERCEPTOR_BEFORE_FAILED(4000, "Interceptor before() callback failed"),
    INTERCEPTOR_AFTER_FAILED(4001, "Interceptor after() callback failed"),
    INTERCEPTOR_EXCEPTION_FAILED(4002, "Interceptor onException() callback failed"),

    // Configuration errors (5000–5099)
    CONFIG_INVALID(5000, "Invalid configuration value"),
    CONFIG_LOAD_FAILED(5001, "Configuration file loading failed");

    private final int code;
    private final String description;

    ErrorCode(int code, String description) {
      this.code = code;
      this.description = description;
    }

    /** Returns the numeric error code. */
    public int getCode() {
      return code;
    }

    /** Returns the human-readable description. */
    public String getDescription() {
      return description;
    }

    @Override
    public String toString() {
      return "WG-" + code + " (" + name() + "): " + description;
    }
  }

  public WeaverGirlException(ErrorCode errorCode, String message) {
    super(formatMessage(errorCode, message));
    this.errorCode = errorCode;
  }

  public WeaverGirlException(ErrorCode errorCode, String message, Throwable cause) {
    super(formatMessage(errorCode, message), cause);
    this.errorCode = errorCode;
  }

  public WeaverGirlException(ErrorCode errorCode, Throwable cause) {
    super(formatMessage(errorCode, cause.getMessage()), cause);
    this.errorCode = errorCode;
  }

  /** Returns the structured error code. */
  public ErrorCode getErrorCode() {
    return errorCode;
  }

  private static String formatMessage(ErrorCode errorCode, String detail) {
    return "[WG-" + errorCode.code + "] " + errorCode.description + ": " + detail;
  }
}
