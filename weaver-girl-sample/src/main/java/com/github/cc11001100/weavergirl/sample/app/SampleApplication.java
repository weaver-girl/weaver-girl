// weaver-girl-sample/src/main/java/com/github/cc11001100/weavergirl/sample/app/SampleApplication.java
package com.github.cc11001100.weavergirl.sample.app;

/**
 * Sample application demonstrating weaver-girl agent usage.
 */
public class SampleApplication {

    public static void main(String[] args) {
        System.out.println("=== Weaver-Girl Sample Application ===\n");

        TargetService service = new TargetService();

        System.out.println("--- Testing greet ---");
        String greeting = service.greet("World");
        System.out.println("Result: " + greeting);

        System.out.println("\n--- Testing calculate ---");
        int result = service.calculate(3, 4);
        System.out.println("Result: " + result);

        System.out.println("\n--- Testing riskyOperation ---");
        try {
            service.riskyOperation();
        } catch (RuntimeException e) {
            System.out.println("Caught: " + e.getMessage());
        }

        System.out.println("\n=== Sample Application Complete ===");
    }
}
