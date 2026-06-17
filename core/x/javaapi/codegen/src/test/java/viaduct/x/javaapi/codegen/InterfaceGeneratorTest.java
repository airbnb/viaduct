package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

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

    assertTrue(generated.contains("package com.example.types;"));
    assertTrue(generated.contains("public interface Node extends GraphQLInterface"));
    assertTrue(generated.contains("String getId();"));
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

    assertTrue(generated.contains("/**"));
    assertTrue(generated.contains(" * Interface for objects with timestamps."));
    assertTrue(generated.contains(" */"));
    assertTrue(generated.contains("public interface Timestamped extends GraphQLInterface"));
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

    assertTrue(
        generated.contains(
            "public interface Auditable extends GraphQLInterface, Node, Timestamped"));
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

    assertTrue(generated.contains("User getOwner();"));
    assertTrue(generated.contains("List<User> getCollaborators();"));
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

    assertTrue(generated.contains("String getId();"));
    assertTrue(generated.contains("String getName();"));
    assertTrue(generated.contains("boolean getIsActive();"));
  }
}
