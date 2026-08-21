package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static viaduct.x.javaapi.codegen.TestStrings.countOccurrences;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the accessor-name collision check on the Java GRT generator.
 *
 * <p>Without the check the generator emits both accessors and javac rejects the file for a
 * duplicate method the schema author never wrote. The Java GRTs have no {@code OrNull} accessor, so
 * a {@code bar}/{@code barOrNull} pair generates cleanly here while the Kotlin generators must
 * reject it.
 */
class AccessorNameCollisionTest {

  private static ObjectModel objectWith(List<FieldModel> fields) {
    return new ObjectModel("com.example.types", "Collision", List.of(), fields, null, false, false);
  }

  private static InterfaceModel interfaceWith(List<FieldModel> fields) {
    return new InterfaceModel("com.example.types", "Collision", List.of(), fields, null, false);
  }

  @Test
  void objectFieldCollidingWithStrictAccessorIsRejected() {
    ObjectModel model =
        objectWith(
            List.of(
                FieldModel.simple("foo", "String", true),
                FieldModel.simple("fooOrThrow", "String", true)));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> JavaGRTGenerator.ObjectGenerator.generate(model));

    assertTrue(error.getMessage().contains("type `Collision`"), error.getMessage());
    assertTrue(
        error.getMessage().contains("fields `foo` and `fooOrThrow` both generate `getFooOrThrow`"),
        error.getMessage());
  }

  @Test
  void interfaceFieldCollidingWithStrictAccessorIsRejected() {
    InterfaceModel model =
        interfaceWith(
            List.of(
                FieldModel.simple("foo", "String", true),
                FieldModel.simple("fooOrThrow", "String", true)));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> JavaGRTGenerator.InterfaceGenerator.generate(model));

    assertTrue(
        error.getMessage().contains("fields `foo` and `fooOrThrow` both generate `getFooOrThrow`"),
        error.getMessage());
  }

  /**
   * Java always prefixes {@code get}, so an {@code is} field collides under {@code
   * getIsReadyOrThrow}.
   */
  @Test
  void isPrefixedFieldCollidingWithStrictAccessorIsRejected() {
    ObjectModel model =
        objectWith(
            List.of(
                FieldModel.simple("isReady", "Boolean", true),
                FieldModel.simple("isReadyOrThrow", "Boolean", true)));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> JavaGRTGenerator.ObjectGenerator.generate(model));

    assertTrue(
        error
            .getMessage()
            .contains("fields `isReady` and `isReadyOrThrow` both generate `getIsReadyOrThrow`"),
        error.getMessage());
  }

  /** Two field names differing only in leading case map to one accessor. */
  @Test
  void caseOnlyCollisionIsRejected() {
    ObjectModel model =
        objectWith(
            List.of(
                FieldModel.simple("foo", "String", true),
                FieldModel.simple("Foo", "String", true)));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class, () -> JavaGRTGenerator.ObjectGenerator.generate(model));

    // The pair collides under every suffix, so the message names it once.
    assertTrue(
        error.getMessage().contains("fields `foo` and `Foo` both generate"), error.getMessage());
    assertEquals(1, countOccurrences(error.getMessage(), "both generate"), error.getMessage());
  }

  @Test
  void suffixNamedFieldWithoutSiblingIsGenerated() {
    ObjectModel model = objectWith(List.of(FieldModel.simple("fooOrThrow", "String", true)));

    String generated = assertDoesNotThrow(() -> JavaGRTGenerator.ObjectGenerator.generate(model));

    assertTrue(generated.contains("public String getFooOrThrowOrThrow()"), generated);
    assertTrue(generated.contains("public String getFooOrThrow()"), generated);
  }

  /**
   * The suffix list is maintained by hand, since the Java GRTs emit no soft accessor and so cannot
   * derive it from {@code AccessorForm}. This asserts it agrees with what the templates emit.
   */
  @Test
  void emittedAccessorNamesMatchTheSuffixListTheCheckUses() {
    ObjectModel model = objectWith(List.of(FieldModel.simple("foo", "String", true)));

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    Set<String> emitted =
        Pattern.compile("public String (getFoo\\w*)\\(")
            .matcher(generated)
            .results()
            .map(m -> m.group(1))
            .collect(Collectors.toSet());
    assertEquals(
        JavaGRTGenerator.FIELD_ACCESSOR_SUFFIXES.stream()
            .map(s -> "getFoo" + s)
            .collect(Collectors.toSet()),
        emitted);
  }

  @Test
  void softSuffixPairIsGeneratedBecauseJavaHasNoSoftAccessor() {
    ObjectModel model =
        objectWith(
            List.of(
                FieldModel.simple("bar", "String", true),
                FieldModel.simple("barOrNull", "String", true)));

    String generated = assertDoesNotThrow(() -> JavaGRTGenerator.ObjectGenerator.generate(model));

    assertTrue(generated.contains("public String getBarOrThrow()"), generated);
    assertTrue(generated.contains("public String getBarOrNullOrThrow()"), generated);
  }
}
