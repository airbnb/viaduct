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
  void objectGenerator_usesGraphQLObject() {
    ObjectModel model =
        new ObjectModel("com.example", "TestObject", List.of(), List.of(), null, false);

    String generated = JavaGRTGenerator.ObjectGenerator.generate(model);

    assertThat(generated)
        .contains("import viaduct.java.api.types.GraphQLObject;")
        .contains("implements GraphQLObject");
  }

  @Test
  void inputGenerator_usesGraphQLInput() {
    InputModel model = new InputModel("com.example", "TestInput", List.of(), null);

    String generated = JavaGRTGenerator.InputGenerator.generate(model);

    assertThat(generated)
        .contains("import viaduct.java.api.types.GraphQLInput;")
        .contains("implements GraphQLInput");
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
