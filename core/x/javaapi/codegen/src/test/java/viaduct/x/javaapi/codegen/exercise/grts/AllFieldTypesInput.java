package viaduct.x.javaapi.codegen.exercise.grts;

import graphql.schema.GraphQLInputObjectType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import viaduct.java.api.context.ExecutionContext;
import viaduct.java.api.internal.InputBase;
import viaduct.java.api.internal.InternalContext;
import viaduct.java.api.reflect.Field;
import viaduct.java.api.reflect.Type;
import viaduct.java.api.reflect.TypeFields;

/** An input with all field types. */
public class AllFieldTypesInput extends InputBase {

  public static final Type<AllFieldTypesInput> Reflection = Type.ofClass(AllFieldTypesInput.class);

  public static final class Fields implements TypeFields<AllFieldTypesInput> {
    private Fields() {}

    public static final Field<AllFieldTypesInput> __typename = Field.of("__typename", Reflection);
    public static final Field<AllFieldTypesInput> stringField = Field.of("stringField", Reflection);
    public static final Field<AllFieldTypesInput> intField = Field.of("intField", Reflection);
    public static final Field<AllFieldTypesInput> floatField = Field.of("floatField", Reflection);
    public static final Field<AllFieldTypesInput> boolField = Field.of("boolField", Reflection);
    public static final Field<AllFieldTypesInput> listField = Field.of("listField", Reflection);
    public static final Field<AllFieldTypesInput> bigDecimalField =
        Field.of("bigDecimalField", Reflection);
    public static final Field<AllFieldTypesInput> bigIntegerField =
        Field.of("bigIntegerField", Reflection);
    public static final Field<AllFieldTypesInput> jsonField = Field.of("jsonField", Reflection);
    public static final Field<AllFieldTypesInput> jsonListField =
        Field.of("jsonListField", Reflection);
  }

  AllFieldTypesInput(
      InternalContext context,
      Map<String, Object> data,
      GraphQLInputObjectType graphQLInputObjectType) {
    super(context, data, graphQLInputObjectType);
  }

  /**
   * Returns whether this input contains a value for {@code field} after GraphQL defaults are
   * applied. Explicit {@code null} counts as present. An omitted field with a schema default is
   * present; an omitted field without a default is absent.
   */
  public boolean isPresent(Field<AllFieldTypesInput> field) {
    return isFieldPresent(field);
  }

  public String getStringField() {
    return get("stringField");
  }

  public Integer getIntField() {
    return get("intField");
  }

  public Double getFloatField() {
    return get("floatField");
  }

  public Boolean getBoolField() {
    return get("boolField");
  }

  public List<String> getListField() {
    return getScalarList("listField");
  }

  public BigDecimal getBigDecimalField() {
    return get("bigDecimalField");
  }

  public BigInteger getBigIntegerField() {
    return get("bigIntegerField");
  }

  public Object getJsonField() {
    return get("jsonField");
  }

  public List<Object> getJsonListField() {
    return getScalarList("jsonListField");
  }

  public static Builder builder(ExecutionContext context) {
    return new Builder(InternalContext.from(context));
  }

  public static class Builder {
    private final InternalContext __context;
    private final GraphQLInputObjectType graphQLInputObjectType;
    private final Map<String, Object> data = new LinkedHashMap<>();

    private Builder(InternalContext __context) {
      this.__context = __context;
      this.graphQLInputObjectType =
          (GraphQLInputObjectType) __context.getSchema().getSchema().getType("AllFieldTypesInput");
    }

    public Builder stringField(String stringField) {
      data.put("stringField", stringField);
      return this;
    }

    public Builder intField(Integer intField) {
      data.put("intField", intField);
      return this;
    }

    public Builder floatField(Double floatField) {
      data.put("floatField", floatField);
      return this;
    }

    public Builder boolField(Boolean boolField) {
      data.put("boolField", boolField);
      return this;
    }

    public Builder listField(List<String> listField) {
      data.put("listField", listField);
      return this;
    }

    public Builder bigDecimalField(BigDecimal bigDecimalField) {
      data.put("bigDecimalField", bigDecimalField);
      return this;
    }

    public Builder bigIntegerField(BigInteger bigIntegerField) {
      data.put("bigIntegerField", bigIntegerField);
      return this;
    }

    public Builder jsonField(Object jsonField) {
      data.put("jsonField", jsonField);
      return this;
    }

    public Builder jsonListField(List<Object> jsonListField) {
      data.put("jsonListField", jsonListField);
      return this;
    }

    public AllFieldTypesInput build() {
      return new AllFieldTypesInput(__context, new LinkedHashMap<>(data), graphQLInputObjectType);
    }
  }
}
