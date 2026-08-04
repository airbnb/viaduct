package viaduct.x.javaapi.codegen.exercise.grts;

import graphql.schema.GraphQLInputObjectType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.reflect.CompositeField;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;

/** An input with enum and list fields. */
public class ComplexInput extends InputBase {

  public static final Type<ComplexInput> Reflection = Type.ofClass(ComplexInput.class);

  public static final class Fields implements TypeFields<ComplexInput> {
    private Fields() {}

    public static final Field<ComplexInput> __typename = Field.of("__typename", Reflection);
    public static final CompositeField<ComplexInput, StatusEnum> status =
        CompositeField.of("status", Reflection, StatusEnum.Reflection);
    public static final Field<ComplexInput> tags = Field.of("tags", Reflection);
  }

  ComplexInput(
      InternalContext context,
      Map<String, Object> data,
      GraphQLInputObjectType graphQLInputObjectType) {
    super(context, data, graphQLInputObjectType);
  }

  public StatusEnum getStatus() {
    return getEnum("status", StatusEnum.class);
  }

  public List<String> getTags() {
    return getScalarList("tags");
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

    public Builder status(StatusEnum status) {
      data.put("status", status);
      return this;
    }

    public Builder tags(List<String> tags) {
      data.put("tags", tags);
      return this;
    }

    public ComplexInput build() {
      return new ComplexInput(__context, new LinkedHashMap<>(data), null);
    }
  }
}
