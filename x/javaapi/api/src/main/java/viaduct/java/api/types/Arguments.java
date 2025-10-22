package viaduct.java.api.types;

/** Tagging interface for virtual input types that wrap field arguments. */
public interface Arguments extends InputLike {

  /** A marker object indicating the lack of schematic arguments. */
  final class None implements Arguments {
    private None() {}
  }

  Arguments NoArguments = new None();
}
