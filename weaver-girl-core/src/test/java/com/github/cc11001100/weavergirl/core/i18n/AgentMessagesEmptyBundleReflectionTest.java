package com.github.cc11001100.weavergirl.core.i18n;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Locale;
import java.util.ResourceBundle;
import org.junit.jupiter.api.Test;

/**
 * Tests for AgentMessages.EmptyBundle via reflection.
 */
class AgentMessagesEmptyBundleReflectionTest {

  @Test
  void emptyBundle_viaReflection() throws Exception {
    // 通过反射获取 EmptyBundle 类
    Class<?> emptyBundleClass = Class.forName(
        "com.github.cc11001100.weavergirl.core.i18n.AgentMessages$EmptyBundle",
        true,
        AgentMessages.class.getClassLoader());

    Constructor<?> ctor = emptyBundleClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    ResourceBundle bundle = (ResourceBundle) ctor.newInstance();

    // 测试 handleGetObject
    Method handleGetObject = emptyBundleClass.getDeclaredMethod("handleGetObject", String.class);
    handleGetObject.setAccessible(true);
    Object result = handleGetObject.invoke(bundle, "anyKey");
    assertNull(result);

    // 测试 getKeys
    Method getKeys = emptyBundleClass.getDeclaredMethod("getKeys");
    getKeys.setAccessible(true);
    Enumeration<String> keys = (Enumeration<String>) getKeys.invoke(bundle);
    assertFalse(keys.hasMoreElements());
  }

  @Test
  void emptyBundle_parentMethods() throws Exception {
    Class<?> emptyBundleClass = Class.forName(
        "com.github.cc11001100.weavergirl.core.i18n.AgentMessages$EmptyBundle",
        true,
        AgentMessages.class.getClassLoader());

    Constructor<?> ctor = emptyBundleClass.getDeclaredConstructor();
    ctor.setAccessible(true);
    ResourceBundle bundle = (ResourceBundle) ctor.newInstance();

    // 测试父类的 keySet 方法
    assertTrue(bundle.keySet().isEmpty());
  }
}
