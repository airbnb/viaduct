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
            null,
            true);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertTrue(generated.contains("package com.example.types;"));
    assertTrue(generated.contains("public interface Node extends NodeCompositeOutput"));
    assertTrue(generated.contains("String getIdOrThrow();"));
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
            "Interface for objects with timestamps.",
            false);

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
            null,
            true);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertTrue(
        generated.contains(
            "public interface Auditable extends NodeCompositeOutput, Node, Timestamped"));
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
            null,
            false);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertTrue(generated.contains("User getOwnerOrThrow();"));
    assertTrue(generated.contains("List<User> getCollaboratorsOrThrow();"));
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
            null,
            false);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertTrue(generated.contains("String getIdOrThrow();"));
    assertTrue(generated.contains("String getNameOrThrow();"));
    assertTrue(generated.contains("boolean getIsActiveOrThrow();"));
    // The bare names remain declared as aliases until call sites have moved over.
    assertTrue(generated.contains("String getId();"));
    assertTrue(generated.contains("String getName();"));
    assertTrue(generated.contains("boolean getIsActive();"));
  }

  @Test
  void generatesReflectionAndFieldsContainer() {
    InterfaceModel model =
        new InterfaceModel(
            "com.example.types",
            "Entity",
            List.of(),
            List.of(FieldModel.simple("id", "String", false)),
            null,
            false);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertTrue(generated.contains("Type<Entity> Reflection = Type.ofClass(Entity.class)"));
    assertTrue(generated.contains("final class Fields implements TypeFields<Entity>"));
    assertTrue(generated.contains("public static final Field<Entity> id"));
  }
}
