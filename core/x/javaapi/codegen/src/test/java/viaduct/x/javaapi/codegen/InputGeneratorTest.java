package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InputGeneratorTest {

  @Test
  void generatesSimpleInput() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "CreateUserInput",
            List.of(
                FieldModel.simple("name", "String", false),
                FieldModel.simple("email", "String", false),
                FieldModel.simple("age", "Integer", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("package com.example.types;")
        .contains("public class CreateUserInput extends JavaInputBase")
        .doesNotContain("implements GraphQLInput")
        .contains("public CreateUserInput(Map<String, Object> data)")
        .contains("private final Map<String, Object> data = new LinkedHashMap<>")
        .contains("public String getName()")
        .contains("return get(\"name\")")
        .doesNotContain("private String name;")
        .doesNotContain("public void setName(")
        .contains("public static Builder builder()")
        .contains("public static class Builder");
  }

  @Test
  void generatesInputWithDescription() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "CreateBookingInput",
            List.of(FieldModel.simple("listingId", "String", false)),
            "Input for creating a booking.");

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("/**")
        .contains(" * Input for creating a booking.")
        .contains(" */")
        .contains("public class CreateBookingInput");
  }

  @Test
  void generatesInputWithComplexFields() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "SearchFiltersInput",
            List.of(
                new FieldModel(
                    "listingType", "ListingType", true, false, false, true, false, "ListingType"),
                FieldModel.simple("amenities", "List<String>", true),
                FieldModel.simple("minPrice", "Double", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("public ListingType getListingType()")
        .contains("return getEnum(\"listingType\", ListingType.class)")
        .contains("public List<String> getAmenities()")
        .contains("return get(\"amenities\")");
  }

  @Test
  void generatesInputWithScalarListField() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "TagInput",
            List.of(
                new FieldModel("tags", "List<String>", true, false, true, false, false, null),
                new FieldModel("ids", "List<Integer>", true, false, true, false, false, null)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("public List<String> getTags()")
        .contains("return getScalarList(\"tags\")")
        .contains("public List<Integer> getIds()")
        .contains("return getScalarList(\"ids\")");
  }

  @Test
  void generatesInputWithListFields() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "FilterInput",
            List.of(
                new FieldModel(
                    "nestedItems", "List<ItemInput>", true, true, true, false, false, "ItemInput"),
                new FieldModel(
                    "statuses", "List<Status>", true, false, true, true, false, "Status")),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("public List<ItemInput> getNestedItems()")
        .contains("return getInputList(\"nestedItems\", ItemInput::new)")
        .contains("public List<Status> getStatuses()")
        .contains("return getEnumList(\"statuses\", Status.class)");
  }

  @Test
  void generatesInputWithTemporalScalarFields() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "EventInput",
            List.of(
                FieldModel.simple("createdAt", "Instant", true),
                FieldModel.simple("eventDate", "LocalDate", true),
                FieldModel.simple("startTime", "OffsetTime", true),
                FieldModel.simple("label", "String", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("public Instant getCreatedAt()")
        .contains("return get(\"createdAt\", \"DateTime\")")
        .contains("public LocalDate getEventDate()")
        .contains("return get(\"eventDate\", \"Date\")")
        .contains("public OffsetTime getStartTime()")
        .contains("return get(\"startTime\", \"Time\")")
        .contains("public String getLabel()")
        .contains("return get(\"label\")");
  }

  @Test
  void generatesBuilderMethods() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "CreateUserInput",
            List.of(
                FieldModel.simple("name", "String", false),
                FieldModel.simple("age", "Integer", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("public Builder name(String name)")
        .contains("public Builder age(Integer age)")
        .contains("public CreateUserInput build()");
  }
}
