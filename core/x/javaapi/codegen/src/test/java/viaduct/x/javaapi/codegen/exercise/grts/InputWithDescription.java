package viaduct.x.javaapi.codegen.exercise.grts;

import graphql.schema.GraphQLInputObjectType;
import java.util.LinkedHashMap;
import java.util.Map;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;

/** An input with a description to test Javadoc generation. */
public class InputWithDescription extends InputBase {

  public static final Type<InputWithDescription> Reflection =
      Type.ofClass(InputWithDescription.class);

  public static final class Fields implements TypeFields<InputWithDescription> {
    private Fields() {}

    public static final Field<InputWithDescription> __typename = Field.of("__typename", Reflection);
    public static final Field<InputWithDescription> value = Field.of("value", Reflection);
  }

  InputWithDescription(
      InternalContext context,
      Map<String, Object> data,
      GraphQLInputObjectType graphQLInputObjectType) {
    super(context, data, graphQLInputObjectType);
  }

  /**
   * Returns whether {@code field} was explicitly provided, including an explicit {@code null}.
   *
   * <p>This is meaningful only for top-level fields. graphql-java applies input coercion, including
   * default values, to nested input objects, so presence cannot be determined for fields nested
   * more deeply than this input.
   */
  public boolean isPresent(Field<InputWithDescription> field) {
    return isFieldPresent(field);
  }

  public String getValue() {
    return get("value");
  }

  public static Builder builder(ExecutionContext context) {
    return new Builder(InternalContext.from(context));
  }

  public static class Builder {
    private final InternalContext __context;
    private final Map<String, Object> data = new LinkedHashMap<>();

    private Builder(InternalContext __context) {
      this.__context = __context;
    }

    public Builder value(String value) {
      data.put("value", value);
      return this;
    }

    public InputWithDescription build() {
      return new InputWithDescription(__context, new LinkedHashMap<>(data), null);
    }
  }
}
