package viaduct.java.runtime.featureapp.object_example.grt;

import org.jspecify.annotations.Nullable;
import viaduct.java.api.types.CompositeOutput;
import viaduct.java.api.types.GraphQLObject;

/**
 * ObjectType representing an address.
 *
 * <p>Maps to:
 *
 * <pre>
 * type Address {
 *     street: String!
 *     city: String!
 *     country: String
 * }
 * </pre>
 *
 * <p>Note: Implements both CompositeOutput (for use as return type) and GraphQLObject (required for
 * nested object conversion in resolvers).
 */
public class Address implements CompositeOutput, GraphQLObject {

  private String street;
  private String city;
  private @Nullable String country;

  public String getStreet() {
    return street;
  }

  public void setStreet(String street) {
    this.street = street;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public @Nullable String getCountry() {
    return country;
  }

  public void setCountry(@Nullable String country) {
    this.country = country;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String street;
    private String city;
    private @Nullable String country;

    public Builder street(String street) {
      this.street = street;
      return this;
    }

    public Builder city(String city) {
      this.city = city;
      return this;
    }

    public Builder country(@Nullable String country) {
      this.country = country;
      return this;
    }

    public Address build() {
      Address obj = new Address();
      obj.street = this.street;
      obj.city = this.city;
      obj.country = this.country;
      return obj;
    }
  }
}
