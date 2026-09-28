package transformsafety;

public class OkTarget {
  public static volatile boolean seen;

  public static String ping() {
    return "ok";
  }
}
