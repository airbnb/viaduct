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
                new FieldModel("name", "String", false),
                new FieldModel("email", "String", false),
                new FieldModel("age", "Integer", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("package com.example.types;")
        .contains("public class CreateUserInput implements GraphQLInput")
        .contains("private String name;")
        .contains("private String email;")
        .contains("private Integer age;")
        .contains("public String getName()")
        .contains("public void setName(String name)")
        .contains("public static Builder builder()")
        .contains("public static class Builder");
  }

  @Test
  void generatesInputWithDescription() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "CreateBookingInput",
            List.of(new FieldModel("listingId", "String", false)),
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
                new FieldModel("listingType", "ListingType", true),
                new FieldModel("amenities", "List<String>", true),
                new FieldModel("minPrice", "Double", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("private ListingType listingType;")
        .contains("private List<String> amenities;")
        .contains("private Double minPrice;")
        .contains("public ListingType getListingType()")
        .contains("public List<String> getAmenities()");
  }

  @Test
  void generatesBuilderMethods() {
    InputModel model =
        new InputModel(
            "com.example.types",
            "CreateUserInput",
            List.of(
                new FieldModel("name", "String", false), new FieldModel("age", "Integer", true)),
            null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("public Builder name(String name)")
        .contains("public Builder age(Integer age)")
        .contains("public CreateUserInput build()");
  }
}
