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

/** An input with all field types. */
public class AllFieldTypesInput extends InputBase {

  AllFieldTypesInput(
      InternalContext context,
      Map<String, Object> data,
      GraphQLInputObjectType graphQLInputObjectType) {
    super(context, data, graphQLInputObjectType);
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
    private final Map<String, Object> data = new LinkedHashMap<>();

    private Builder(InternalContext __context) {
      this.__context = __context;
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
      return new AllFieldTypesInput(__context, new LinkedHashMap<>(data), null);
    }
  }
}
