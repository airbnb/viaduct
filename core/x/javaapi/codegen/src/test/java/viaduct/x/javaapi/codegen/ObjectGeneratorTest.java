package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    assertTrue(generated.contains("package com.example.types;"), generated);
    assertTrue(generated.contains("public class User extends ObjectBase"), generated);
    assertFalse(generated.contains("implements GraphQLObject"), generated);
    assertTrue(generated.contains("public String getIdOrThrow()"), generated);
    assertTrue(generated.contains("return fetchScalar(\"id\", null)"), generated);
    // Delegating instead would let a subclass declaring getIdOrThrow() change what getId() returns.
    assertTrue(generated.contains("public String getId()"), generated);
    assertFalse(generated.contains("return getIdOrThrow();"), generated);
    // Only the OrThrow form reads strictly; the bare form goes through nullOnDataFailure.
    assertEquals(1, countOccurrences(generated, "return fetchScalar(\"id\", null);"), generated);
    assertFalse(generated.contains("public String getIdOrNull()"), generated);
    assertEquals(
        1,
        countOccurrences(generated, "return nullOnDataFailure(() -> fetchScalar(\"id\", null));"),
        generated);
    // Both forms have an alias-taking overload for reads of aliased selections.
    assertTrue(generated.contains("public String getIdOrThrow(String alias)"), generated);
    assertTrue(generated.contains("public String getId(String alias)"), generated);
    assertFalse(generated.contains("public String getIdOrNull(String alias)"), generated);
    assertEquals(1, countOccurrences(generated, "return fetchScalar(\"id\", alias);"), generated);
    assertEquals(
        1,
        countOccurrences(generated, "return nullOnDataFailure(() -> fetchScalar(\"id\", alias));"),
        generated);
    assertFalse(generated.contains("private String id;"), generated);
    assertFalse(generated.contains("public void setId("), generated);
    assertTrue(
        generated.contains("public static Builder builder(ExecutionContext context)"), generated);
    assertTrue(generated.contains("public static class Builder"), generated);
    assertTrue(generated.contains("public Builder toBuilder()"), generated);
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

    assertTrue(generated.contains("/**"), generated);
    assertTrue(generated.contains(" * A booking for a listing."), generated);
    assertTrue(generated.contains(" */"), generated);
    assertTrue(generated.contains("public class Booking"), generated);
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
        generated.contains("public class Human extends ObjectBase implements Character, Node"),
        generated);
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

    assertTrue(generated.contains("public User getHostOrThrow()"), generated);
    assertTrue(
        generated.contains("return fetchObject(\"host\", null, User.class, User::new)"), generated);
    assertTrue(generated.contains("public List<String> getAmenitiesOrThrow()"), generated);
    assertTrue(generated.contains("return fetchScalar(\"amenities\", null)"), generated);
    assertTrue(generated.contains("public double getPricePerNightOrThrow()"), generated);
    assertTrue(generated.contains("public User getHost()"), generated);
    assertTrue(generated.contains("public List<String> getAmenities()"), generated);
    // The soft form boxes, since a primitive cannot carry the null it returns on data failure.
    assertTrue(generated.contains("public Double getPricePerNight()"), generated);
    assertFalse(generated.contains("public Double getPricePerNightOrNull()"), generated);
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

    assertTrue(generated.contains("public List<String> getTags()"), generated);
    assertTrue(generated.contains("return fetchScalarList(\"tags\", null)"), generated);
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

    assertTrue(generated.contains("public List<Book> getBooks()"), generated);
    assertTrue(
        generated.contains("return fetchObjectList(\"books\", null, Book.class, Book::new)"),
        generated);
    assertTrue(generated.contains("public List<Tag> getTags()"), generated);
    assertTrue(generated.contains("return fetchEnumList(\"tags\", null, Tag.class)"), generated);
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

    assertTrue(generated.contains("public Node getTopNode()"), generated);
    assertTrue(
        generated.contains("return fetchAbstractObject(\"topNode\", null, Node.class)"), generated);
    assertTrue(generated.contains("public SearchResult getTopResult()"), generated);
    assertTrue(
        generated.contains("return fetchAbstractObject(\"topResult\", null, SearchResult.class)"),
        generated);
    assertTrue(generated.contains("public List<SearchResult> getAllResults()"), generated);
    assertTrue(
        generated.contains(
            "return fetchAbstractObjectList(\"allResults\", null, SearchResult.class)"),
        generated);
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

    assertTrue(generated.contains("public Builder name(String name)"), generated);
    assertTrue(generated.contains("public Builder age(Integer age)"), generated);
    assertTrue(
        normalized.contains(
            "OutputBuilderTypeChecker.checkField( __context, \"User\", \"name\", null, name);"),
        normalized);
    assertTrue(generated.contains("public User build()"), generated);
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

    assertTrue(
        generated.contains("extends ConnectionBuilder<PostConnection, PostEdge, Post>"), generated);
    assertTrue(
        generated.contains("public static Builder builder(ExecutionContext context)"), generated);
    assertTrue(
        generated.contains(
            "public PostConnection(InternalContext context,"
                + " RootFieldReference rootFieldReference)"),
        generated);
    assertTrue(generated.contains("public Builder fromEdges(List<PostEdge> edges)"), generated);
    assertTrue(
        generated.contains("super.fromEdges(edges, hasNextPage, hasPreviousPage);"), generated);
    assertTrue(generated.contains("public <I> Builder fromSlice("), generated);
    assertTrue(
        generated.contains("super.fromSlice(items, offsetLimit, hasNextPage, buildNode);"),
        generated);
    assertTrue(generated.contains("public <I> Builder fromList("), generated);
    assertTrue(generated.contains("Function<I, Post> buildNode"), generated);
    assertTrue(normalized.contains("putField( \"totalCount\", totalCount, null);"), normalized);
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

    assertTrue(
        generated.contains("return fetchEnum(\"status\", null, PostStatus.class)"), generated);
    assertTrue(normalized.contains("putField( \"status\", status, PostStatus.class);"), normalized);
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
        generated.contains("return fetchEnumList(\"statusHistory\", null, PostStatus.class)"),
        generated);
    assertTrue(
        generated.contains("return fetchAbstractObject(\"metadata\", null, Metadata.class)"),
        generated);
    assertTrue(
        generated.contains(
            "return fetchAbstractObjectList(\"metadataHistory\", null, Metadata.class)"),
        generated);
    assertTrue(generated.contains("return fetchScalarList(\"labels\", null)"), generated);
    assertTrue(
        generated.contains("return fetchScalar(\"publishedAt\", null, \"DateTime\")"), generated);
    assertTrue(
        generated.contains("return fetchScalarList(\"publishedHistory\", null, \"DateTime\")"),
        generated);
    assertTrue(generated.contains("return fetchGlobalID(\"ownerID\", null)"), generated);
    assertTrue(generated.contains("return fetchGlobalIDList(\"ownerIDs\", null)"), generated);
    assertTrue(generated.contains("import viaduct.java.api.globalid.GlobalID;"), generated);
    assertTrue(generated.contains("import java.time.Instant;"), generated);
    assertTrue(generated.contains("putGlobalIDField(\"ownerID\", ownerID)"), generated);
    assertTrue(generated.contains("putGlobalIDListField(\"ownerIDs\", ownerIDs)"), generated);
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

    assertTrue(generated.contains("public Instant getCreatedAt()"), generated);
    assertTrue(
        generated.contains("return fetchScalar(\"createdAt\", null, \"DateTime\")"), generated);
    assertTrue(generated.contains("public LocalDate getEventDate()"), generated);
    assertTrue(generated.contains("return fetchScalar(\"eventDate\", null, \"Date\")"), generated);
    assertTrue(generated.contains("public OffsetTime getStartTime()"), generated);
    assertTrue(generated.contains("return fetchScalar(\"startTime\", null, \"Time\")"), generated);
    assertTrue(generated.contains("public String getLabel()"), generated);
    assertTrue(generated.contains("return fetchScalar(\"label\", null)"), generated);
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

    assertTrue(generated.contains("public Object getJson()"), generated);
    assertTrue(generated.contains("return fetchScalar(\"json\", null)"), generated);
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

    assertTrue(generated.contains("public List<Instant> getTimestamps()"), generated);
    assertTrue(
        generated.contains("return fetchScalarList(\"timestamps\", null, \"DateTime\")"),
        generated);
    assertTrue(generated.contains("public List<LocalDate> getDates()"), generated);
    assertTrue(generated.contains("return fetchScalarList(\"dates\", null, \"Date\")"), generated);
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
        generated.contains("public User(InternalContext context, EngineObjectData.Sync data)"),
        generated);
    assertTrue(
        generated.contains("private User(InternalContext context, Map<String, Object> data)"),
        generated);
    assertTrue(
        generated.contains(
            "public User(InternalContext context, RootFieldReference rootFieldReference)"),
        generated);
    assertTrue(
        generated.contains("private final Map<String, Object> data = new LinkedHashMap<>"),
        generated);
    assertTrue(
        generated.contains("return new User(__context, __base, new LinkedHashMap<>(data))"),
        generated);
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
            "public static final Type<Query> Reflection = Type.ofClass(Query.class)"),
        generated);
    assertTrue(
        generated.contains("public static final class Fields implements TypeFields<Query>"),
        generated);
    assertTrue(generated.contains("public static final Field<Query> __typename"), generated);
    assertTrue(generated.contains("public static final Field<Query> title"), generated);
    assertTrue(
        generated.contains("RootObjectField<Query, User, Query_Viewer_Arguments> viewer"),
        generated);
    assertTrue(
        generated.contains(
            "RootObjectField.of(\"viewer\", Reflection, User.Reflection, List.of(\"viewer\"))"),
        generated);
  }

  private static String normalizeWhitespace(String value) {
    return value.replaceAll("\\s+", " ");
  }
}
