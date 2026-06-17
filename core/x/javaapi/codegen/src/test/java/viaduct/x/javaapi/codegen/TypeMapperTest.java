package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import viaduct.graphql.schema.ViaductSchema;
import viaduct.graphql.schema.graphqljava.extensions.ViaductSchemaFactory;

/**
 * Tests for TypeMapper using ViaductSchema.TypeExpr. TypeMapper now operates on ViaductSchema types
 * instead of graphql-java types directly.
 *
 * <p>Since TypeExpr requires a ViaductSchema context, these tests create real schemas from SDL
 * strings to test type mapping behavior.
 */
class TypeMapperTest {

  private TypeMapper mapper;
  private ViaductSchema schema;

  @BeforeEach
  void setUp() throws IOException {
    mapper = new TypeMapper();

    // Create a schema with various field types for testing
    String sdl =
        """
        type Query {
          stringField: String
          nonNullStringField: String!
          intField: Int
          nonNullIntField: Int!
          floatField: Float
          nonNullFloatField: Float!
          booleanField: Boolean
          nonNullBooleanField: Boolean!
          idField: ID
          userField: User
          listOfStrings: [String]
          nonNullListOfStrings: [String]!
          listOfNonNullStrings: [String!]
          listOfUsers: [User]
          listOfNonNullInts: [Int!]
          listOfNonNullFloats: [Float!]
          listOfNonNullBooleans: [Boolean!]
          nestedList: [[Int!]!]
        }

        type User {
          id: ID!
          name: String!
        }
        """;

    schema = ViaductSchemaFactory.fromTypeDefinitionRegistry(sdl);
  }

  private ViaductSchema.Field getQueryField(String fieldName) {
    ViaductSchema.Object queryType = (ViaductSchema.Object) schema.getTypes().get("Query");
    return queryType.getFields().stream()
        .filter(f -> f.getName().equals(fieldName))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Field not found: " + fieldName));
  }

  @Test
  void mapsStringType() {
    ViaductSchema.Field field = getQueryField("stringField");

    assertEquals("String", mapper.toJavaType(field.getType()));
    assertTrue(field.getType().isNullable());
  }

  @Test
  void mapsNonNullStringType() {
    ViaductSchema.Field field = getQueryField("nonNullStringField");

    assertEquals("String", mapper.toJavaType(field.getType()));
    assertFalse(field.getType().isNullable());
  }

  @Test
  void mapsIntType() {
    ViaductSchema.Field field = getQueryField("intField");

    assertEquals("Integer", mapper.toJavaType(field.getType()));
    assertTrue(field.getType().isNullable());
  }

  @Test
  void mapsNonNullIntType() {
    ViaductSchema.Field field = getQueryField("nonNullIntField");

    assertEquals("int", mapper.toJavaType(field.getType()));
    assertFalse(field.getType().isNullable());
  }

  @Test
  void mapsFloatType() {
    ViaductSchema.Field field = getQueryField("floatField");

    assertEquals("Double", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsNonNullFloatType() {
    ViaductSchema.Field field = getQueryField("nonNullFloatField");

    assertEquals("double", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsBooleanType() {
    ViaductSchema.Field field = getQueryField("booleanField");

    assertEquals("Boolean", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsNonNullBooleanType() {
    ViaductSchema.Field field = getQueryField("nonNullBooleanField");

    assertEquals("boolean", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsIdType() {
    ViaductSchema.Field field = getQueryField("idField");

    assertEquals("String", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsCustomType() {
    ViaductSchema.Field field = getQueryField("userField");

    assertEquals("User", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsListType() {
    ViaductSchema.Field field = getQueryField("listOfStrings");

    assertEquals("List<String>", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsNonNullListType() {
    ViaductSchema.Field field = getQueryField("nonNullListOfStrings");

    assertEquals("List<String>", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsListOfNonNullType() {
    ViaductSchema.Field field = getQueryField("listOfNonNullStrings");

    assertEquals("List<String>", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsListOfCustomType() {
    ViaductSchema.Field field = getQueryField("listOfUsers");

    assertEquals("List<User>", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsListOfNonNullIntToBoxedType() {
    // [Int!] should map to List<Integer>, not List<int> (primitives can't be generic type params)
    ViaductSchema.Field field = getQueryField("listOfNonNullInts");

    assertEquals("List<Integer>", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsListOfNonNullFloatToBoxedType() {
    // [Float!] should map to List<Double>, not List<double>
    ViaductSchema.Field field = getQueryField("listOfNonNullFloats");

    assertEquals("List<Double>", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsListOfNonNullBooleanToBoxedType() {
    // [Boolean!] should map to List<Boolean>, not List<boolean>
    ViaductSchema.Field field = getQueryField("listOfNonNullBooleans");

    assertEquals("List<Boolean>", mapper.toJavaType(field.getType()));
  }

  @Test
  void mapsNestedListType() {
    // [[Int!]!] should map to List<List<Integer>>
    ViaductSchema.Field field = getQueryField("nestedList");

    assertEquals("List<List<Integer>>", mapper.toJavaType(field.getType()));
  }
}
