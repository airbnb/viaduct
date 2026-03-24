package viaduct.x.javaapi.codegen.exercise.grts;

import java.util.List;
import viaduct.java.api.types.GraphQLInput;

/** An input with enum and list fields. */
public class ComplexInput implements GraphQLInput {

  private StatusEnum status;
  private List<String> tags;

  public StatusEnum getStatus() {
    return this.status;
  }

  public void setStatus(StatusEnum status) {
    this.status = status;
  }

  public List<String> getTags() {
    return this.tags;
  }

  public void setTags(List<String> tags) {
    this.tags = tags;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private StatusEnum status;
    private List<String> tags;

    public Builder status(StatusEnum status) {
      this.status = status;
      return this;
    }

    public Builder tags(List<String> tags) {
      this.tags = tags;
      return this;
    }

    public ComplexInput build() {
      ComplexInput obj = new ComplexInput();
      obj.status = this.status;
      obj.tags = this.tags;
      return obj;
    }
  }
}
