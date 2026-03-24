package viaduct.x.javaapi.codegen.exercise.grts;

import viaduct.java.api.types.GraphQLInput;

/** An input with a description to test Javadoc generation. */
public class InputWithDescription implements GraphQLInput {

  private String value;

  public String getValue() {
    return this.value;
  }

  public void setValue(String value) {
    this.value = value;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String value;

    public Builder value(String value) {
      this.value = value;
      return this;
    }

    public InputWithDescription build() {
      InputWithDescription obj = new InputWithDescription();
      obj.value = this.value;
      return obj;
    }
  }
}
