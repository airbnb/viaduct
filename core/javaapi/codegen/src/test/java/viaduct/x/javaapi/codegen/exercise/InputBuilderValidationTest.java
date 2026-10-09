package viaduct.x.javaapi.codegen.exercise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import graphql.schema.GraphQLInputObjectType;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import viaduct.engine.api.EngineSchema;
import viaduct.errors.FrameworkException;
import viaduct.errors.TenantUsageException;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;
import viaduct.tenant.runtime.jvm.InputTypeFactory;

class InputBuilderValidationTest extends AbstractClassDiffTest {
  private ClassLoader generatedClassLoader;

  private static final List<String> INPUT_AND_ARGUMENTS =
      List.of("ValidationInput", "Query_Validate_Arguments");

  @Override
  protected String getSchemaResource() {
    return "graphql/exerciser_input_schema.graphqls";
  }

  @Test
  void omittedRequiredFieldsFailBeforePrimitiveReads() throws Exception {
    for (String name : INPUT_AND_ARGUMENTS) {
      Object builder = builder(generatedType(name));
      tenantFailure(builder, name + ".count");
      set(builder, "count", int.class, 3);
      tenantFailure(builder, name + ".token");
      set(builder, "token", String.class, "valid");
      tenantFailure(builder, name + ".matrix");
      set(builder, "matrix", List.class, List.of());
      InputBase result = build(builder);
      assertThat(result.getClass().getMethod("getCount").invoke(result)).isEqualTo(3);
      assertThat(result.getClass().getMethod("getDefaulted").invoke(result)).isEqualTo(7);
      assertThat(result.getInputData()).doesNotContainKey("defaulted");
      assertThat(result.getClass().getMethod("getNullable").invoke(result)).isEqualTo("fallback");
      assertThat(result.getInputData()).doesNotContainKey("nullable");
    }
  }

  @Test
  void explicitNullDoesNotUseANonNullFieldsDefault() throws Exception {
    for (String name : INPUT_AND_ARGUMENTS) {
      Object builder = validBuilder(generatedType(name));
      set(builder, "token", String.class, null);
      tenantFailure(builder, name + ".token");

      if (name.equals("ValidationInput")) {
        set(builder, "token", String.class, "valid");
        InputBase original = build(builder);
        Object copy = original.getClass().getMethod("toBuilder").invoke(original);
        set(copy, "token", String.class, null);
        tenantFailure(copy, name + ".token");
        assertThat(original.getClass().getMethod("getToken").invoke(original)).isEqualTo("valid");
      }
      Class<?> childType =
          Class.forName(
              GENERATED_PACKAGE + ".ValidationChild", true, builder.getClass().getClassLoader());
      Object childBuilder = builder(childType);
      set(childBuilder, "count", int.class, 4);
      set(childBuilder, "token", String.class, null);
      tenantFailure(childBuilder, "ValidationChild.token");
    }
  }

  @Test
  void nullContainersAndElementsFollowEverySchemaWrapper() throws Exception {
    for (String name : INPUT_AND_ARGUMENTS) {
      Object builder = validBuilder(generatedType(name));
      set(builder, "matrix", List.class, null);
      tenantFailure(builder, name + ".matrix");
      set(builder, "matrix", List.class, Arrays.asList((Object) null));
      tenantFailure(builder, name + ".matrix[0]");
      set(builder, "matrix", List.class, List.of(Arrays.asList(1, null)));
      tenantFailure(builder, name + ".matrix[0][1]");
      set(builder, "matrix", List.class, List.of(List.of(1, 2)));
      set(builder, "cube", List.class, List.of(List.of(Arrays.asList(1, null))));
      tenantFailure(builder, name + ".cube[0][0][1]");
      set(builder, "cube", List.class, List.of(Arrays.asList((Object) null)));
      tenantFailure(builder, name + ".cube[0][0]");
      set(builder, "cube", List.class, null);
      List<List<Integer>> loose = Arrays.asList(null, Arrays.asList(1, null));
      set(builder, "loose", List.class, loose);
      set(builder, "nullable", String.class, null);
      InputBase result = build(builder);
      assertThat(result.getClass().getMethod("getMatrix").invoke(result))
          .isEqualTo(List.of(List.of(1, 2)));
      assertThat(result.getClass().getMethod("getLoose").invoke(result)).isEqualTo(loose);
      assertThat(result.getClass().getMethod("getNullable").invoke(result)).isNull();
      assertThat(result.getInputData()).containsEntry("nullable", null);
    }
  }

  @Test
  void nestedInputsAreCheckedAgainstTheParentSchemaAndKeepDefaultsOnRead() throws Exception {
    for (String name : INPUT_AND_ARGUMENTS) {
      Class<?> type = generatedType(name);
      Class<?> childType =
          Class.forName(GENERATED_PACKAGE + ".ValidationChild", true, type.getClassLoader());
      Object builder = validBuilder(type);
      set(builder, "child", childType, rawInput(childType, Map.of()));
      tenantFailure(builder, name + ".child.count");
      set(builder, "child", childType, rawInput(childType, Map.of("count", 4, "token", "valid")));
      set(builder, "children", List.class, List.of(rawInput(childType, Map.of())));
      tenantFailure(builder, name + ".children[0].count");
      set(builder, "children", List.class, Arrays.asList((Object) null));
      tenantFailure(builder, name + ".children[0]");
      Map<String, Object> childData = new java.util.HashMap<>(Map.of("count", 5));
      set(builder, "children", List.class, List.of(childData));
      InputBase result = build(builder);
      List<?> children = (List<?>) type.getMethod("getChildren").invoke(result);
      Object child = children.get(0);
      childData.put("count", null);
      assertThat(childType.getMethod("getCount").invoke(child)).isEqualTo(5);
      assertThat(childType.getMethod("getToken").invoke(child)).isEqualTo("child-default");
      assertThat(((InputBase) child).getInputData()).doesNotContainKey("token");
      Object copy = childType.getMethod("toBuilder").invoke(child);
      assertThat(childType.getMethod("getToken").invoke(build(copy))).isEqualTo("child-default");
      assertThat(childType.getMethod("getCount").invoke(type.getMethod("getChild").invoke(result)))
          .isEqualTo(4);
    }
  }

  @Test
  void validatedListsAreDetachedFromCallerMutation() throws Exception {
    for (String name : INPUT_AND_ARGUMENTS) {
      Object builder = validBuilder(generatedType(name));
      List<Integer> row = new ArrayList<>(List.of(1));
      List<List<Integer>> matrix = new ArrayList<>(List.of(row));
      set(builder, "matrix", List.class, matrix);
      InputBase result = build(builder);
      row.set(0, null);
      matrix.add(null);
      assertThat(result.getClass().getMethod("getMatrix").invoke(result))
          .isEqualTo(List.of(List.of(1)));
      tenantFailure(builder, name + ".matrix[0][0]");
    }
  }

  @Test
  void nestedOneOfStillRejectsMissingMultipleAndNullChoices() throws Exception {
    for (String name : INPUT_AND_ARGUMENTS) {
      Class<?> type = generatedType(name);
      Class<?> choiceType =
          Class.forName(GENERATED_PACKAGE + ".CopyChoice", true, type.getClassLoader());
      Object builder = validBuilder(type);
      set(builder, "choice", choiceType, rawInput(choiceType, Map.of()));
      tenantFailure(builder, "Exactly one field");
      set(
          builder,
          "choice",
          choiceType,
          rawInput(choiceType, Map.of("byName", "Alice", "byEmail", "email")));
      tenantFailure(builder, "Exactly one field");
      set(
          builder,
          "choice",
          choiceType,
          rawInput(choiceType, java.util.Collections.singletonMap("byName", null)));
      tenantFailure(builder, "must have a non-null value");
      set(builder, "choice", choiceType, rawInput(choiceType, Map.of("byName", "Alice")));
      InputBase result = build(builder);
      assertThat(
              choiceType.getMethod("getByName").invoke(type.getMethod("getChoice").invoke(result)))
          .isEqualTo("Alice");
    }
  }

  @Test
  void connectionBuildersValidateOnlyDeclaredArguments() throws Exception {
    Class<?> type = generatedType("Query_FirstOnly_Arguments");
    Object builder = builder(type);
    tenantFailure(builder, "Query_FirstOnly_Arguments.first");
    set(builder, "first", Integer.class, null);
    tenantFailure(builder, "Query_FirstOnly_Arguments.first");
    set(builder, "first", Integer.class, 3);
    InputBase result = build(builder);
    assertThat(type.getMethod("getFirst").invoke(result)).isEqualTo(3);
    assertThat(type.getMethod("getAfter").invoke(result)).isNull();
    assertThat(result.getInputData()).containsOnlyKeys("first");
  }

  @Test
  void missingSchemaIsAFrameworkError() throws Exception {
    Class<?> type = generatedType("ValidationInput");
    InputBase raw = rawInput(type, Map.of());
    Object builder = type.getMethod("toBuilder").invoke(raw);
    assertThat(assertThrows(InvocationTargetException.class, () -> build(builder)).getCause())
        .isInstanceOf(FrameworkException.class)
        .hasMessageContaining("ValidationInput");
  }

  private Class<?> generatedType(String name) throws Exception {
    if (generatedClassLoader == null) {
      generatedClassLoader = generateAndLoad(name).getClassLoader();
    }
    return Class.forName(GENERATED_PACKAGE + "." + name, true, generatedClassLoader);
  }

  private Object builder(Class<?> type) throws Exception {
    return type.getMethod("builder", ExecutionContext.class).invoke(null, contextForSchema());
  }

  private Object validBuilder(Class<?> type) throws Exception {
    Object builder = builder(type);
    set(builder, "count", int.class, 3);
    set(builder, "token", String.class, "valid");
    set(builder, "matrix", List.class, List.of());
    return builder;
  }

  private void set(Object builder, String field, Class<?> parameterType, Object value)
      throws Exception {
    builder.getClass().getMethod(field, parameterType).invoke(builder, new Object[] {value});
  }

  private InputBase build(Object builder) throws Exception {
    return (InputBase) builder.getClass().getMethod("build").invoke(builder);
  }

  private void tenantFailure(Object builder, String context) {
    assertThat(assertThrows(InvocationTargetException.class, () -> build(builder)).getCause())
        .isInstanceOf(TenantUsageException.class)
        .hasMessageContaining(context);
  }

  private InputBase rawInput(Class<?> type, Map<String, Object> data) throws Exception {
    var constructor =
        type.getDeclaredConstructor(InternalContext.class, Map.class, GraphQLInputObjectType.class);
    constructor.setAccessible(true);
    return (InputBase) constructor.newInstance(null, data, null);
  }

  private ExecutionContext contextForSchema() throws Exception {
    EngineSchema schema;
    try (var stream =
        Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream(getSchemaResource()))) {
      var registry =
          new SchemaParser().parse(new InputStreamReader(stream, StandardCharsets.UTF_8));
      schema = new EngineSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(registry));
    }
    return (ExecutionContext)
        Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[] {ExecutionContext.class, InternalContext.class},
            (proxy, method, args) -> {
              if (method.getName().equals("getSchema")) return schema;
              if (method.getName().equals("getArgumentsInputType")) {
                return InputTypeFactory.argumentsInputType(
                    (String) args[0], (String) args[1], (String) args[2], schema);
              }
              throw new UnsupportedOperationException(method.toString());
            });
  }
}
