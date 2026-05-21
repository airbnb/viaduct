package viaduct.x.javaapi.codegen.exercise.grts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import viaduct.java.api.internal.InputBase;

/** An input with all field types. */
public class AllFieldTypesInput extends InputBase {

  public AllFieldTypesInput(Map<String, Object> data) {
    super(data);
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

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final Map<String, Object> data = new LinkedHashMap<>();

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

    public AllFieldTypesInput build() {
      return new AllFieldTypesInput(data);
    }
  }
}
