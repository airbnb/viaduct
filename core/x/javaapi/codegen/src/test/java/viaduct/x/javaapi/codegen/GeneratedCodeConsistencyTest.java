package viaduct.x.javaapi.codegen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests to ensure generated code uses the correct marker interface types. This prevents issues
 * where we import GraphQLInterface but use "Interface" in the extends clause, or vice versa.
 */
class GeneratedCodeConsistencyTest {

  @Test
  void interfaceGenerator_usesGraphQLInterface() {
    InterfaceModel model =
        new InterfaceModel("com.example", "TestInterface", List.of(), List.of(), null, false);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertTrue(generated.contains("import viaduct.java.api.types.GraphQLInterface;"));
    assertTrue(generated.contains("extends GraphQLInterface"));
  }

  @Test
  void objectGenerator_usesObjectBase() {
    ObjectModel model =
        new ObjectModel("com.example", "TestObject", List.of(), List.of(), null, false, false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("import viaduct.java.api.internal.ObjectBase;"));
    assertTrue(generated.contains("extends ObjectBase"));
  }

  @Test
  void objectGenerator_usesNodeObjectBase_forNodeTypes() {
    ObjectModel model =
        new ObjectModel("com.example", "TestNode", List.of("Node"), List.of(), null, false, true);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertTrue(generated.contains("import viaduct.java.api.internal.NodeObjectBase;"));
    assertTrue(generated.contains("extends NodeObjectBase"));
  }

  @Test
  void inputGenerator_usesInputBase() {
    InputModel model = new InputModel("com.example", "TestInput", List.of(), null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertTrue(generated.contains("import viaduct.java.api.internal.InputBase;"));
    assertTrue(generated.contains("extends InputBase"));
  }

  @Test
  void unionGenerator_usesGraphQLUnion() {
    UnionModel model = new UnionModel("com.example", "TestUnion", List.of("TypeA"), null);

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertTrue(generated.contains("import viaduct.java.api.types.GraphQLUnion;"));
    assertTrue(generated.contains("extends GraphQLUnion"));
  }
}
