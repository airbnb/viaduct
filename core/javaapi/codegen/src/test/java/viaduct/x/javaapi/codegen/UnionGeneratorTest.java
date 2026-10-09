package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class UnionGeneratorTest {

  @Test
  void generatesSimpleUnion() {
    UnionModel model =
        new UnionModel(
            "com.example.types", "SearchResult", List.of("User", "Listing", "Booking"), null);

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertTrue(generated.contains("package com.example.types;"));
    assertTrue(generated.contains("import viaduct.java.api.types.GraphQLUnion;"));
    assertTrue(generated.contains("public interface SearchResult extends GraphQLUnion"));
    assertTrue(generated.contains("Possible types: User, Listing, Booking"));
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

    assertTrue(generated.contains("/**"));
    assertTrue(generated.contains(" * A search result can be a user or listing."));
    assertTrue(generated.contains("Possible types: User, Listing"));
    assertTrue(generated.contains("public interface SearchResult extends GraphQLUnion"));
  }

  @Test
  void generatesUnionWithSingleMemberType() {
    UnionModel model =
        new UnionModel("com.example.types", "SingleUnion", List.of("OnlyType"), null);

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertTrue(generated.contains("public interface SingleUnion extends GraphQLUnion"));
    assertTrue(generated.contains("Possible types: OnlyType"));
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

    assertTrue(generated.contains("public interface LargeUnion extends GraphQLUnion"));
    assertTrue(generated.contains("Possible types: TypeA, TypeB, TypeC, TypeD, TypeE"));
  }
}
