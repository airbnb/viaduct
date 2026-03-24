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
                new FieldModel("id", "String", false),
                new FieldModel("name", "String", false),
                new FieldModel("email", "String", true)),
            null,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("package com.example.types;")
        .contains("public class User implements GraphQLObject")
        .contains("private String id;")
        .contains("private String name;")
        .contains("private String email;")
        .contains("public String getId()")
        .contains("public void setId(String id)")
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
            List.of(new FieldModel("id", "String", false)),
            "A booking for a listing.",
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
            List.of(new FieldModel("id", "String", false), new FieldModel("name", "String", false)),
            null,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated).contains("public class Human implements GraphQLObject, Character, Node");
  }

  @Test
  void generatesObjectWithComplexFields() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "Listing",
            List.of(),
            List.of(
                new FieldModel("host", "User", false),
                new FieldModel("amenities", "List<String>", false),
                new FieldModel("pricePerNight", "double", false)),
            null,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("private User host;")
        .contains("private List<String> amenities;")
        .contains("private double pricePerNight;")
        .contains("public User getHost()")
        .contains("public List<String> getAmenities()")
        .contains("public double getPricePerNight()");
  }

  @Test
  void generatesBuilderMethods() {
    ObjectModel model =
        new ObjectModel(
            "com.example.types",
            "User",
            List.of(),
            List.of(
                new FieldModel("name", "String", false), new FieldModel("age", "Integer", true)),
            null,
            false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("public Builder name(String name)")
        .contains("public Builder age(Integer age)")
        .contains("public User build()");
  }
}
