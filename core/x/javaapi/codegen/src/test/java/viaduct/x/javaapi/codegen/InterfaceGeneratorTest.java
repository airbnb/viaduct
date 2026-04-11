package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InterfaceGeneratorTest {

  @Test
  void generatesSimpleInterface() {
    InterfaceModel model =
        new InterfaceModel(
            "com.example.types",
            "Node",
            List.of(),
            List.of(FieldModel.simple("id", "String", false)),
            null);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertThat(generated)
        .contains("package com.example.types;")
        .contains("public interface Node extends GraphQLInterface")
        .contains("String getId();");
  }

  @Test
  void generatesInterfaceWithDescription() {
    InterfaceModel model =
        new InterfaceModel(
            "com.example.types",
            "Timestamped",
            List.of(),
            List.of(
                FieldModel.simple("createdAt", "String", false),
                FieldModel.simple("updatedAt", "String", true)),
            "Interface for objects with timestamps.");

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertThat(generated)
        .contains("/**")
        .contains(" * Interface for objects with timestamps.")
        .contains(" */")
        .contains("public interface Timestamped extends GraphQLInterface");
  }

  @Test
  void generatesInterfaceExtendingOtherInterfaces() {
    InterfaceModel model =
        new InterfaceModel(
            "com.example.types",
            "Auditable",
            List.of("Node", "Timestamped"),
            List.of(
                FieldModel.simple("id", "String", false),
                FieldModel.simple("createdAt", "String", false),
                FieldModel.simple("createdBy", "String", false)),
            null);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertThat(generated)
        .contains("public interface Auditable extends GraphQLInterface, Node, Timestamped");
  }

  @Test
  void generatesInterfaceWithComplexFields() {
    InterfaceModel model =
        new InterfaceModel(
            "com.example.types",
            "HasOwner",
            List.of(),
            List.of(
                FieldModel.simple("owner", "User", false),
                FieldModel.simple("collaborators", "List<User>", true)),
            null);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertThat(generated).contains("User getOwner();").contains("List<User> getCollaborators();");
  }

  @Test
  void generatesMultipleGetters() {
    InterfaceModel model =
        new InterfaceModel(
            "com.example.types",
            "Entity",
            List.of(),
            List.of(
                FieldModel.simple("id", "String", false),
                FieldModel.simple("name", "String", false),
                FieldModel.simple("isActive", "boolean", false)),
            null);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertThat(generated)
        .contains("String getId();")
        .contains("String getName();")
        .contains("boolean getIsActive();");
  }
}
