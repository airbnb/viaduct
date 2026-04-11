package viaduct.x.javaapi.codegen.exercise.grts;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import viaduct.java.api.internal.JavaInputBase;

/** An input with enum and list fields. */
public class ComplexInput extends JavaInputBase {

  public ComplexInput(Map<String, Object> data) {
    super(data);
  }

  public StatusEnum getStatus() {
    return getEnum("status", StatusEnum.class);
  }

  public List<String> getTags() {
    return getScalarList("tags");
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final Map<String, Object> data = new LinkedHashMap<>();

    public Builder status(StatusEnum status) {
      data.put("status", status);
      return this;
    }

    public Builder tags(List<String> tags) {
      data.put("tags", tags);
      return this;
    }

    public ComplexInput build() {
      return new ComplexInput(data);
    }
  }
}
