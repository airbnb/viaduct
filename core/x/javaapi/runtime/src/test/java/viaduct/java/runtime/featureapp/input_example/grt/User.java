package viaduct.java.runtime.featureapp.input_example.grt;

import org.jspecify.annotations.Nullable;
import viaduct.java.api.types.CompositeOutput;

/**
 * Output type representing a user.
 *
 * <p>Maps to:
 *
 * <pre>
 * type User {
 *     name: String!
 *     age: Int
 * }
 * </pre>
 */
public class User implements CompositeOutput {

  private String name;
  private @Nullable Integer age;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public @Nullable Integer getAge() {
    return age;
  }

  public void setAge(@Nullable Integer age) {
    this.age = age;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String name;
    private @Nullable Integer age;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder age(@Nullable Integer age) {
      this.age = age;
      return this;
    }

    public User build() {
      User obj = new User();
      obj.name = this.name;
      obj.age = this.age;
      return obj;
    }
  }
}
