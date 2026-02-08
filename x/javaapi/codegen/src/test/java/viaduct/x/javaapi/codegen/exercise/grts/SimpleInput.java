package viaduct.x.javaapi.codegen.exercise.grts;

import viaduct.java.api.types.GraphQLInput;

/** A simple input with basic fields. */
public class SimpleInput implements GraphQLInput {

  private String name;
  private Integer count;

  public String getName() {
    return this.name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Integer getCount() {
    return this.count;
  }

  public void setCount(Integer count) {
    this.count = count;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String name;
    private Integer count;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder count(Integer count) {
      this.count = count;
      return this;
    }

    public SimpleInput build() {
      SimpleInput obj = new SimpleInput();
      obj.name = this.name;
      obj.count = this.count;
      return obj;
    }
  }
}
