package transformsafety;

public class OkLoaded {
  public static volatile boolean seen;

  public static String ping() {
    return "ok-loaded";
  }
}
