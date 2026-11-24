package com.github.cc11001100.weavergirl.core.taint;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.HashMap;
import java.util.Map;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.ClassWriter;
import net.bytebuddy.jar.asm.Handle;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.jar.asm.Type;

/**
 * Rewrites Java 9+ {@code invokedynamic} string concatenation so the result keeps the taint of its
 * arguments. {@code a + b} compiles to {@code StringConcatFactory.makeConcatWithConstants}, which
 * never touches {@code StringBuilder}, so the append/toString advice cannot see it.
 *
 * <p>At every concat call site the arguments are spilled into fresh locals above the method's
 * {@code maxLocals}, the original {@code invokedynamic} runs, and {@link
 * com.github.cc11001100.weavergirl.api.taint.TaintConcat#propagate} receives the result plus a
 * template resolved at transform time and the boxed arguments. Only straight-line code is inserted
 * and no branch target moves, so the original stack map frames stay valid; the rewritten class goes
 * through {@link ClassWriter#COMPUTE_MAXS}.
 *
 * <p>The transformation is idempotent: a class already carrying the helper reference is returned
 * unchanged, which matters because the agent retransforms classes.
 *
 * @since 1.5.0
 */
public final class TaintConcatTransformer implements ClassFileTransformer {

  public static final TaintConcatTransformer INSTANCE = new TaintConcatTransformer();

  private static final String CONCAT_FACTORY = "java/lang/invoke/StringConcatFactory";
  private static final String MAKE_CONCAT = "makeConcat";
  private static final String MAKE_CONCAT_WITH_CONSTANTS = "makeConcatWithConstants";
  private static final String HELPER = "com/github/cc11001100/weavergirl/api/taint/TaintConcat";
  private static final String HELPER_METHOD = "propagate";
  private static final String HELPER_DESCRIPTOR =
      "(Ljava/lang/String;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/String;";

  private static final byte[] CONCAT_MARKER = ascii("makeConcat");
  private static final byte[] HELPER_MARKER = ascii("TaintConcat");

  private static final String[] SKIP_PREFIXES = {
    "java/",
    "javax/",
    "jdk/",
    "sun/",
    "com/sun/",
    "net/bytebuddy/",
    "org/mockito/",
    "org/junit/",
    "org/opentest4j/",
    "com/github/cc11001100/weavergirl/"
  };

  @Override
  public byte[] transform(
      ClassLoader loader,
      String className,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (classfileBuffer == null || className == null) {
      return null;
    }
    // Classes below the application (boot/platform) are not where untrusted input is concatenated,
    // and their loaders cannot resolve the helper reliably.
    if (loader == null || shouldSkip(className)) {
      return null;
    }
    if (indexOf(classfileBuffer, CONCAT_MARKER) < 0
        || indexOf(classfileBuffer, HELPER_MARKER) >= 0) {
      return null;
    }
    try {
      return rewrite(classfileBuffer);
    } catch (Throwable ignored) {
      // A class that fails to rewrite must still load untouched; the rest of the agent works.
      return null;
    }
  }

  private static boolean shouldSkip(String className) {
    for (String prefix : SKIP_PREFIXES) {
      if (className.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static byte[] rewrite(byte[] classfileBuffer) {
    ClassReader reader = new ClassReader(classfileBuffer);
    Map<String, Integer> maxLocals = readMaxLocals(reader);
    ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
    ConcatClassVisitor visitor = new ConcatClassVisitor(writer, maxLocals);
    reader.accept(visitor, 0);
    // A class whose constant pool merely mentions makeConcat (a log message, a test name) has no
    // call site; rewriting it would only re-serialise identical bytes.
    return visitor.changed ? writer.toByteArray() : null;
  }

  /** {@code maxLocals} is only known at {@code visitMaxs}, so the rewrite needs a first pass. */
  private static Map<String, Integer> readMaxLocals(ClassReader reader) {
    final Map<String, Integer> maxLocals = new HashMap<String, Integer>();
    reader.accept(
        new ClassVisitor(Opcodes.ASM9) {
          @Override
          public MethodVisitor visitMethod(
              int access, String name, String descriptor, String signature, String[] exceptions) {
            final String key = name + descriptor;
            return new MethodVisitor(Opcodes.ASM9) {
              @Override
              public void visitMaxs(int maxStack, int locals) {
                maxLocals.put(key, Integer.valueOf(locals));
              }
            };
          }
        },
        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    return maxLocals;
  }

  private static final class ConcatClassVisitor extends ClassVisitor {
    private final Map<String, Integer> maxLocals;
    boolean changed;

    ConcatClassVisitor(ClassVisitor delegate, Map<String, Integer> maxLocals) {
      super(Opcodes.ASM9, delegate);
      this.maxLocals = maxLocals;
    }

    @Override
    public MethodVisitor visitMethod(
        int access, String name, String descriptor, String signature, String[] exceptions) {
      MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
      if (delegate == null) {
        return null;
      }
      Integer locals = maxLocals.get(name + descriptor);
      if (locals == null) {
        return delegate;
      }
      return new ConcatMethodVisitor(this, delegate, locals.intValue());
    }
  }

  private static final class ConcatMethodVisitor extends MethodVisitor {
    private final ConcatClassVisitor owner;
    private final int firstFreeLocal;

    ConcatMethodVisitor(
        ConcatClassVisitor owner, MethodVisitor delegate, int firstFreeLocal) {
      super(Opcodes.ASM9, delegate);
      this.owner = owner;
      this.firstFreeLocal = firstFreeLocal;
    }

    @Override
    public void visitInvokeDynamicInsn(
        String name, String descriptor, Handle bootstrap, Object... bootstrapArgs) {
      String recipe = concatRecipe(name, descriptor, bootstrap, bootstrapArgs);
      if (recipe == null) {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrap, bootstrapArgs);
        return;
      }
      owner.changed = true;
      Type[] arguments = Type.getArgumentTypes(descriptor);
      int local = firstFreeLocal;
      int[] slots = new int[arguments.length];
      // The stack holds the arguments bottom to top, so the last one is stored first. Storing pops
      // the operand, which keeps the stack clean for the next one; they are reloaded for the indy.
      for (int i = arguments.length - 1; i >= 0; i--) {
        Type argument = arguments[i];
        slots[i] = local;
        local += argument.getSize();
        super.visitVarInsn(argument.getOpcode(Opcodes.ISTORE), slots[i]);
      }
      for (int i = 0; i < arguments.length; i++) {
        super.visitVarInsn(arguments[i].getOpcode(Opcodes.ILOAD), slots[i]);
      }
      super.visitInvokeDynamicInsn(name, descriptor, bootstrap, bootstrapArgs);
      super.visitLdcInsn(recipe);
      pushInt(arguments.length);
      super.visitTypeInsn(Opcodes.ANEWARRAY, "java/lang/Object");
      for (int i = 0; i < arguments.length; i++) {
        Type argument = arguments[i];
        super.visitInsn(Opcodes.DUP);
        pushInt(i);
        super.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slots[i]);
        if (argument.getSort() != Type.OBJECT && argument.getSort() != Type.ARRAY) {
          super.visitMethodInsn(
              Opcodes.INVOKESTATIC,
              boxOwner(argument),
              "valueOf",
              "(" + argument.getDescriptor() + ")L" + boxOwner(argument) + ";",
              false);
        }
        super.visitInsn(Opcodes.AASTORE);
      }
      super.visitMethodInsn(
          Opcodes.INVOKESTATIC, HELPER, HELPER_METHOD, HELPER_DESCRIPTOR, false);
    }

    /** Returns the resolved template, or null when this call site is not a string concat. */
    private static String concatRecipe(
        String name, String descriptor, Handle bootstrap, Object[] bootstrapArgs) {
      if (bootstrap == null || !CONCAT_FACTORY.equals(bootstrap.getOwner())) {
        return null;
      }
      if (MAKE_CONCAT.equals(name)) {
        // No recipe argument: every dynamic argument is concatenated back to back.
        StringBuilder template = new StringBuilder();
        Type[] arguments = Type.getArgumentTypes(descriptor);
        for (int i = 0; i < arguments.length; i++) {
          template.append('\u0001');
        }
        return template.toString();
      }
      if (!MAKE_CONCAT_WITH_CONSTANTS.equals(name)
          || bootstrapArgs.length == 0
          || !(bootstrapArgs[0] instanceof String)) {
        return null;
      }
      return resolveRecipe((String) bootstrapArgs[0], bootstrapArgs);
    }

    private static String resolveRecipe(String recipe, Object[] bootstrapArgs) {
      StringBuilder resolved = new StringBuilder(recipe.length());
      int constant = 1;
      for (int i = 0; i < recipe.length(); i++) {
        char current = recipe.charAt(i);
        if (current == '\u0002') {
          Object value = constant < bootstrapArgs.length ? bootstrapArgs[constant] : null;
          resolved.append(String.valueOf(value));
          constant++;
        } else {
          resolved.append(current);
        }
      }
      return resolved.toString();
    }

    private static String boxOwner(Type argument) {
      switch (argument.getSort()) {
        case Type.BOOLEAN:
          return "java/lang/Boolean";
        case Type.CHAR:
          return "java/lang/Character";
        case Type.BYTE:
          return "java/lang/Byte";
        case Type.SHORT:
          return "java/lang/Short";
        case Type.INT:
          return "java/lang/Integer";
        case Type.LONG:
          return "java/lang/Long";
        case Type.FLOAT:
          return "java/lang/Float";
        case Type.DOUBLE:
          return "java/lang/Double";
        default:
          throw new IllegalArgumentException("not a primitive: " + argument);
      }
    }

    private void pushInt(int value) {
      if (value >= -1 && value <= 5) {
        super.visitInsn(Opcodes.ICONST_0 + value);
      } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
        super.visitIntInsn(Opcodes.BIPUSH, value);
      } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
        super.visitIntInsn(Opcodes.SIPUSH, value);
      } else {
        super.visitLdcInsn(Integer.valueOf(value));
      }
    }
  }

  private static byte[] ascii(String text) {
    byte[] bytes = new byte[text.length()];
    for (int i = 0; i < text.length(); i++) {
      bytes[i] = (byte) text.charAt(i);
    }
    return bytes;
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    int last = haystack.length - needle.length;
    outer:
    for (int i = 0; i <= last; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private TaintConcatTransformer() {}
}
