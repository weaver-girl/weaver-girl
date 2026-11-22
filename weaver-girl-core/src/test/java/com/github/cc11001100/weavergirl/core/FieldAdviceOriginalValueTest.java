package com.github.cc11001100.weavergirl.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.cc11001100.weavergirl.api.interceptor.FieldInterceptor;
import com.github.cc11001100.weavergirl.api.interceptor.Interceptor;
import com.github.cc11001100.weavergirl.api.interceptor.FieldInvocation;
import com.github.cc11001100.weavergirl.api.interceptor.InterceptorDefinition;
import com.github.cc11001100.weavergirl.api.matcher.ClassMatcher;
import com.github.cc11001100.weavergirl.api.matcher.FieldMatcher;
import com.github.cc11001100.weavergirl.api.matcher.MethodMatcher;
import com.github.cc11001100.weavergirl.api.pointcut.FieldPointcut;
import com.github.cc11001100.weavergirl.api.pointcut.Pointcut;
import com.github.cc11001100.weavergirl.core.registry.DefaultInterceptorRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The field hooks must tell an interceptor what it is looking at and must honour a rejected write.
 *
 * <p>Both were broken in ways a unit test could not see before: {@code onFieldGet} received a
 * literal {@code null} instead of the value read, and {@code skipWrite()} restored the value being
 * written — a no-op, so the write went through. Each test here asserts the observable effect.
 */
class FieldAdviceOriginalValueTest {

  private final DefaultInterceptorRegistry registry = new DefaultInterceptorRegistry();
  private com.github.cc11001100.weavergirl.api.registry.InterceptorRegistry previous;
  private boolean previousEnabled;

  static class Account {
    public String password = "hunter2";

    public void setPassword(String password) {
      this.password = password;
    }

    public String getPassword() {
      return password;
    }
  }

  @BeforeEach
  void install() {
    previous = InterceptorHolder.getRegistry();
    previousEnabled = InterceptorHolder.isInterceptionEnabled();
    InterceptorHolder.setRegistry(registry);
    InterceptorHolder.setInterceptionEnabled(true, "test");
  }

  @AfterEach
  void restore() {
    InterceptorHolder.setRegistry(previous);
    InterceptorHolder.setInterceptionEnabled(previousEnabled, "test");
  }

  private abstract static class AbstractFieldInterceptor implements Interceptor, FieldInterceptor {}

  private void register(String name, AbstractFieldInterceptor interceptor) {
    registry.register(
        new InterceptorDefinition(
            name,
            new Pointcut(ClassMatcher.byName(Account.class.getName()), MethodMatcher.any()),
            interceptor));
  }

  @Test
  void onFieldGetReceivesTheValueActuallyRead() throws Exception {
    AtomicReference<Object> seen = new AtomicReference<>();
    register(
        "read",
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldGet(FieldInvocation invocation, Object originalValue) {
            seen.set(originalValue);
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {
              FieldPointcut.inClass(
                  ClassMatcher.byName(Account.class.getName()), FieldMatcher.byName("password"))
            };
          }
        });

    Field field = Account.class.getDeclaredField("password");
    FieldAdvice.onFieldEnter(Account.class, field, new Account(), null);

    assertEquals("hunter2", seen.get(), "the read hook must see the field's current value");
  }

  @Test
  void onFieldGetStillAllowsReplacingTheReturnedValue() throws Exception {
    register(
        "mask",
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldGet(FieldInvocation invocation, Object originalValue) {
            invocation.setReturnValue("********");
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {
              FieldPointcut.inClass(
                  ClassMatcher.byName(Account.class.getName()), FieldMatcher.byName("password"))
            };
          }
        });

    Field field = Account.class.getDeclaredField("password");
    FieldInvocation invocation =
        FieldAdvice.onFieldEnter(Account.class, field, new Account(), null);

    assertNotNull(invocation);
    assertTrue(invocation.isReturnOverridden());
    assertEquals("********", invocation.getReturnValue());
  }

  @Test
  void skipWriteLeavesTheFieldHoldingItsPreviousValue() throws Exception {
    register(
        "reject-empty",
        new AbstractFieldInterceptor() {
          @Override
          public void onFieldSet(FieldInvocation invocation, Object target, Object originalValue) {
            if (String.valueOf(originalValue).isEmpty()) {
              invocation.skipWrite();
            }
          }

          @Override
          public FieldPointcut[] fieldPointcuts() {
            return new FieldPointcut[] {
              FieldPointcut.inClass(
                  ClassMatcher.byName(Account.class.getName()), FieldMatcher.byName("password"))
            };
          }
        });

    Account account = new Account();
    // The woven entry point is what applies the decision to the argument the putfield will use.
    FieldAdvice.onWovenEnter(Account.class, "setPassword", account, "");

    assertEquals(
        "hunter2",
        account.password,
        "a rejected write must not reach the field, so the previous value stays");
  }
}
