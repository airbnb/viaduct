package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

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

    assertThat(generated)
        .contains("package com.example.types;")
        .contains("public enum BookingStatus")
        .contains("PENDING,")
        .contains("CONFIRMED,")
        .contains("CANCELLED");
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

    assertThat(generated)
        .contains("/**")
        .contains(" * Type of listing accommodation.")
        .contains(" */")
        .contains("public enum ListingType");
  }

  @Test
  void generatesEnumWithSingleValue() {
    EnumModel model = new EnumModel("com.example", "SingleValue", List.of("ONLY_ONE"), null);

    String generated = JavaGRTGenerator.EnumGenerator.generate(model);

    assertThat(generated).contains("public enum SingleValue").contains("ONLY_ONE");
  }
}
