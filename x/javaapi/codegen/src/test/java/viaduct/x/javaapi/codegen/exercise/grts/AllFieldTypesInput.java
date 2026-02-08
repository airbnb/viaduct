package viaduct.x.javaapi.codegen.exercise.grts;

import java.util.List;
import viaduct.java.api.types.GraphQLInput;

/** An input with all field types. */
public class AllFieldTypesInput implements GraphQLInput {

  private String stringField;
  private Integer intField;
  private Double floatField;
  private Boolean boolField;
  private List<String> listField;

  public String getStringField() {
    return this.stringField;
  }

  public void setStringField(String stringField) {
    this.stringField = stringField;
  }

  public Integer getIntField() {
    return this.intField;
  }

  public void setIntField(Integer intField) {
    this.intField = intField;
  }

  public Double getFloatField() {
    return this.floatField;
  }

  public void setFloatField(Double floatField) {
    this.floatField = floatField;
  }

  public Boolean getBoolField() {
    return this.boolField;
  }

  public void setBoolField(Boolean boolField) {
    this.boolField = boolField;
  }

  public List<String> getListField() {
    return this.listField;
  }

  public void setListField(List<String> listField) {
    this.listField = listField;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String stringField;
    private Integer intField;
    private Double floatField;
    private Boolean boolField;
    private List<String> listField;

    public Builder stringField(String stringField) {
      this.stringField = stringField;
      return this;
    }

    public Builder intField(Integer intField) {
      this.intField = intField;
      return this;
    }

    public Builder floatField(Double floatField) {
      this.floatField = floatField;
      return this;
    }

    public Builder boolField(Boolean boolField) {
      this.boolField = boolField;
      return this;
    }

    public Builder listField(List<String> listField) {
      this.listField = listField;
      return this;
    }

    public AllFieldTypesInput build() {
      AllFieldTypesInput obj = new AllFieldTypesInput();
      obj.stringField = this.stringField;
      obj.intField = this.intField;
      obj.floatField = this.floatField;
      obj.boolField = this.boolField;
      obj.listField = this.listField;
      return obj;
    }
  }
}
