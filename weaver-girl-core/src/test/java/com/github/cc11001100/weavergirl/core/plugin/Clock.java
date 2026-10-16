package com.github.cc11001100.weavergirl.core.plugin;

/**
 * A service one plugin can offer another. A plugin compiled against the test classpath can see it,
 * exactly as a real service lives in the api module both plugins share.
 */
public interface Clock {

  long now();
}
