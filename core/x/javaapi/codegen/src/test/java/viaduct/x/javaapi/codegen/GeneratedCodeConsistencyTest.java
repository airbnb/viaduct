package viaduct.x.javaapi.codegen;

import static org.assertj.core.api.Assertions.assertThat;

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
        new InterfaceModel("com.example", "TestInterface", List.of(), List.of(), null);

    String generated = JavaGRTGenerator.InterfaceGenerator.generate(model);

    assertThat(generated)
        .contains("import viaduct.java.api.types.GraphQLInterface;")
        .contains("extends GraphQLInterface");
  }

  @Test
  void objectGenerator_usesJavaObjectBase() {
    ObjectModel model =
        new ObjectModel("com.example", "TestObject", List.of(), List.of(), null, false, false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("import viaduct.java.api.internal.JavaObjectBase;")
        .contains("extends JavaObjectBase");
  }

  @Test
  void objectGenerator_usesJavaNodeObjectBase_forNodeTypes() {
    ObjectModel model =
        new ObjectModel("com.example", "TestNode", List.of("Node"), List.of(), null, false, true);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("import viaduct.java.api.internal.JavaNodeObjectBase;")
        .contains("extends JavaNodeObjectBase");
  }

  @Test
  void inputGenerator_usesJavaInputBase() {
    InputModel model = new InputModel("com.example", "TestInput", List.of(), null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("import viaduct.java.api.internal.JavaInputBase;")
        .contains("extends JavaInputBase");
  }

  @Test
  void unionGenerator_usesGraphQLUnion() {
    UnionModel model = new UnionModel("com.example", "TestUnion", List.of("TypeA"), null);

    String generated = JavaGRTGenerator.UnionGenerator.generate(model);

    assertThat(generated)
        .contains("import viaduct.java.api.types.GraphQLUnion;")
        .contains("extends GraphQLUnion");
  }
}
