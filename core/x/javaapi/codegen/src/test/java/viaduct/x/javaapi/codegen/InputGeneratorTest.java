package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

    assertTrue(generated.contains("package com.example.types;"));
    assertTrue(generated.contains("public class CreateUserInput extends InputBase"));
    assertTrue(!generated.contains("implements GraphQLInput"));
    // The raw-map constructor is package-private on plain inputs: tenants must go through the
    // validating Builder, so an invalid @oneOf input cannot bypass fail-fast construction.
    assertTrue(
        generated.contains(
            "CreateUserInput(InternalContext context, Map<String, Object> data,"
                + " GraphQLInputObjectType graphQLInputObjectType)"));
    assertTrue(
        !generated.contains("public CreateUserInput(InternalContext context"),
        "Expected package-private (non-public) input constructor, got:\n" + generated);
    assertTrue(generated.contains("private final Map<String, Object> data = new LinkedHashMap<>"));
    assertTrue(generated.contains("public String getName()"));
    assertTrue(generated.contains("return get(\"name\")"));
    assertTrue(!generated.contains("private String name;"));
    assertTrue(!generated.contains("public void setName("));
    assertTrue(generated.contains("public static Builder builder(ExecutionContext context)"));
    assertTrue(generated.contains("public static class Builder"));
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

    assertTrue(generated.contains("/**"));
    assertTrue(generated.contains(" * Input for creating a booking."));
    assertTrue(generated.contains(" */"));
    assertTrue(generated.contains("public class CreateBookingInput"));
  }

  @Test
  void generatesInputWithComplexFields() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "SearchFiltersInput",
            List.of(
                new FieldModel(
                    "listingType",
                    "ListingType",
                    true,
                    false,
                    false,
                    true,
                    false,
                    false,
                    "ListingType"),
                FieldModel.simple("amenities", "List<String>", true),
                FieldModel.simple("minPrice", "Double", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertTrue(generated.contains("public ListingType getListingType()"));
    assertTrue(generated.contains("return getEnum(\"listingType\", ListingType.class)"));
    assertTrue(generated.contains("public List<String> getAmenities()"));
    assertTrue(generated.contains("return get(\"amenities\")"));
  }

  @Test
  void generatesInputWithScalarListField() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "TagInput",
            List.of(
                new FieldModel(
                    "tags", "List<String>", true, false, true, false, false, false, null),
                new FieldModel(
                    "ids", "List<Integer>", true, false, true, false, false, false, null)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertTrue(generated.contains("public List<String> getTags()"));
    assertTrue(generated.contains("return getScalarList(\"tags\")"));
    assertTrue(generated.contains("public List<Integer> getIds()"));
    assertTrue(generated.contains("return getScalarList(\"ids\")"));
  }

  @Test
  void generatesInputWithListFields() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "FilterInput",
            List.of(
                new FieldModel(
                    "nestedItems",
                    "List<ItemInput>",
                    true,
                    true,
                    true,
                    false,
                    false,
                    false,
                    "ItemInput"),
                new FieldModel(
                    "statuses", "List<Status>", true, false, true, true, false, false, "Status")),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertTrue(generated.contains("public List<ItemInput> getNestedItems()"));
    assertTrue(generated.contains("return getInputList(\"nestedItems\", ItemInput::new)"));
    assertTrue(generated.contains("public List<Status> getStatuses()"));
    assertTrue(generated.contains("return getEnumList(\"statuses\", Status.class)"));
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

    assertTrue(generated.contains("public Instant getCreatedAt()"));
    assertTrue(generated.contains("return get(\"createdAt\", \"DateTime\")"));
    assertTrue(generated.contains("public LocalDate getEventDate()"));
    assertTrue(generated.contains("return get(\"eventDate\", \"Date\")"));
    assertTrue(generated.contains("public OffsetTime getStartTime()"));
    assertTrue(generated.contains("return get(\"startTime\", \"Time\")"));
    assertTrue(generated.contains("public String getLabel()"));
    assertTrue(generated.contains("return get(\"label\")"));
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

    assertTrue(generated.contains("public Builder name(String name)"));
    assertTrue(generated.contains("public Builder age(Integer age)"));
    assertTrue(generated.contains("public CreateUserInput build()"));
  }

  @Test
  void oneOfInputBuildValidates() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "SearchFilterInput",
            List.of(
                FieldModel.simple("byId", "String", true),
                FieldModel.simple("byName", "String", true)),
            null,
            /* isOneOf= */ true);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertTrue(
        generated.contains("InputBase.validateOneOf(\"SearchFilterInput\", data);"),
        "Expected build() to validate the @oneOf constraint, got:\n" + generated);
  }

  @Test
  void nonOneOfInputBuildDoesNotValidate() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "PlainInput",
            List.of(FieldModel.simple("value", "String", true)),
            null,
            /* isOneOf= */ false);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertTrue(
        !generated.contains("validateOneOf"),
        "Expected no @oneOf validation on a plain input, got:\n" + generated);
  }
}
