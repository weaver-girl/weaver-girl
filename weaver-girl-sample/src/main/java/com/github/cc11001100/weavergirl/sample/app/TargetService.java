// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/app/TargetService.java
package com.github.cc11001100.weavergirl.sample.app;

/**
 * Sample target service class that will be intercepted by the agent.
 */
public class TargetService {

    public String greet(String name) {
        return "Hello, " + name + "!";
    }

    public int calculate(int a, int b) {
        return a + b;
    }

    public void riskyOperation() {
        throw new RuntimeException("Something went wrong!");
    }
}
