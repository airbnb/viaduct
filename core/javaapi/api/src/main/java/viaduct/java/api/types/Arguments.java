package viaduct.java.api.types;

/** Tagging interface for virtual input types that wrap field arguments. */
public interface Arguments extends InputLike {

  /**
   * Legacy no-arguments type.
   *
   * @deprecated Use {@link NoArguments}. This alias preserves generated code compiled against the
   *     original Java API.
   */
  @Deprecated
  class None implements Arguments {
    private None() {}
  }

  /** A marker object indicating the lack of schematic arguments. */
  final class NoArguments extends None {
    private NoArguments() {}
  }

  NoArguments None = new NoArguments();

  /**
   * Legacy no-arguments singleton.
   *
   * @deprecated Use {@link #None}.
   */
  @Deprecated Arguments NoArguments = None;

  /** Returns whether a class is either the current or legacy no-arguments marker type. */
  static boolean isNoArgumentsClass(Class<?> argumentsClass) {
    return argumentsClass == NoArguments.class || argumentsClass == None.class;
  }
}
