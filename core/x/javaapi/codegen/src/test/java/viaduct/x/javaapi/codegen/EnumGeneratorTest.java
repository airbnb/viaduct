package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class EnumGeneratorTest {

  @Test
  void generatesSimpleEnum() {
    EnumModel model =
        new EnumModel(
            "com.example.types",
            "BookingStatus",
            List.of("PENDING", "CONFIRMED", "CANCELLED"),
            null);

    String generated = JavaGRTGenerator.EnumGenerator.generate(model);

    assertTrue(generated.contains("package com.example.types;"));
    assertTrue(generated.contains("import viaduct.java.api.types.GraphQLEnum;"));
    assertTrue(generated.contains("public enum BookingStatus implements GraphQLEnum"));
    assertTrue(generated.contains("PENDING,"));
    assertTrue(generated.contains("CONFIRMED,"));
    assertTrue(generated.contains("CANCELLED"));
  }

  @Test
  void generatesEnumWithDescription() {
    EnumModel model =
        new EnumModel(
            "com.example.types",
            "ListingType",
            List.of("ENTIRE_PLACE", "PRIVATE_ROOM"),
            "Type of listing accommodation.");

    String generated = JavaGRTGenerator.EnumGenerator.generate(model);

    assertTrue(generated.contains("/**"));
    assertTrue(generated.contains(" * Type of listing accommodation."));
    assertTrue(generated.contains(" */"));
    assertTrue(generated.contains("public enum ListingType"));
  }

  @Test
  void generatesEnumWithSingleValue() {
    EnumModel model = new EnumModel("com.example", "SingleValue", List.of("ONLY_ONE"), null);

    String generated = JavaGRTGenerator.EnumGenerator.generate(model);

    assertTrue(generated.contains("public enum SingleValue"));
    assertTrue(generated.contains("ONLY_ONE"));
  }
}
