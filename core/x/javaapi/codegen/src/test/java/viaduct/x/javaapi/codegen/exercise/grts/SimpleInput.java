package viaduct.x.javaapi.codegen.exercise.grts;

import java.util.LinkedHashMap;
import java.util.Map;
import viaduct.java.api.internal.InputBase;

/** A simple input with basic fields. */
public class SimpleInput extends InputBase {

  public SimpleInput(Map<String, Object> data) {
    super(data);
  }

  public String getName() {
    return get("name");
  }

  public Integer getCount() {
    return get("count");
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final Map<String, Object> data = new LinkedHashMap<>();

    public Builder name(String name) {
      data.put("name", name);
      return this;
    }

    public Builder count(Integer count) {
      data.put("count", count);
      return this;
    }

    public SimpleInput build() {
      return new SimpleInput(data);
    }
  }
}
