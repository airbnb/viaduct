package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

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
    assertTrue(generated.contains("public String getId()"));
    assertTrue(generated.contains("return fetchScalar(\"id\")"));
    assertTrue(!generated.contains("private String id;"));
    assertTrue(!generated.contains("public void setId("));
    assertTrue(generated.contains("public static Builder builder(ExecutionContext context)"));
    assertTrue(generated.contains("public static class Builder"));
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

    assertTrue(generated.contains("public User getHost()"));
    assertTrue(generated.contains("return fetchObject(\"host\", User::new)"));
    assertTrue(generated.contains("public List<String> getAmenities()"));
    assertTrue(generated.contains("return fetchScalar(\"amenities\")"));
    assertTrue(generated.contains("public double getPricePerNight()"));
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
    assertTrue(generated.contains("return fetchScalarList(\"tags\")"));
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
    assertTrue(generated.contains("return fetchObjectList(\"books\", Book::new)"));
    assertTrue(generated.contains("public List<Tag> getTags()"));
    assertTrue(generated.contains("return fetchEnumList(\"tags\", Tag.class)"));
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
    assertTrue(generated.contains("return fetchAbstractObject(\"topNode\", Node.class)"));
    assertTrue(generated.contains("public SearchResult getTopResult()"));
    assertTrue(generated.contains("return fetchAbstractObject(\"topResult\", SearchResult.class)"));
    assertTrue(generated.contains("public List<SearchResult> getAllResults()"));
    assertTrue(
        generated.contains("return fetchAbstractObjectList(\"allResults\", SearchResult.class)"));
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

    assertTrue(generated.contains("public Builder name(String name)"));
    assertTrue(generated.contains("public Builder age(Integer age)"));
    assertTrue(generated.contains("public User build()"));
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
    assertTrue(generated.contains("return fetchScalar(\"createdAt\", \"DateTime\")"));
    assertTrue(generated.contains("public LocalDate getEventDate()"));
    assertTrue(generated.contains("return fetchScalar(\"eventDate\", \"Date\")"));
    assertTrue(generated.contains("public OffsetTime getStartTime()"));
    assertTrue(generated.contains("return fetchScalar(\"startTime\", \"Time\")"));
    assertTrue(generated.contains("public String getLabel()"));
    assertTrue(generated.contains("return fetchScalar(\"label\")"));
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
    assertTrue(generated.contains("return fetchScalarList(\"timestamps\", \"DateTime\")"));
    assertTrue(generated.contains("public List<LocalDate> getDates()"));
    assertTrue(generated.contains("return fetchScalarList(\"dates\", \"Date\")"));
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
    assertTrue(generated.contains("private final Map<String, Object> data = new LinkedHashMap<>"));
    assertTrue(generated.contains("return new User(__context, new LinkedHashMap<>(data))"));
  }
}
