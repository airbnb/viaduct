package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

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

    assertThat(generated)
        .contains("package com.example.types;")
        .contains("public class User extends JavaObjectBase")
        .doesNotContain("implements GraphQLObject")
        .contains("public String getId()")
        .contains("return fetchScalar(\"id\")")
        .doesNotContain("private String id;")
        .doesNotContain("public void setId(")
        .contains("public static Builder builder()")
        .contains("public static class Builder");
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

    assertThat(generated)
        .contains("/**")
        .contains(" * A booking for a listing.")
        .contains(" */")
        .contains("public class Booking");
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

    assertThat(generated)
        .contains("public class Human extends JavaObjectBase implements Character, Node");
  }

  @Test
  void generatesObjectWithComplexFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Listing",
            List.of(),
            List.of(
                new FieldModel("host", "User", false, true, false, false, false, "User"),
                FieldModel.simple("amenities", "List<String>", false),
                FieldModel.simple("pricePerNight", "double", false)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("public User getHost()")
        .contains("return fetchObject(\"host\", User::new)")
        .contains("public List<String> getAmenities()")
        .contains("return fetchScalar(\"amenities\")")
        .contains("public double getPricePerNight()");
  }

  @Test
  void generatesObjectWithScalarListField() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Listing",
            List.of(),
            List.of(new FieldModel("tags", "List<String>", true, false, true, false, false, null)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("public List<String> getTags()")
        .contains("return fetchScalarList(\"tags\")");
  }

  @Test
  void generatesObjectWithListFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Author",
            List.of(),
            List.of(
                new FieldModel("books", "List<Book>", true, true, true, false, false, "Book"),
                new FieldModel("tags", "List<Tag>", true, false, true, true, false, "Tag")),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("public List<Book> getBooks()")
        .contains("return fetchObjectList(\"books\", Book::new)")
        .contains("public List<Tag> getTags()")
        .contains("return fetchEnumList(\"tags\", Tag.class)");
  }

  @Test
  void generatesObjectWithAbstractFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "SearchContainer",
            List.of(),
            List.of(
                new FieldModel("topNode", "Node", true, false, false, false, true, "Node"),
                new FieldModel(
                    "topResult", "SearchResult", true, false, false, false, true, "SearchResult"),
                new FieldModel(
                    "allResults",
                    "List<SearchResult>",
                    false,
                    false,
                    true,
                    false,
                    true,
                    "SearchResult")),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("public Node getTopNode()")
        .contains("return fetchAbstractObject(\"topNode\", Node.class)")
        .contains("public SearchResult getTopResult()")
        .contains("return fetchAbstractObject(\"topResult\", SearchResult.class)")
        .contains("public List<SearchResult> getAllResults()")
        .contains("return fetchAbstractObjectList(\"allResults\", SearchResult.class)");
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

    assertThat(generated)
        .contains("public Builder name(String name)")
        .contains("public Builder age(Integer age)")
        .contains("public User build()");
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

    assertThat(generated)
        .contains("public Instant getCreatedAt()")
        .contains("return fetchScalar(\"createdAt\", \"DateTime\")")
        .contains("public LocalDate getEventDate()")
        .contains("return fetchScalar(\"eventDate\", \"Date\")")
        .contains("public OffsetTime getStartTime()")
        .contains("return fetchScalar(\"startTime\", \"Time\")")
        .contains("public String getLabel()")
        .contains("return fetchScalar(\"label\")");
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
                    "timestamps", "List<Instant>", true, false, true, false, false, null),
                new FieldModel("dates", "List<LocalDate>", true, false, true, false, false, null)),
            null,
            false,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("public List<Instant> getTimestamps()")
        .contains("return fetchScalarList(\"timestamps\", \"DateTime\")")
        .contains("public List<LocalDate> getDates()")
        .contains("return fetchScalarList(\"dates\", \"Date\")");
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

    assertThat(generated)
        .contains("public User(EngineObjectData.Sync data)")
        .contains("private User(Map<String, Object> data)")
        .contains("private final Map<String, Object> data = new LinkedHashMap<>")
        .contains("return new User(new LinkedHashMap<>(data))");
  }
}
