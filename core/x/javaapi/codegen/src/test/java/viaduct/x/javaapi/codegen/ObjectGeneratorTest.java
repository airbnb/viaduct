package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static viaduct.x.javaapi.codegen.TestStrings.countOccurrences;

import java.util.List;
import org.junit.jupiter.api.Test;

class ObjectGeneratorTest {

  @Test
  void generatesSimpleObject() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "User",
            List.of(),
            List.of(
                FieldModel.simple("id", "String", false),
                FieldModel.simple("name", "String", false),
                FieldModel.simple("email", "String", true)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("package com.example.types;"));
    assertTrue(generated.contains("public class User extends ObjectBase"));
    assertTrue(!generated.contains("implements GraphQLObject"));
    assertTrue(generated.contains("public String getIdOrThrow()"));
    assertTrue(generated.contains("return fetchScalar(\"id\", null)"));
    // Delegating instead would let a subclass declaring getIdOrThrow() change what getId() returns.
    assertTrue(generated.contains("public String getId()"));
    assertTrue(!generated.contains("return getIdOrThrow();"));
    // getFoo() is LEGACY_STRICT: same body as getFooOrThrow() while call sites migrate.
    assertEquals(2, countOccurrences(generated, "return fetchScalar(\"id\", null);"));
    assertTrue(generated.contains("public String getIdOrNull()"));
    assertTrue(
        generated.contains("return nullOnDataFailure(() -> fetchScalar(\"id\", null));"),
        generated);
    // Every form has an alias-taking overload for reads of aliased selections.
    assertTrue(generated.contains("public String getIdOrThrow(String alias)"));
    assertTrue(generated.contains("public String getId(String alias)"));
    assertTrue(generated.contains("public String getIdOrNull(String alias)"));
    assertEquals(2, countOccurrences(generated, "return fetchScalar(\"id\", alias);"));
    assertTrue(!generated.contains("private String id;"));
    assertTrue(!generated.contains("public void setId("));
    assertTrue(generated.contains("public static Builder builder(ExecutionContext context)"));
    assertTrue(generated.contains("public static class Builder"));
    assertTrue(generated.contains("public Builder toBuilder()"));
  }

  @Test
  void generatesObjectWithDescription() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Booking",
            List.of(),
            List.of(FieldModel.simple("id", "String", false)),
            "A booking for a listing.",
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("/**"));
    assertTrue(generated.contains(" * A booking for a listing."));
    assertTrue(generated.contains(" */"));
    assertTrue(generated.contains("public class Booking"));
  }

  @Test
  void generatesObjectWithInterfaces() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Human",
            List.of("Character", "Node"),
            List.of(
                FieldModel.simple("id", "String", false),
                FieldModel.simple("name", "String", false)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(
        generated.contains("public class Human extends ObjectBase implements Character, Node"));
  }

  @Test
  void generatesObjectWithComplexFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Listing",
            List.of(),
            List.of(
                new FieldModel("host", "User", false, true, false, false, false, false, "User"),
                FieldModel.simple("amenities", "List<String>", false),
                FieldModel.simple("pricePerNight", "double", false)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("public User getHostOrThrow()"));
    assertTrue(generated.contains("return fetchObject(\"host\", null, User.class, User::new)"));
    assertTrue(generated.contains("public List<String> getAmenitiesOrThrow()"));
    assertTrue(generated.contains("return fetchScalar(\"amenities\", null)"));
    assertTrue(generated.contains("public double getPricePerNightOrThrow()"));
    assertTrue(generated.contains("public User getHost()"));
    assertTrue(generated.contains("public List<String> getAmenities()"));
    assertTrue(generated.contains("public double getPricePerNight()"));
    // The soft form boxes, since a primitive cannot carry the null it returns on data failure.
    assertTrue(generated.contains("public Double getPricePerNightOrNull()"));
  }

  @Test
  void generatesObjectWithScalarListField() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Listing",
            List.of(),
            List.of(
                new FieldModel(
                    "tags", "List<String>", true, false, true, false, false, false, null)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("public List<String> getTags()"));
    assertTrue(generated.contains("return fetchScalarList(\"tags\", null)"));
  }

  @Test
  void generatesObjectWithListFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Author",
            List.of(),
            List.of(
                new FieldModel(
                    "books", "List<Book>", true, true, true, false, false, false, "Book"),
                new FieldModel("tags", "List<Tag>", true, false, true, true, false, false, "Tag")),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("public List<Book> getBooks()"));
    assertTrue(
        generated.contains("return fetchObjectList(\"books\", null, Book.class, Book::new)"));
    assertTrue(generated.contains("public List<Tag> getTags()"));
    assertTrue(generated.contains("return fetchEnumList(\"tags\", null, Tag.class)"));
  }

  @Test
  void generatesObjectWithAbstractFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "SearchContainer",
            List.of(),
            List.of(
                new FieldModel("topNode", "Node", true, false, false, false, true, false, "Node"),
                new FieldModel(
                    "topResult",
                    "SearchResult",
                    true,
                    false,
                    false,
                    false,
                    true,
                    false,
                    "SearchResult"),
                new FieldModel(
                    "allResults",
                    "List<SearchResult>",
                    false,
                    false,
                    true,
                    false,
                    true,
                    false,
                    "SearchResult")),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("public Node getTopNode()"));
    assertTrue(generated.contains("return fetchAbstractObject(\"topNode\", null, Node.class)"));
    assertTrue(generated.contains("public SearchResult getTopResult()"));
    assertTrue(
        generated.contains("return fetchAbstractObject(\"topResult\", null, SearchResult.class)"));
    assertTrue(generated.contains("public List<SearchResult> getAllResults()"));
    assertTrue(
        generated.contains(
            "return fetchAbstractObjectList(\"allResults\", null, SearchResult.class)"));
  }

  @Test
  void generatesBuilderMethods() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "User",
            List.of(),
            List.of(
                FieldModel.simple("name", "String", false),
                FieldModel.simple("age", "Integer", true)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);
    String normalized = normalizeWhitespace(generated);

    assertTrue(generated.contains("public Builder name(String name)"));
    assertTrue(generated.contains("public Builder age(Integer age)"));
    assertTrue(
        normalized.contains(
            "OutputBuilderTypeChecker.checkField( __context, \"User\", \"name\", null, name);"));
    assertTrue(generated.contains("public User build()"));
  }

  @Test
  void generatesConnectionBuilderOverridesWithConcreteReturnType() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "PostConnection",
            List.of(),
            List.of(FieldModel.simple("totalCount", "Integer", true)),
            null,
            false,
            false,
            true,
            false,
            "PostEdge",
            "Post");

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);
    String normalized = normalizeWhitespace(generated);

    assertTrue(generated.contains("extends ConnectionBuilder<PostConnection, PostEdge, Post>"));
    assertTrue(generated.contains("public static Builder builder(ExecutionContext context)"));
    assertTrue(
        generated.contains(
            "public PostConnection(InternalContext context,"
                + " RootFieldReference rootFieldReference)"));
    assertTrue(generated.contains("public Builder fromEdges(List<PostEdge> edges)"));
    assertTrue(generated.contains("super.fromEdges(edges, hasNextPage, hasPreviousPage);"));
    assertTrue(generated.contains("public <I> Builder fromSlice("));
    assertTrue(generated.contains("super.fromSlice(items, offsetLimit, hasNextPage, buildNode);"));
    assertTrue(generated.contains("public <I> Builder fromList("));
    assertTrue(generated.contains("Function<I, Post> buildNode"));
    assertTrue(normalized.contains("putField( \"totalCount\", totalCount, null);"));
  }

  @Test
  void generatesConnectionWithEnumField() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "PostConnection",
            List.of(),
            List.of(
                new FieldModel(
                    "status", "PostStatus", true, false, false, true, false, false, "PostStatus")),
            null,
            false,
            false,
            true,
            false,
            "PostEdge",
            "Post");

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);
    String normalized = normalizeWhitespace(generated);

    assertTrue(generated.contains("return fetchEnum(\"status\", null, PostStatus.class)"));
    assertTrue(normalized.contains("putField( \"status\", status, PostStatus.class);"));
  }

  @Test
  void generatesConnectionWithOrdinaryObjectFieldConversions() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "PostConnection",
            List.of(),
            List.of(
                new FieldModel(
                    "statusHistory",
                    "List<PostStatus>",
                    true,
                    false,
                    true,
                    true,
                    false,
                    false,
                    "PostStatus"),
                new FieldModel(
                    "metadata", "Metadata", true, false, false, false, true, false, "Metadata"),
                new FieldModel(
                    "metadataHistory",
                    "List<Metadata>",
                    true,
                    false,
                    true,
                    false,
                    true,
                    false,
                    "Metadata"),
                new FieldModel(
                    "labels", "List<String>", true, false, true, false, false, false, null),
                FieldModel.simple("publishedAt", "Instant", true),
                new FieldModel(
                    "publishedHistory",
                    "List<Instant>",
                    true,
                    false,
                    true,
                    false,
                    false,
                    false,
                    null),
                new FieldModel(
                    "ownerID", "GlobalID<Post>", true, false, false, false, false, true, "Post"),
                new FieldModel(
                    "ownerIDs",
                    "List<GlobalID<Post>>",
                    true,
                    false,
                    true,
                    false,
                    false,
                    true,
                    "Post")),
            null,
            false,
            false,
            true,
            false,
            "PostEdge",
            "Post");

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(
        generated.contains("return fetchEnumList(\"statusHistory\", null, PostStatus.class)"));
    assertTrue(
        generated.contains("return fetchAbstractObject(\"metadata\", null, Metadata.class)"));
    assertTrue(
        generated.contains(
            "return fetchAbstractObjectList(\"metadataHistory\", null, Metadata.class)"));
    assertTrue(generated.contains("return fetchScalarList(\"labels\", null)"));
    assertTrue(generated.contains("return fetchScalar(\"publishedAt\", null, \"DateTime\")"));
    assertTrue(
        generated.contains("return fetchScalarList(\"publishedHistory\", null, \"DateTime\")"));
    assertTrue(generated.contains("return fetchGlobalID(\"ownerID\", null)"));
    assertTrue(generated.contains("return fetchGlobalIDList(\"ownerIDs\", null)"));
    assertTrue(generated.contains("import viaduct.java.api.globalid.GlobalID;"));
    assertTrue(generated.contains("import java.time.Instant;"));
    assertTrue(generated.contains("putGlobalIDField(\"ownerID\", ownerID)"));
    assertTrue(generated.contains("putGlobalIDListField(\"ownerIDs\", ownerIDs)"));
  }

  @Test
  void generatesObjectWithTemporalScalarFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Event",
            List.of(),
            List.of(
                FieldModel.simple("createdAt", "Instant", true),
                FieldModel.simple("eventDate", "LocalDate", true),
                FieldModel.simple("startTime", "OffsetTime", true),
                FieldModel.simple("label", "String", true)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("public Instant getCreatedAt()"));
    assertTrue(generated.contains("return fetchScalar(\"createdAt\", null, \"DateTime\")"));
    assertTrue(generated.contains("public LocalDate getEventDate()"));
    assertTrue(generated.contains("return fetchScalar(\"eventDate\", null, \"Date\")"));
    assertTrue(generated.contains("public OffsetTime getStartTime()"));
    assertTrue(generated.contains("return fetchScalar(\"startTime\", null, \"Time\")"));
    assertTrue(generated.contains("public String getLabel()"));
    assertTrue(generated.contains("return fetchScalar(\"label\", null)"));
  }

  @Test
  void generatesObjectWithJsonScalarPassThrough() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Payload",
            List.of(),
            List.of(FieldModel.simple("json", "Object", true)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("public Object getJson()"));
    assertTrue(generated.contains("return fetchScalar(\"json\", null)"));
  }

  @Test
  void generatesObjectWithTemporalScalarListFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Schedule",
            List.of(),
            List.of(
                new FieldModel(
                    "timestamps", "List<Instant>", true, false, true, false, false, false, null),
                new FieldModel(
                    "dates", "List<LocalDate>", true, false, true, false, false, false, null)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("public List<Instant> getTimestamps()"));
    assertTrue(generated.contains("return fetchScalarList(\"timestamps\", null, \"DateTime\")"));
    assertTrue(generated.contains("public List<LocalDate> getDates()"));
    assertTrue(generated.contains("return fetchScalarList(\"dates\", null, \"Date\")"));
  }

  @Test
  void generatesConstructors() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "User",
            List.of(),
            List.of(FieldModel.simple("id", "String", false)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(
        generated.contains("public User(InternalContext context, EngineObjectData.Sync data)"));
    assertTrue(
        generated.contains("private User(InternalContext context, Map<String, Object> data)"));
    assertTrue(
        generated.contains(
            "public User(InternalContext context, RootFieldReference rootFieldReference)"));
    assertTrue(generated.contains("private final Map<String, Object> data = new LinkedHashMap<>"));
    assertTrue(generated.contains("return new User(__context, __base, new LinkedHashMap<>(data))"));
  }

  @Test
  void generatesReflectionAndFieldDescriptors() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Query",
            List.of(),
            List.of(
                new FieldModel(
                    "title", "String", true, false, false, false, false, false, null, null, false,
                    null, null),
                new FieldModel(
                    "viewer",
                    "User",
                    true,
                    true,
                    false,
                    false,
                    false,
                    false,
                    "User",
                    "User",
                    true,
                    "Query_Viewer_Arguments",
                    List.of("viewer"))),
            null,
            true,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(
        generated.contains(
            "public static final Type<Query> Reflection = Type.ofClass(Query.class)"));
    assertTrue(generated.contains("public static final class Fields implements TypeFields<Query>"));
    assertTrue(generated.contains("public static final Field<Query> __typename"));
    assertTrue(generated.contains("public static final Field<Query> title"));
    assertTrue(generated.contains("RootObjectField<Query, User, Query_Viewer_Arguments> viewer"));
    assertTrue(
        generated.contains(
            "RootObjectField.of(\"viewer\", Reflection, User.Reflection, List.of(\"viewer\"))"));
  }

  private static String normalizeWhitespace(String value) {
    return value.replaceAll("\\s+", " ");
  }
}
