package com.github.cc11001100.weavergirl.core.plugin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PluginClassLoaderTest {

    @Test
    void constructor_acceptsUrlsAndParent() {
        ClassLoader parent = getClass().getClassLoader();
        PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0], parent);
        assertEquals(parent, cl.getParent());
    }

    @Test
    void loadClass_javaCoreClass_delegatesToParent() throws ClassNotFoundException {
        PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0], getClass().getClassLoader());
        // java.lang.String is a JDK class, must delegate to parent
        Class<?> stringClass = cl.loadClass("java.lang.String");
        assertSame(String.class, stringClass, "JDK classes must be loaded by parent ClassLoader");
    }

    @Test
    void loadClass_javaxClass_delegatesToParent() throws ClassNotFoundException {
        PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0], getClass().getClassLoader());
        // javax classes should delegate to parent
        Class<?> listClass = cl.loadClass("javax.swing.JPanel");
        // Just verify it loaded without error via parent delegation
        assertNotNull(listClass);
    }

    @Test
    void loadClass_nonExistentClass_throwsClassNotFoundException() {
        PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0], getClass().getClassLoader());
        assertThrows(ClassNotFoundException.class, () -> cl.loadClass("com.nonexistent.FakeClass123"));
    }

    @Test
    void loadClass_alreadyLoadedClass_returnsSameInstance() throws ClassNotFoundException {
        PluginClassLoader cl = new PluginClassLoader(new java.net.URL[0], getClass().getClassLoader());
        Class<?> first = cl.loadClass("java.lang.Integer");
        Class<?> second = cl.loadClass("java.lang.Integer");
        assertSame(first, second, "Already loaded classes must return the same instance");
    }
}
