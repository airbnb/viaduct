package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

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

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("String");
    assertThat(field.getType().isNullable()).isTrue();
  }

  @Test
  void mapsNonNullStringType() {
    ViaductSchema.Field field = getQueryField("nonNullStringField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("String");
    assertThat(field.getType().isNullable()).isFalse();
  }

  @Test
  void mapsIntType() {
    ViaductSchema.Field field = getQueryField("intField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("Integer");
    assertThat(field.getType().isNullable()).isTrue();
  }

  @Test
  void mapsNonNullIntType() {
    ViaductSchema.Field field = getQueryField("nonNullIntField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("int");
    assertThat(field.getType().isNullable()).isFalse();
  }

  @Test
  void mapsFloatType() {
    ViaductSchema.Field field = getQueryField("floatField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("Double");
  }

  @Test
  void mapsNonNullFloatType() {
    ViaductSchema.Field field = getQueryField("nonNullFloatField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("double");
  }

  @Test
  void mapsBooleanType() {
    ViaductSchema.Field field = getQueryField("booleanField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("Boolean");
  }

  @Test
  void mapsNonNullBooleanType() {
    ViaductSchema.Field field = getQueryField("nonNullBooleanField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("boolean");
  }

  @Test
  void mapsIdType() {
    ViaductSchema.Field field = getQueryField("idField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("String");
  }

  @Test
  void mapsCustomType() {
    ViaductSchema.Field field = getQueryField("userField");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("User");
  }

  @Test
  void mapsListType() {
    ViaductSchema.Field field = getQueryField("listOfStrings");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<String>");
  }

  @Test
  void mapsNonNullListType() {
    ViaductSchema.Field field = getQueryField("nonNullListOfStrings");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<String>");
  }

  @Test
  void mapsListOfNonNullType() {
    ViaductSchema.Field field = getQueryField("listOfNonNullStrings");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<String>");
  }

  @Test
  void mapsListOfCustomType() {
    ViaductSchema.Field field = getQueryField("listOfUsers");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<User>");
  }

  @Test
  void mapsListOfNonNullIntToBoxedType() {
    // [Int!] should map to List<Integer>, not List<int> (primitives can't be generic type params)
    ViaductSchema.Field field = getQueryField("listOfNonNullInts");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<Integer>");
  }

  @Test
  void mapsListOfNonNullFloatToBoxedType() {
    // [Float!] should map to List<Double>, not List<double>
    ViaductSchema.Field field = getQueryField("listOfNonNullFloats");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<Double>");
  }

  @Test
  void mapsListOfNonNullBooleanToBoxedType() {
    // [Boolean!] should map to List<Boolean>, not List<boolean>
    ViaductSchema.Field field = getQueryField("listOfNonNullBooleans");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<Boolean>");
  }

  @Test
  void mapsNestedListType() {
    // [[Int!]!] should map to List<List<Integer>>
    ViaductSchema.Field field = getQueryField("nestedList");

    assertThat(mapper.toJavaType(field.getType())).isEqualTo("List<List<Integer>>");
  }
}
