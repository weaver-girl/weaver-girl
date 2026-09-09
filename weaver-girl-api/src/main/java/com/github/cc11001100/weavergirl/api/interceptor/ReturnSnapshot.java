package com.github.cc11001100.weavergirl.api.interceptor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Immutable snapshot of a method return value at a specific point in time.
 *
 * <p>Return snapshots are created by the framework when a method returns. They carry the value
 * itself plus metadata useful for tracing, caching, and change-detection use cases.
 *
 * <h3>Identity</h3>
 *
 * <p>Each snapshot has a globally unique {@link #getId() id} and a {@link #getVersion() version}
 * that increments across snapshots for the same method. The version counter is monotonic per
 * method signature; it does not wrap.
 *
 * <h3>Usage</h3>
 *
 * <pre>
 * ReturnSnapshot snapshot = invocation.getReturnSnapshot();
 * if (snapshot != null) {
 *     Object value = snapshot.getValue();
 *     long version = snapshot.getVersion();
 *     long timestampNanos = snapshot.getTimestampNanos();
 * }</pre>
 *
 * @see ReturnVersion
 * @since 1.7.0
 */
public class ReturnSnapshot {

  private static final AtomicLong GLOBAL_ID = new AtomicLong(0);

  private final long id;
  private final Object value;
  private final long version;
  private final long timestampNanos;
  private final String methodName;
  private final Class<?> returnType;

  /**
   * Creates a new return snapshot.
   *
   * @param value the return value (may be null)
   * @param version the version number for this method (monotonic)
   * @param methodName the method name that produced this return value
   * @param returnType the return type of the method
   */
  public ReturnSnapshot(Object value, long version, String methodName, Class<?> returnType) {
    this.id = GLOBAL_ID.incrementAndGet();
    this.value = value;
    this.version = version;
    this.timestampNanos = System.nanoTime();
    this.methodName = methodName;
    this.returnType = returnType;
  }

  /**
   * Returns the globally unique identifier for this snapshot.
   *
   * @return the snapshot id
   */
  public long getId() {
    return id;
  }

  /**
   * Returns the captured return value.
   *
   * @return the return value, may be null
   */
  public Object getValue() {
    return value;
  }

  /**
   * Returns the version number for this method's return value sequence.
   *
   * <p>The version starts at 1 for the first return of a given method and increments by 1 on each
   * subsequent return. It is used to detect whether a cached value is stale.
   *
   * @return the version (>= 1)
   */
  public long getVersion() {
    return version;
  }

  /**
   * Returns the timestamp (in nanoseconds) when this snapshot was captured.
   *
   * @return the timestamp in nanos since an arbitrary origin
   */
  public long getTimestampNanos() {
    return timestampNanos;
  }

  /**
   * Returns the name of the method that produced this return value.
   *
   * @return the method name, never null
   */
  public String getMethodName() {
    return methodName;
  }

  /**
   * Returns the return type of the method that produced this return value.
   *
   * @return the return type, never null
   */
  public Class<?> getReturnType() {
    return returnType;
  }

  @Override
  public String toString() {
    return "ReturnSnapshot{id="
        + id
        + ", version="
        + version
        + ", method="
        + methodName
        + ", value="
        + value
        + "}";
  }
}
