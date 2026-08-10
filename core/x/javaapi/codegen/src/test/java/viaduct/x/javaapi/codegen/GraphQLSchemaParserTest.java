package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import graphql.schema.idl.SchemaParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import viaduct.graphql.schema.ViaductSchema;
import viaduct.graphql.utils.DefaultSchemaFactory;
import viaduct.graphql.utils.Predicates;
import viaduct.graphql.utils.TypeDefinitionRegistryExtensionsKt;

class GraphQLSchemaParserTest {

  private final GraphQLSchemaParser parser = new GraphQLSchemaParser();

  @Test
  void parsesSchemaFile() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    assertThat(schema).isNotNull();
    assertThat(schema.getTypes()).isNotEmpty();
  }

  @Test
  void extractsEnumsFromSchema() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    List<EnumModel> enums = parser.extractEnums(schema, "com.example.types");

    assertThat(enums).hasSize(5);

    // Basic enum
    EnumModel bookingStatus =
        enums.stream().filter(e -> e.className().equals("BookingStatus")).findFirst().orElseThrow();
    assertThat(bookingStatus.packageName()).isEqualTo("com.example.types");
    assertThat(bookingStatus.valueNames())
        .containsExactly("PENDING", "CONFIRMED", "CANCELLED", "COMPLETED");
    // Note: description not extracted from ViaductSchema interface
    assertThat(bookingStatus.description()).isNull();

    // Basic enum
    EnumModel listingType =
        enums.stream().filter(e -> e.className().equals("ListingType")).findFirst().orElseThrow();
    assertThat(listingType.packageName()).isEqualTo("com.example.types");
    assertThat(listingType.valueNames())
        .containsExactly("ENTIRE_PLACE", "PRIVATE_ROOM", "SHARED_ROOM", "HOTEL_ROOM");

    // Extended enum - values from base + extensions merged
    EnumModel extendableStatus =
        enums.stream()
            .filter(e -> e.className().equals("ExtendableStatus"))
            .findFirst()
            .orElseThrow();
    assertThat(extendableStatus.valueNames())
        .containsExactly("ORIGINAL_VALUE", "EXTENDED_VALUE_1", "EXTENDED_VALUE_2");

    // Enum with Java reserved keywords as values
    EnumModel javaReserved =
        enums.stream()
            .filter(e -> e.className().equals("JavaReservedKeywords"))
            .findFirst()
            .orElseThrow();
    assertThat(javaReserved.valueNames())
        .containsExactly("CLASS", "PUBLIC", "PRIVATE", "STATIC", "FINAL", "VOID");

    // Enum with lowercase values
    EnumModel lowercase =
        enums.stream().filter(e -> e.className().equals("LowercaseEnum")).findFirst().orElseThrow();
    assertThat(lowercase.valueNames()).containsExactly("active", "inactive", "pending");
  }

  @Test
  void extractsObjectsFromSchema() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    List<ObjectModel> objects = parser.extractObjects(schema, "com.example.types");

    // User, Listing, Booking, PrimitiveListTest, Review, PageInfo, SearchContainer
    assertThat(objects).hasSize(7);

    // User object (includes 5 base fields + 3 resolver fields from extend type)
    ObjectModel user =
        objects.stream().filter(o -> o.className().equals("User")).findFirst().orElseThrow();
    assertThat(user.packageName()).isEqualTo("com.example.types");
    assertThat(user.fields()).hasSize(8);

    // Check User fields (order may vary with ViaductSchema)
    assertThat(user.fields().stream().map(FieldModel::name).toList())
        .containsExactlyInAnyOrder(
            "id",
            "name",
            "email",
            "age",
            "isActive",
            "profilePicture",
            "activeBookings",
            "totalSpent");

    // Listing object with references to other types (7 base + 2 resolver fields)
    ObjectModel listing =
        objects.stream().filter(o -> o.className().equals("Listing")).findFirst().orElseThrow();
    assertThat(listing.fields()).hasSize(9);

    // Check that host field references User type
    FieldModel hostField =
        listing.fields().stream().filter(f -> f.name().equals("host")).findFirst().orElseThrow();
    assertThat(hostField.javaType()).isEqualTo("User");

    // Check that listingType field references enum
    FieldModel listingTypeField =
        listing.fields().stream()
            .filter(f -> f.name().equals("listingType"))
            .findFirst()
            .orElseThrow();
    assertThat(listingTypeField.javaType()).isEqualTo("ListingType");

    // Check list field
    FieldModel amenitiesField =
        listing.fields().stream()
            .filter(f -> f.name().equals("amenities"))
            .findFirst()
            .orElseThrow();
    assertThat(amenitiesField.javaType()).isEqualTo("List<String>");

    // Booking object - now has createdAt and updatedAt from implementing Timestamped
    ObjectModel booking =
        objects.stream().filter(o -> o.className().equals("Booking")).findFirst().orElseThrow();
    assertThat(booking.fields()).hasSize(9); // 7 original + createdAt + updatedAt
  }

  @Test
  void extractsReflectionMetadataForRootAndCompositeFields() throws IOException {
    ViaductSchema schema =
        parser.parse(
            new StringReader(
                """
                directive @namespaceType on OBJECT
                type Query {
                  products: Products!
                }
                type Products @namespaceType {
                  detail(id: ID): Product
                  featured: [Product!]!
                }
                type Product {
                  id: ID!
                }
                """));

    List<ObjectModel> objects =
        parser.extractObjects(schema, "com.example.types", /* includeRootTypes= */ true);
    ObjectModel query =
        objects.stream().filter(o -> o.className().equals("Query")).findFirst().orElseThrow();
    ObjectModel products =
        objects.stream().filter(o -> o.className().equals("Products")).findFirst().orElseThrow();

    FieldModel productsField = query.fields().get(0);
    assertThat(productsField.rootObjectField()).isTrue();
    assertThat(productsField.argumentsTypeName()).isEqualTo("Arguments.NoArguments");
    assertThat(productsField.pathFromQueryRoot()).containsExactly("products");

    FieldModel detail =
        products.fields().stream().filter(f -> f.name().equals("detail")).findFirst().orElseThrow();
    assertThat(detail.reflectedTypeName()).isEqualTo("Product");
    assertThat(detail.rootObjectField()).isTrue();
    assertThat(detail.argumentsTypeName()).isEqualTo("Products_Detail_Arguments");
    assertThat(detail.pathFromQueryRoot()).containsExactly("products", "detail");

    FieldModel featured =
        products.fields().stream()
            .filter(f -> f.name().equals("featured"))
            .findFirst()
            .orElseThrow();
    assertThat(featured.reflectedTypeName()).isEqualTo("Product");
    assertThat(featured.rootObjectField()).isFalse();

    ArgumentModel detailArguments =
        parser.extractArguments(schema, "com.example.types", null).get(0);
    assertThat(detailArguments.className()).isEqualTo("Products_Detail_Arguments");
    assertThat(detailArguments.containingTypeName()).isEqualTo("Products");
    assertThat(detailArguments.fieldName()).isEqualTo("detail");
  }

  @Test
  void keepsSyntheticConnectionGettersOutOfArgumentReflection() throws IOException {
    ViaductSchema schema =
        parser.parse(
            new StringReader(
                """
                directive @connection on OBJECT
                type Query {
                  forward(first: Int!): ResultConnection
                  backward(last: Int!): ResultConnection
                }
                type ResultConnection @connection {
                  edges: [ResultEdge]
                }
                type ResultEdge {
                  cursor: String!
                }
                """));

    List<ArgumentModel> arguments = parser.extractArguments(schema, "com.example.types", null);
    ArgumentModel forward =
        arguments.stream()
            .filter(argument -> argument.className().equals("Query_Forward_Arguments"))
            .findFirst()
            .orElseThrow();
    ArgumentModel backward =
        arguments.stream()
            .filter(argument -> argument.className().equals("Query_Backward_Arguments"))
            .findFirst()
            .orElseThrow();

    assertThat(forward.getReflectedFields()).extracting(FieldModel::name).containsExactly("first");
    assertThat(forward.synthesizedConnectionFields())
        .extracting(FieldModel::name)
        .containsExactly("after");
    assertThat(backward.getReflectedFields()).extracting(FieldModel::name).containsExactly("last");
    assertThat(backward.synthesizedConnectionFields())
        .extracting(FieldModel::name)
        .containsExactly("before");

    assertThat(JavaGRTGenerator.ArgumentGenerator.generate(forward))
        .contains(
            "public String getAfter()",
            "return null;",
            "public static Builder builder(ExecutionContext context)",
            "public Builder first(Integer first)",
            "public Query_Forward_Arguments build()")
        .doesNotContain("Field<Query_Forward_Arguments> after");
    assertThat(JavaGRTGenerator.ArgumentGenerator.generate(backward))
        .contains("public String getBefore()", "return null;")
        .doesNotContain("Field<Query_Backward_Arguments> before");
  }

  @Test
  void reflectsBackingDataWithoutGeneratingJavaAccessors() throws IOException {
    ViaductSchema schema =
        parser.parse(
            new StringReader(
                """
                scalar BackingData
                type User {
                  name: String!
                  internalState: BackingData
                }
                """));

    ObjectModel user = parser.extractObjects(schema, "com.example.types").get(0);

    assertThat(user.fields()).extracting(FieldModel::name).containsExactly("name");
    assertThat(user.reflectedFields())
        .extracting(FieldModel::name)
        .containsExactly("name", "internalState");

    String generated = JavaGRTGenerator.ObjectGenerator.generate(user);
    assertThat(generated)
        .contains(
            "public static final Field<User> internalState",
            "Field.of(\"internalState\", Reflection)")
        .doesNotContain("getInternalState()", "Builder internalState(");
  }

  @Test
  void extractsAbstractTypedFields() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    List<ObjectModel> objects = parser.extractObjects(schema, "com.example.types");

    ObjectModel searchContainer =
        objects.stream()
            .filter(o -> o.className().equals("SearchContainer"))
            .findFirst()
            .orElseThrow();
    assertThat(searchContainer.fields()).hasSize(3);

    // Interface-typed field
    FieldModel topNode =
        searchContainer.fields().stream()
            .filter(f -> f.name().equals("topNode"))
            .findFirst()
            .orElseThrow();
    assertThat(topNode.abstractType()).isTrue();
    assertThat(topNode.compositeType()).isFalse();
    assertThat(topNode.baseTypeName()).isEqualTo("Node");

    // Union-typed field
    FieldModel topResult =
        searchContainer.fields().stream()
            .filter(f -> f.name().equals("topResult"))
            .findFirst()
            .orElseThrow();
    assertThat(topResult.abstractType()).isTrue();
    assertThat(topResult.compositeType()).isFalse();
    assertThat(topResult.baseTypeName()).isEqualTo("SearchResult");

    // Union-typed list field
    FieldModel allResults =
        searchContainer.fields().stream()
            .filter(f -> f.name().equals("allResults"))
            .findFirst()
            .orElseThrow();
    assertThat(allResults.abstractType()).isTrue();
    assertThat(allResults.list()).isTrue();
    assertThat(allResults.baseTypeName()).isEqualTo("SearchResult");
  }

  @Test
  void extractsInputsFromSchema() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    List<InputModel> inputs = parser.extractInputs(schema, "com.example.types");

    assertThat(inputs).hasSize(5);

    // CreateUserInput
    InputModel createUserInput =
        inputs.stream()
            .filter(i -> i.className().equals("CreateUserInput"))
            .findFirst()
            .orElseThrow();
    assertThat(createUserInput.packageName()).isEqualTo("com.example.types");
    assertThat(createUserInput.fields()).hasSize(3);
    assertThat(createUserInput.fields().stream().map(FieldModel::name).toList())
        .containsExactlyInAnyOrder("name", "email", "age");

    // CreateBookingInput
    InputModel createBookingInput =
        inputs.stream()
            .filter(i -> i.className().equals("CreateBookingInput"))
            .findFirst()
            .orElseThrow();
    assertThat(createBookingInput.fields()).hasSize(6);

    // SearchFiltersInput - has enum reference and list field
    InputModel searchFiltersInput =
        inputs.stream()
            .filter(i -> i.className().equals("SearchFiltersInput"))
            .findFirst()
            .orElseThrow();
    FieldModel listingTypeField =
        searchFiltersInput.fields().stream()
            .filter(f -> f.name().equals("listingType"))
            .findFirst()
            .orElseThrow();
    assertThat(listingTypeField.javaType()).isEqualTo("ListingType");

    FieldModel amenitiesField =
        searchFiltersInput.fields().stream()
            .filter(f -> f.name().equals("amenities"))
            .findFirst()
            .orElseThrow();
    assertThat(amenitiesField.javaType()).isEqualTo("List<String>");

    // ExtendableInput - extended input
    InputModel extendableInput =
        inputs.stream()
            .filter(i -> i.className().equals("ExtendableInput"))
            .findFirst()
            .orElseThrow();
    assertThat(extendableInput.fields()).hasSize(2);
    assertThat(extendableInput.fields().stream().map(FieldModel::name).toList())
        .containsExactlyInAnyOrder("baseField", "extendedField");
  }

  @Test
  void extractsInterfacesFromSchema() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    List<InterfaceModel> interfaces = parser.extractInterfaces(schema, "com.example.types");

    assertThat(interfaces).hasSize(4);

    // Node interface - simple interface
    InterfaceModel node =
        interfaces.stream().filter(i -> i.className().equals("Node")).findFirst().orElseThrow();
    assertThat(node.packageName()).isEqualTo("com.example.types");
    assertThat(node.fields()).hasSize(1);
    assertThat(node.extendedInterfaces()).isEmpty();

    FieldModel idField =
        node.fields().stream().filter(f -> f.name().equals("id")).findFirst().orElseThrow();
    assertThat(idField.javaType()).isEqualTo("GlobalID<? extends Node>");
    assertThat(idField.globalIDType()).isTrue();

    // Timestamped interface
    InterfaceModel timestamped =
        interfaces.stream()
            .filter(i -> i.className().equals("Timestamped"))
            .findFirst()
            .orElseThrow();
    assertThat(timestamped.fields()).hasSize(2);
    assertThat(timestamped.extendedInterfaces()).isEmpty();

    // Auditable interface - extends both Node and Timestamped
    InterfaceModel auditable =
        interfaces.stream()
            .filter(i -> i.className().equals("Auditable"))
            .findFirst()
            .orElseThrow();
    assertThat(auditable.extendedInterfaces()).containsExactlyInAnyOrder("Node", "Timestamped");
    assertThat(auditable.fields()).hasSize(4);

    // ExtendableInterface - extended interface
    InterfaceModel extendableInterface =
        interfaces.stream()
            .filter(i -> i.className().equals("ExtendableInterface"))
            .findFirst()
            .orElseThrow();
    assertThat(extendableInterface.fields()).hasSize(2);
    assertThat(extendableInterface.fields().stream().map(FieldModel::name).toList())
        .containsExactlyInAnyOrder("baseField", "extendedField");
  }

  @Test
  void extractsObjectsWithImplementedInterfaces() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    List<ObjectModel> objects = parser.extractObjects(schema, "com.example.types");

    // User implements Node + SearchResult, ExtendableUnion, NodeResult (union membership)
    ObjectModel user =
        objects.stream().filter(o -> o.className().equals("User")).findFirst().orElseThrow();
    assertThat(user.implementedInterfaces())
        .containsExactlyInAnyOrder("Node", "SearchResult", "ExtendableUnion", "NodeResult");

    // Listing implements Node + SearchResult, ExtendableUnion, NodeResult (union membership)
    ObjectModel listing =
        objects.stream().filter(o -> o.className().equals("Listing")).findFirst().orElseThrow();
    assertThat(listing.implementedInterfaces())
        .containsExactlyInAnyOrder("Node", "SearchResult", "ExtendableUnion", "NodeResult");

    // Booking implements Node & Timestamped + SearchResult, ExtendableUnion (union membership)
    ObjectModel booking =
        objects.stream().filter(o -> o.className().equals("Booking")).findFirst().orElseThrow();
    assertThat(booking.implementedInterfaces())
        .containsExactlyInAnyOrder("Node", "Timestamped", "SearchResult", "ExtendableUnion");
  }

  @Test
  void extractsUnionsFromSchema() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    List<UnionModel> unions = parser.extractUnions(schema, "com.example.types");

    assertThat(unions).hasSize(3);

    // SearchResult union - basic union
    UnionModel searchResult =
        unions.stream().filter(u -> u.className().equals("SearchResult")).findFirst().orElseThrow();
    assertThat(searchResult.packageName()).isEqualTo("com.example.types");
    assertThat(searchResult.memberTypes()).containsExactlyInAnyOrder("User", "Listing", "Booking");

    // ExtendableUnion - extended union
    UnionModel extendableUnion =
        unions.stream()
            .filter(u -> u.className().equals("ExtendableUnion"))
            .findFirst()
            .orElseThrow();
    assertThat(extendableUnion.memberTypes())
        .containsExactlyInAnyOrder("User", "Listing", "Booking");

    // NodeResult - simple union without description in comments
    UnionModel nodeResult =
        unions.stream().filter(u -> u.className().equals("NodeResult")).findFirst().orElseThrow();
    assertThat(nodeResult.memberTypes()).containsExactlyInAnyOrder("User", "Listing");
  }

  @Test
  void primitiveTypesInListsAreBoxed() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    // Test object type with primitive lists
    List<ObjectModel> objects = parser.extractObjects(schema, "com.example.types");
    ObjectModel primitiveListTest =
        objects.stream()
            .filter(o -> o.className().equals("PrimitiveListTest"))
            .findFirst()
            .orElseThrow();

    // Verify [Int!]! maps to List<Integer>, not List<int>
    FieldModel scoresField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("scores"))
            .findFirst()
            .orElseThrow();
    assertThat(scoresField.javaType())
        .as("[Int!]! should map to List<Integer>, not List<int>")
        .isEqualTo("List<Integer>");

    // Verify [Float!]! maps to List<Double>, not List<double>
    FieldModel pricesField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("prices"))
            .findFirst()
            .orElseThrow();
    assertThat(pricesField.javaType())
        .as("[Float!]! should map to List<Double>, not List<double>")
        .isEqualTo("List<Double>");

    // Verify [Boolean!]! maps to List<Boolean>, not List<boolean>
    FieldModel flagsField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("flags"))
            .findFirst()
            .orElseThrow();
    assertThat(flagsField.javaType())
        .as("[Boolean!]! should map to List<Boolean>, not List<boolean>")
        .isEqualTo("List<Boolean>");

    // Verify [[Int!]!] maps to List<List<Integer>>
    FieldModel matrixField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("matrix"))
            .findFirst()
            .orElseThrow();
    assertThat(matrixField.javaType())
        .as("[[Int!]!] should map to List<List<Integer>>")
        .isEqualTo("List<List<Integer>>");

    // Verify non-null primitives remain primitives (not boxed)
    FieldModel countField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("count"))
            .findFirst()
            .orElseThrow();
    assertThat(countField.javaType())
        .as("Int! should map to int primitive, not Integer")
        .isEqualTo("int");

    FieldModel rateField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("rate"))
            .findFirst()
            .orElseThrow();
    assertThat(rateField.javaType())
        .as("Float! should map to double primitive, not Double")
        .isEqualTo("double");

    FieldModel enabledField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("enabled"))
            .findFirst()
            .orElseThrow();
    assertThat(enabledField.javaType())
        .as("Boolean! should map to boolean primitive, not Boolean")
        .isEqualTo("boolean");

    // Verify nullable primitives are boxed (primitives can't be null in Java)
    FieldModel nullableCountField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("nullableCount"))
            .findFirst()
            .orElseThrow();
    assertThat(nullableCountField.javaType())
        .as("Int (nullable) should map to Integer")
        .isEqualTo("Integer");

    FieldModel nullableRateField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("nullableRate"))
            .findFirst()
            .orElseThrow();
    assertThat(nullableRateField.javaType())
        .as("Float (nullable) should map to Double")
        .isEqualTo("Double");

    FieldModel nullableEnabledField =
        primitiveListTest.fields().stream()
            .filter(f -> f.name().equals("nullableEnabled"))
            .findFirst()
            .orElseThrow();
    assertThat(nullableEnabledField.javaType())
        .as("Boolean (nullable) should map to Boolean")
        .isEqualTo("Boolean");

    // Test input type with primitive lists
    List<InputModel> inputs = parser.extractInputs(schema, "com.example.types");
    InputModel primitiveListInput =
        inputs.stream()
            .filter(i -> i.className().equals("PrimitiveListInput"))
            .findFirst()
            .orElseThrow();

    FieldModel valuesField =
        primitiveListInput.fields().stream()
            .filter(f -> f.name().equals("values"))
            .findFirst()
            .orElseThrow();
    assertThat(valuesField.javaType())
        .as("Input [Int!]! should map to List<Integer>")
        .isEqualTo("List<Integer>");

    FieldModel ratiosField =
        primitiveListInput.fields().stream()
            .filter(f -> f.name().equals("ratios"))
            .findFirst()
            .orElseThrow();
    assertThat(ratiosField.javaType())
        .as("Input [Float!] should map to List<Double>")
        .isEqualTo("List<Double>");

    FieldModel optionsField =
        primitiveListInput.fields().stream()
            .filter(f -> f.name().equals("options"))
            .findFirst()
            .orElseThrow();
    assertThat(optionsField.javaType())
        .as("Input [Boolean!] should map to List<Boolean>")
        .isEqualTo("List<Boolean>");
  }

  @Test
  void extractsResolversFromSchema() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, "com.example.types", "Mutation");

    // Shared default schema contributes Query.node and Query.nodes.
    assertThat(resolversByType).hasSize(4);
    assertThat(resolversByType).containsKeys("Query", "User", "Listing", "Mutation");
    assertThat(resolversByType.get("Query").stream().map(ResolverModel::gqlFieldName))
        .containsExactlyInAnyOrder("node", "nodes");
  }

  @Test
  void extractsUserResolvers() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, "com.example.types", "Mutation");

    List<ResolverModel> userResolvers = resolversByType.get("User");
    assertThat(userResolvers).hasSize(3);

    // profilePicture resolver - no arguments, scalar output
    ResolverModel profilePicture =
        userResolvers.stream()
            .filter(r -> r.gqlFieldName().equals("profilePicture"))
            .findFirst()
            .orElseThrow();
    assertThat(profilePicture.gqlTypeName()).isEqualTo("User");
    assertThat(profilePicture.resolverClassName()).isEqualTo("ProfilePicture");
    assertThat(profilePicture.returnType()).isEqualTo("String");
    assertThat(profilePicture.objectType()).isEqualTo("com.example.types.User");
    assertThat(profilePicture.queryType()).isEqualTo("com.example.types.Query");
    assertThat(profilePicture.argumentsType()).isEqualTo("Arguments.NoArguments");
    assertThat(profilePicture.hasArguments()).isFalse();
    assertThat(profilePicture.isSelective()).isTrue();
    assertThat(profilePicture.isBatching()).isFalse();

    // activeBookings resolver - list return type
    ResolverModel activeBookings =
        userResolvers.stream()
            .filter(r -> r.gqlFieldName().equals("activeBookings"))
            .findFirst()
            .orElseThrow();
    assertThat(activeBookings.returnType()).isEqualTo("List<com.example.types.Booking>");
    assertThat(activeBookings.isSelective()).isFalse();
    assertThat(activeBookings.isBatching()).isFalse();

    // totalSpent resolver - non-null Float (boxed for use in CompletableFuture<T>)
    ResolverModel totalSpent =
        userResolvers.stream()
            .filter(r -> r.gqlFieldName().equals("totalSpent"))
            .findFirst()
            .orElseThrow();
    assertThat(totalSpent.returnType()).isEqualTo("Double");
  }

  @Test
  void extractsSelectiveResolversWithLegacyDirectiveArg() throws IOException {
    ViaductSchema schema =
        parser.parse(
            new StringReader(
                """
                directive @resolver(selective: Boolean! = false) on OBJECT | FIELD_DEFINITION

                type Query {
                  user: User @resolver(selective: true)
                }

                type User {
                  id: ID!
                }
                """));

    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, "com.example.types", null);

    ResolverModel userResolver = resolversByType.get("Query").get(0);
    assertThat(userResolver.gqlFieldName()).isEqualTo("user");
    assertThat(userResolver.isSelective()).isTrue();
  }

  @Test
  void rejectsSelectiveOnMutationNamespaceFields() throws IOException {
    // A selective resolver on a @namespaceType object reachable from the mutation root must be
    // rejected at codegen time, matching the Kotlin codegen (mutations execute on the root type
    // and its reachable namespace types).
    ViaductSchema schema =
        parser.parse(
            new StringReader(
                """
                directive @resolver(isSelective: Boolean! = false) on OBJECT | FIELD_DEFINITION
                directive @namespaceType on OBJECT

                type Query {
                  placeholder: Int
                }

                type Mutation {
                  stayFoo: StayFooMutations
                }

                type StayFooMutations @namespaceType {
                  doThing(id: ID!): String @resolver(isSelective: true)
                }
                """));

    assertThatThrownBy(() -> parser.extractResolvers(schema, "com.example.types", "Mutation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("StayFooMutations.doThing");
  }

  @Test
  void rejectsBatchingOnMutationNamespaceFields() throws IOException {
    ViaductSchema schema =
        parser.parse(
            new StringReader(
                """
                directive @resolver(isBatching: Boolean! = false) on OBJECT | FIELD_DEFINITION
                directive @namespaceType on OBJECT

                type Query {
                  placeholder: Int
                }

                type Mutation {
                  stayFoo: StayFooMutations
                }

                type StayFooMutations @namespaceType {
                  doThing(id: ID!): String @resolver(isBatching: true)
                }
                """));

    assertThatThrownBy(() -> parser.extractResolvers(schema, "com.example.types", "Mutation"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("StayFooMutations.doThing");
  }

  @Test
  void extractsListingResolversWithArguments() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, "com.example.types", "Mutation");

    List<ResolverModel> listingResolvers = resolversByType.get("Listing");
    assertThat(listingResolvers).hasSize(2);

    // availability resolver - has arguments
    ResolverModel availability =
        listingResolvers.stream()
            .filter(r -> r.gqlFieldName().equals("availability"))
            .findFirst()
            .orElseThrow();
    assertThat(availability.hasArguments()).isTrue();
    assertThat(availability.argumentsType())
        .isEqualTo("com.example.types.Listing_Availability_Arguments");
    assertThat(availability.returnType()).isEqualTo("List<String>");

    // reviews resolver - no arguments
    ResolverModel reviews =
        listingResolvers.stream()
            .filter(r -> r.gqlFieldName().equals("reviews"))
            .findFirst()
            .orElseThrow();
    assertThat(reviews.hasArguments()).isFalse();
    assertThat(reviews.argumentsType()).isEqualTo("Arguments.NoArguments");
  }

  @Test
  void excludesBatchResolveForMutationResolvers() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, "com.example.types", "Mutation");

    List<ResolverModel> mutationResolvers = resolversByType.get("Mutation");
    assertThat(mutationResolvers).hasSize(1);

    ResolverModel sendNotification =
        mutationResolvers.stream()
            .filter(r -> r.gqlFieldName().equals("sendNotification"))
            .findFirst()
            .orElseThrow();
    assertThat(sendNotification.gqlTypeName()).isEqualTo("Mutation");
    assertThat(sendNotification.isBatching())
        .as("Mutation resolvers should not use batching")
        .isFalse();
    assertThat(sendNotification.hasArguments()).isTrue();
  }

  @Test
  void resolverModelPreFormattedTypeStrings() throws IOException {
    ViaductSchema schema = parser.parse(getTestSchemaReader());

    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, "com.example.types", "Mutation");

    ResolverModel profilePicture =
        resolversByType.get("User").stream()
            .filter(r -> r.gqlFieldName().equals("profilePicture"))
            .findFirst()
            .orElseThrow();

    // Test pre-formatted type strings used by the template
    assertThat(profilePicture.getFieldResolverBaseType())
        .isEqualTo(
            "FieldResolverBase<String, com.example.types.User, com.example.types.Query,"
                + " Arguments.NoArguments, CompositeOutput.None>");
    assertThat(profilePicture.getContextBaseType())
        .isEqualTo(
            "FieldResolverBase.Context<com.example.types.User, com.example.types.Query,"
                + " Arguments.NoArguments, CompositeOutput.None>");
    assertThat(profilePicture.getFieldExecutionContextType())
        .isEqualTo(
            "FieldExecutionContext<com.example.types.User, com.example.types.Query,"
                + " Arguments.NoArguments, CompositeOutput.None>");
    assertThat(profilePicture.getResolveFutureType()).isEqualTo("CompletableFuture<String>");
    assertThat(profilePicture.getBatchResolveFutureType())
        .isEqualTo("CompletableFuture<Map<Context, String>>");
    assertThat(profilePicture.getBatchResolveContextListType()).isEqualTo("List<Context>");
  }

  @Test
  void connectionResolverWithoutArgumentsUsesOrdinaryResolverBase() throws IOException {
    ResolverModel resolver = connectionResolversWithoutPagination().get("unpagedItems");

    assertThat(resolver.getFieldResolverBaseType())
        .startsWith("FieldResolverBase<")
        .contains("Arguments.NoArguments");
  }

  @Test
  void connectionResolverWithOnlyNonPaginationArgumentsUsesOrdinaryResolverBase()
      throws IOException {
    ResolverModel resolver = connectionResolversWithoutPagination().get("filteredItems");

    assertThat(resolver.getFieldResolverBaseType())
        .startsWith("FieldResolverBase<")
        .contains("com.example.types.Query_FilteredItems_Arguments");
  }

  private Map<String, ResolverModel> connectionResolversWithoutPagination() throws IOException {
    ViaductSchema schema =
        parseWithDefaults(
            """
            type Item {
              id: ID!
            }

            type ItemEdge @edge {
              node: Item
              cursor: String!
            }

            type ItemConnection @connection {
              edges: [ItemEdge!]!
              pageInfo: PageInfo!
            }

            extend type Query {
              unpagedItems: ItemConnection @resolver
              filteredItems(filter: String): ItemConnection @resolver
            }
            """);

    return parser.extractResolvers(schema, "com.example.types", null).get("Query").stream()
        .collect(Collectors.toMap(ResolverModel::gqlFieldName, model -> model));
  }

  @Test
  void returnsEmptyMapWhenNoResolversInSchema() throws IOException {
    // Use a minimal schema with no resolver directives
    String minimalSchema =
        """
        type Query {
          hello: String
        }
        type User {
          id: ID!
          name: String!
        }
        """;
    ViaductSchema schema = parser.parse(new StringReader(minimalSchema));

    Map<String, List<ResolverModel>> resolversByType =
        parser.extractResolvers(schema, "com.example.types", null);

    assertThat(resolversByType).isEmpty();
  }

  private Reader getTestSchemaReader() {
    InputStream inputStream =
        Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream("test-schema.graphqls"));
    String sdl;
    try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
      sdl =
          TypeDefinitionRegistryExtensionsKt.toSDL(
              withDefaults(new SchemaParser().parse(readAll(reader))),
              Predicates.INSTANCE.alwaysTrue(),
              Predicates.INSTANCE.alwaysTrue());
    } catch (IOException e) {
      throw new RuntimeException("Failed to load test-schema.graphqls", e);
    }
    return new StringReader(sdl);
  }

  private ViaductSchema parseWithDefaults(String sdl) throws IOException {
    var registry = withDefaults(new SchemaParser().parse(sdl));
    return parser.parse(
        new StringReader(
            TypeDefinitionRegistryExtensionsKt.toSDL(
                registry, Predicates.INSTANCE.alwaysTrue(), Predicates.INSTANCE.alwaysTrue())));
  }

  private static graphql.schema.idl.TypeDefinitionRegistry withDefaults(
      graphql.schema.idl.TypeDefinitionRegistry registry) {
    DefaultSchemaFactory.INSTANCE.addDefaults(
        registry,
        DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
        DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
        false,
        false,
        false);
    return registry;
  }

  private static String readAll(Reader reader) throws IOException {
    StringWriter sw = new StringWriter();
    reader.transferTo(sw);
    return sw.toString();
  }
}
