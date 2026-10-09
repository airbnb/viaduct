package viaduct.x.javaapi.codegen.exercise;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ClassDiff tests for generated enum classes, comparing them against expected reference classes.
 */
class EnumClassDiffTest extends AbstractClassDiffTest {

  private static final String SCHEMA_RESOURCE = "graphql/exerciser_enum_schema.graphqls";

  @Override
  protected String getSchemaResource() {
    return SCHEMA_RESOURCE;
  }

  @Test
  void exerciseAllEnums() throws Exception {
    exerciseTypes(List.of("SimpleEnum", "EnumWithDescription", "SingleValueEnum", "StatusEnum"));
  }

  @Test
  void exerciseSimpleEnum() throws Exception {
    exerciseSingleType("SimpleEnum");
  }

  @Test
  void exerciseEnumWithDescription() throws Exception {
    exerciseSingleType("EnumWithDescription");
  }

  @Test
  void exerciseSingleValueEnum() throws Exception {
    exerciseSingleType("SingleValueEnum");
  }

  @Test
  void exerciseStatusEnum() throws Exception {
    exerciseSingleType("StatusEnum");
  }
}
