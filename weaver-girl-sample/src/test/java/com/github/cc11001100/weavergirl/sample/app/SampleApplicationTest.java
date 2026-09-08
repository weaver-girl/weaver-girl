// weaver-girl-sample/src/test/java/com/github/cc11001100/weavergirl/sample/app/SampleApplicationTest.java
package com.github.cc11001100.weavergirl.sample.app;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SampleApplicationTest {

  @Test
  void targetService_greet_returnsCorrectGreeting() {
    TargetService service = new TargetService();
    assertEquals("Hello, World!", service.greet("World"));
  }

  @Test
  void targetService_greet_emptyName_returnsHelloWithEmpty() {
    TargetService service = new TargetService();
    assertEquals("Hello, !", service.greet(""));
  }

  @Test
  void targetService_calculate_returnsSum() {
    TargetService service = new TargetService();
    assertEquals(7, service.calculate(3, 4));
    assertEquals(0, service.calculate(0, 0));
    assertEquals(-1, service.calculate(-5, 4));
  }

  @Test
  void targetService_riskyOperation_throwsRuntimeException() {
    TargetService service = new TargetService();
    RuntimeException exception = assertThrows(RuntimeException.class, service::riskyOperation);
    assertEquals("Something went wrong!", exception.getMessage());
  }
}
