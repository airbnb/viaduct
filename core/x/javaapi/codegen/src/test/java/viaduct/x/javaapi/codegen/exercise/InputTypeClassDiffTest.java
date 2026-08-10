package viaduct.x.javaapi.codegen.exercise;

import static org.assertj.core.api.Assertions.assertThat;

import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import viaduct.api.internal.InputTypeFactory;
import viaduct.engine.api.ViaductSchema;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.reflect.Field;

/**
 * ClassDiff tests for generated input type classes, comparing them against expected reference
 * classes.
 */
class InputTypeClassDiffTest extends AbstractClassDiffTest {

  private static final String SCHEMA_RESOURCE = "graphql/exerciser_input_schema.graphqls";

  @Override
  protected String getSchemaResource() {
    return SCHEMA_RESOURCE;
  }

  @Test
  void exerciseAllInputs() throws Exception {
    exerciseTypes(
        List.of("SimpleInput", "InputWithDescription", "ComplexInput", "AllFieldTypesInput"));
  }

  @Test
  void exerciseSimpleInput() throws Exception {
    exerciseSingleType("SimpleInput");
  }

  @Test
  void exerciseInputWithDescription() throws Exception {
    exerciseSingleType("InputWithDescription");
  }

  @Test
  void exerciseComplexInput() throws Exception {
    exerciseSingleType("ComplexInput");
  }

  @Test
  void exerciseAllFieldTypesInput() throws Exception {
    exerciseSingleType("AllFieldTypesInput");
  }

  @Test
  void generatedBuilderReportsPresenceAfterSchemaDefaults() throws Exception {
    Class<?> inputClass = generateAndLoad("SimpleInput");
    ExecutionContext context = contextForSchema();
    Object omittedInput = build(inputClass, context);
    Class<?> fieldsClass =
        Class.forName(inputClass.getName() + "$Fields", true, inputClass.getClassLoader());
    Object countField = fieldsClass.getField("count").get(null);
    Object nameField = fieldsClass.getField("name").get(null);
    var isPresent = inputClass.getMethod("isPresent", Field.class);
    var getCount = inputClass.getMethod("getCount");

    assertThat(isPresent.invoke(omittedInput, countField)).isEqualTo(true);
    assertThat(getCount.invoke(omittedInput)).isEqualTo(42);
    assertThat(isPresent.invoke(omittedInput, nameField)).isEqualTo(false);

    Object explicitNullBuilder =
        inputClass.getMethod("builder", ExecutionContext.class).invoke(null, context);
    explicitNullBuilder
        .getClass()
        .getMethod("count", Integer.class)
        .invoke(explicitNullBuilder, new Object[] {null});
    Object explicitNullInput =
        explicitNullBuilder.getClass().getMethod("build").invoke(explicitNullBuilder);

    assertThat(isPresent.invoke(explicitNullInput, countField)).isEqualTo(true);
    assertThat(getCount.invoke(explicitNullInput)).isNull();
  }

  @Test
  void generatedArgumentBuilderReportsPresenceAfterSchemaDefaults() throws Exception {
    Class<?> argumentsClass = generateAndLoad("Query_InputDefaults_Arguments");
    ExecutionContext context = contextForSchema();
    Object omittedArguments = build(argumentsClass, context);
    Class<?> fieldsClass =
        Class.forName(argumentsClass.getName() + "$Fields", true, argumentsClass.getClassLoader());
    Object countField = fieldsClass.getField("count").get(null);
    Object nameField = fieldsClass.getField("name").get(null);
    var isPresent = argumentsClass.getMethod("isPresent", Field.class);
    var getCount = argumentsClass.getMethod("getCount");

    assertThat(isPresent.invoke(omittedArguments, countField)).isEqualTo(true);
    assertThat(getCount.invoke(omittedArguments)).isEqualTo(42);
    assertThat(isPresent.invoke(omittedArguments, nameField)).isEqualTo(false);

    Object explicitNullBuilder =
        argumentsClass.getMethod("builder", ExecutionContext.class).invoke(null, context);
    explicitNullBuilder
        .getClass()
        .getMethod("count", Integer.class)
        .invoke(explicitNullBuilder, new Object[] {null});
    Object explicitNullArguments =
        explicitNullBuilder.getClass().getMethod("build").invoke(explicitNullBuilder);

    assertThat(isPresent.invoke(explicitNullArguments, countField)).isEqualTo(true);
    assertThat(getCount.invoke(explicitNullArguments)).isNull();
  }

  private Object build(Class<?> inputClass, ExecutionContext context) throws Exception {
    Object builder = inputClass.getMethod("builder", ExecutionContext.class).invoke(null, context);
    return builder.getClass().getMethod("build").invoke(builder);
  }

  private ExecutionContext contextForSchema() throws Exception {
    ViaductSchema schema;
    try (var stream =
        Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream(SCHEMA_RESOURCE),
            "Schema resource not found: " + SCHEMA_RESOURCE)) {
      var registry =
          new SchemaParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
      schema = new ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(registry));
    }

    return (ExecutionContext)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {ExecutionContext.class, InternalContext.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getSchema") && method.getParameterCount() == 0) {
                return schema;
              }
              if (method.getName().equals("getArgumentsInputType")
                  && method.getParameterCount() == 3) {
                return InputTypeFactory.argumentsInputType(
                    (String) args[0], (String) args[1], (String) args[2], schema);
              }
              throw new UnsupportedOperationException(method.toString());
            });
  }
}
