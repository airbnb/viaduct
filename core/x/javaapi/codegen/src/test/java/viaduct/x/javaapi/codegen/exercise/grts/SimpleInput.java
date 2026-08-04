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

/** A simple input with basic fields. */
public class SimpleInput extends InputBase {

  public static final Type<SimpleInput> Reflection = Type.ofClass(SimpleInput.class);

  public static final class Fields implements TypeFields<SimpleInput> {
    private Fields() {}

    public static final Field<SimpleInput> __typename = Field.of("__typename", Reflection);
    public static final Field<SimpleInput> name = Field.of("name", Reflection);
    public static final Field<SimpleInput> count = Field.of("count", Reflection);
  }

  SimpleInput(
      InternalContext context,
      Map<String, Object> data,
      GraphQLInputObjectType graphQLInputObjectType) {
    super(context, data, graphQLInputObjectType);
  }

  public String getName() {
    return get("name");
  }

  public Integer getCount() {
    return get("count");
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

    public Builder name(String name) {
      data.put("name", name);
      return this;
    }

    public Builder count(Integer count) {
      data.put("count", count);
      return this;
    }

    public SimpleInput build() {
      return new SimpleInput(__context, new LinkedHashMap<>(data), null);
    }
  }
}
