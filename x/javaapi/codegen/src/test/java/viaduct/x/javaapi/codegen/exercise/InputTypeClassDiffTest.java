package viaduct.x.javaapi.codegen.exercise;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ClassDiff tests for generated input type classes, comparing them against expected reference
 * classes.
 */
class InputTypeClassDiffTest extends AbstractClassDiffTest {

  private static final String SCHEMA_RESOURCE = "graphql/exerciser_input_schema.graphqls";

  @Override
  protected String getSchemaResource() {
    return SCHEMA_RESOURCE;
  }

  @Test
  void exerciseAllInputs() throws Exception {
    exerciseTypes(
        List.of("SimpleInput", "InputWithDescription", "ComplexInput", "AllFieldTypesInput"));
  }

  @Test
  void exerciseSimpleInput() throws Exception {
    exerciseSingleType("SimpleInput");
  }

  @Test
  void exerciseInputWithDescription() throws Exception {
    exerciseSingleType("InputWithDescription");
  }

  @Test
  void exerciseComplexInput() throws Exception {
    exerciseSingleType("ComplexInput");
  }

  @Test
  void exerciseAllFieldTypesInput() throws Exception {
    exerciseSingleType("AllFieldTypesInput");
  }
}
