package viaduct.java.runtime.featureapp.object_example.grt;

import org.jspecify.annotations.Nullable;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.GraphQLObject;

/**
 * ObjectType representing a person with nested Address.
 *
 * <p>Maps to:
 *
 * <pre>
 * type Person {
 *     name: String!
 *     age: Int
 *     address: Address
 *     fullAddress: String @resolver
 * }
 * </pre>
 *
 * <p>Note: Implements both CompositeOutput (for use as return type) and GraphQLObject (required for
 * object field resolvers that operate on this type).
 */
public class Person implements CompositeOutput, GraphQLObject {

  private String name;
  private @Nullable Integer age;
  private @Nullable Address address;
  private @Nullable String fullAddress;

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

  public @Nullable Address getAddress() {
    return address;
  }

  public void setAddress(@Nullable Address address) {
    this.address = address;
  }

  public @Nullable String getFullAddress() {
    return fullAddress;
  }

  public void setFullAddress(@Nullable String fullAddress) {
    this.fullAddress = fullAddress;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String name;
    private @Nullable Integer age;
    private @Nullable Address address;
    private @Nullable String fullAddress;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Builder age(@Nullable Integer age) {
      this.age = age;
      return this;
    }

    public Builder address(@Nullable Address address) {
      this.address = address;
      return this;
    }

    public Builder fullAddress(@Nullable String fullAddress) {
      this.fullAddress = fullAddress;
      return this;
    }

    public Person build() {
      Person obj = new Person();
      obj.name = this.name;
      obj.age = this.age;
      obj.address = this.address;
      obj.fullAddress = this.fullAddress;
      return obj;
    }
  }
}
