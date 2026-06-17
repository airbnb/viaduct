package viaduct.x.javaapi.codegen.exercise.grts;

import graphql.schema.GraphQLInputObjectType;
import java.util.LinkedHashMap;
import java.util.Map;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;

/** An input with a description to test Javadoc generation. */
public class InputWithDescription extends InputBase {

  public InputWithDescription(
      InternalContext context,
      Map<String, Object> data,
      GraphQLInputObjectType graphQLInputObjectType) {
    super(context, data, graphQLInputObjectType);
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
