package com.github.cc11001100.weavergirl.api.context;

import java.util.Map;

/**
 * Runnable wrapper that captures the ThreadContext at creation time
 * and restores it in the executing thread.
 */
public class ContextRunnable implements Runnable {

    private final Runnable delegate;
    private final Map<String, Object> capturedContext;

    public ContextRunnable(Runnable delegate) {
        this.delegate = delegate;
        this.capturedContext = ThreadContext.capture();
    }

    @Override
    public void run() {
        Map<String, Object> previous = ThreadContext.capture();
        try {
            ThreadContext.restore(capturedContext);
            delegate.run();
        } finally {
            ThreadContext.restore(previous);
        }
    }
}
