package actualspkg;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.PARAMETER, ElementType.TYPE})
@Retention(RetentionPolicy.CLASS)
@interface InvisibleAnnotation {
  String value() default "";
}

public class DisagreementTests {
  @InvisibleAnnotation
  public class ClassAnnotationMismatch {}

  public class DifferentFields {
    int diffName = 0;
    int diffType = 0;
    @Testing String diffAnnotation = "null";
    int diffAccessibility = 0;
  }

  public class DifferentMethods1 {
    @Testing
    DifferentMethods1 diffAnnotation() {
      return null;
    }

    public int diffAccessibility() {
      return 1;
    }
  }

  public class DifferentMethods2 {
    int missingOverload() {
      return 1;
    }

    int missingOverload(int foo) {
      return foo;
    }
  }

  public class DifferentMethods3 {
    int missingMethod() {
      return 1;
    }

    int presentMethod() {
      return 1;
    }
  }

  public interface DifferentMethods4 {
    int diffReturnType();
  }

  public interface DifferentMethods5 {
    int diffParameterAnnotations(@InvisibleAnnotation String p);
  }

  public class DifferentCtor1 { // Change modifiers and annotations on both
    @Testing
    DifferentCtor1() {}

    public DifferentCtor1(int foo) {}
  }

  public class DifferentCtor2 {
    DifferentCtor2() {} // Different overload
  }

  public static class NestedClasses1 {
    public interface DiffKind {}

    @Testing
    public class DiffAnnotation {}

    public static class DiffStaticness {}
  }

  public static class NestedClasses2 {
    public interface DiffAccessibility {}
  }

  public interface I<T> {}

  public class DifferentInterfaces implements I<Integer> {}
}
