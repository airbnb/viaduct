package expectedspkg;

import actualspkg.Testing;

public class AgreementTests {
  public class EmptyClass {}

  public class WithCtors {
    WithCtors() {}

    public WithCtors(int foo) {}

    @Testing
    protected WithCtors(WithCtors bar) {}
  }

  @Testing
  public static class WithFields {
    public int f1 = 0;
    @Testing protected static float f2 = 0F;
    String f3 = null;
    private WithFields f5 = null;
  }

  private interface WithMethods {
    int m1();

    int m1(int a1);

    int m1(int a1, String a2);

    int m1(WithMethods a1);

    WithFields m2();
  }

  public static class NestedClasses {
    public class EmptyClass {}

    interface EmptyInterface {}

    @Testing
    interface AnnotatedInterface {
      public int m1();

      int m2(int a1);

      class DoubleNestedEmptyClass {}
    }
  }

  public interface InterfaceWithTypeParams<A, B> {}

  private class ImplementsInterface implements InterfaceWithTypeParams<String[], String> {}
}
