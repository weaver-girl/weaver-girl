package com.github.cc11001100.weavergirl.core.testsupport;

import java.lang.reflect.Field;

/**
 * Reads and writes static fields, including {@code final} ones.
 *
 * <p>On JDK 12 and newer {@link Field#set} refuses a {@code final} field, so the write goes through
 * {@code sun.misc.Unsafe}, which still accepts it. Reached reflectively because this module compiles
 * with {@code --release 8}, where that class is not on the compile classpath.
 */
public final class Statics {

  private Statics() {}

  /** The value currently held by {@code field}, static when {@code target} is null. */
  public static Object read(Field field, Object target) throws Exception {
    try {
      field.setAccessible(true);
      return field.get(target);
    } catch (RuntimeException inaccessible) {
      // A field in a module that is not opened to this one refuses setAccessible. Unsafe still reads it.
      Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
      Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
      theUnsafe.setAccessible(true);
      Object unsafe = theUnsafe.get(null);
      Object base =
          target == null
              ? unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field)
              : target;
      long offset =
          target == null
              ? (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field)
              : (Long) unsafeClass.getMethod("objectFieldOffset", Field.class).invoke(unsafe, field);
      return unsafeClass
          .getMethod("getObject", Object.class, long.class)
          .invoke(unsafe, base, offset);
    }
  }

  /** Replaces the value held by {@code field}. A {@code final} field is written through Unsafe. */
  public static void write(Field field, Object target, Object value) throws Exception {
    try {
      field.setAccessible(true);
      field.set(target, value);
      return;
    } catch (IllegalAccessException | RuntimeException ignored) {
      // A final field on JDK 12+ refuses the write, and it reports that as either an access or an
      // argument failure depending on the JDK. A field in a module that is not opened to this one
      // refuses setAccessible outright. Both fall through to Unsafe.
    }
    Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
    Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
    theUnsafe.setAccessible(true);
    Object unsafe = theUnsafe.get(null);
    Object base =
        target == null
            ? unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field)
            : target;
    long offset =
        target == null
            ? (Long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, field)
            : (Long) unsafeClass.getMethod("objectFieldOffset", Field.class).invoke(unsafe, field);
    unsafeClass
        .getMethod("putObject", Object.class, long.class, Object.class)
        .invoke(unsafe, base, offset, value);
  }
}
