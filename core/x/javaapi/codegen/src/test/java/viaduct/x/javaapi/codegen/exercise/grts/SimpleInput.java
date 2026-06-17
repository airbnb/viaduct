package viaduct.x.javaapi.codegen.exercise.grts;

import graphql.schema.GraphQLInputObjectType;
import java.util.LinkedHashMap;
import java.util.Map;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;

/** A simple input with basic fields. */
public class SimpleInput extends InputBase {

  public SimpleInput(
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
