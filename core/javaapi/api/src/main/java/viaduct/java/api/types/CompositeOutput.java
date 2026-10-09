package viaduct.java.api.types;

/** Tagging interface for output types that have fields, i.e. interfaces, objects, and unions. */
public interface CompositeOutput extends GRT {

  final class None implements CompositeOutput {
    private None() {}
  }

  /**
   * A marker object indicating that a type does not support selections. NotComposite may be used in
   * places where a CompositeOutput type is required by the compiler but one is not available.
   */
  CompositeOutput NotComposite = new None();
}
