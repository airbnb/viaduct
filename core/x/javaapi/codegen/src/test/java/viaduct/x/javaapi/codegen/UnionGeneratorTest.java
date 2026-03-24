package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnionGeneratorTest {

  @Test
  void generatesSimpleUnion() {
    UnionModel model =
        new UnionModel(
            "com.example.types", "SearchResult", List.of("User", "Listing", "Booking"), null);

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertThat(generated)
        .contains("package com.example.types;")
        .contains("import viaduct.java.api.types.GraphQLUnion;")
        .contains("public interface SearchResult extends GraphQLUnion")
        .contains("Possible types: User, Listing, Booking");
  }

  @Test
  void generatesUnionWithDescription() {
    UnionModel model =
        new UnionModel(
            "com.example.types",
            "SearchResult",
            List.of("User", "Listing"),
            "A search result can be a user or listing.");

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertThat(generated)
        .contains("/**")
        .contains(" * A search result can be a user or listing.")
        .contains("Possible types: User, Listing")
        .contains("public interface SearchResult extends GraphQLUnion");
  }

  @Test
  void generatesUnionWithSingleMemberType() {
    UnionModel model =
        new UnionModel("com.example.types", "SingleUnion", List.of("OnlyType"), null);

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertThat(generated)
        .contains("public interface SingleUnion extends GraphQLUnion")
        .contains("Possible types: OnlyType");
  }

  @Test
  void generatesUnionWithManyMemberTypes() {
    UnionModel model =
        new UnionModel(
            "com.example.types",
            "LargeUnion",
            List.of("TypeA", "TypeB", "TypeC", "TypeD", "TypeE"),
            null);

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertThat(generated)
        .contains("public interface LargeUnion extends GraphQLUnion")
        .contains("Possible types: TypeA, TypeB, TypeC, TypeD, TypeE");
  }
}
