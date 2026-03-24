package expectedspkg;

import actualspkg.Testing;

public class DisagreementTests {
  public class ClassAnnotationMismatch {}

  public class DifferentFields {
    int mismatchedName = 0;
    float diffType = 0F;
    String diffAnnotation = "null";
    private int diffAccessibility = 0;
  }

  public class DifferentMethods1 {
    DifferentMethods1 diffAnnotation() {
      return null;
    }

    int diffAccessibility() {
      return 1;
    }
  }

  public class DifferentMethods2 {
    int missingOverload() {
      return 1;
    }
  }

  public class DifferentMethods3 {
    int presentMethod() {
      return 1;
    }
  }

  public interface DifferentMethods4 {
    String diffReturnType();
  }

  public interface DifferentMethods5 {
    int diffParameterAnnotations(String p);
  }

  public class DifferentCtor1 { // Change modifiers and annotations on both
    private DifferentCtor1() {}

    @Testing
    DifferentCtor1(int foo) {}
  }

  public class DifferentCtor2 {
    DifferentCtor2(int foo) {} // Different overload
  }

  public static class NestedClasses1 {
    public class DiffKind {}

    public class DiffAnnotation {}

    public class DiffStaticness {}
  }

  public static class NestedClasses2 {
    interface DiffAccessibility {}
  }

  public interface I<T> {}

  public class DifferentInterfaces implements I<String> {}
}
