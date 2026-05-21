package viaduct.x.javaapi.codegen.exercise.grts;

import java.util.LinkedHashMap;
import java.util.Map;
import viaduct.java.api.internal.InputBase;

/** An input with a description to test Javadoc generation. */
public class InputWithDescription extends InputBase {

  public InputWithDescription(Map<String, Object> data) {
    super(data);
  }

  public String getValue() {
    return get("value");
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private final Map<String, Object> data = new LinkedHashMap<>();

    public Builder value(String value) {
      data.put("value", value);
      return this;
    }

    public InputWithDescription build() {
      return new InputWithDescription(data);
    }
  }
}
