package viaduct.java.runtime.featureapp.object_example.grt;

import viaduct.java.api.types.Arguments;

/**
 * Arguments for Query.person field.
 *
 * <p>Maps to: person(name: String!): Person
 */
public class Query_person_Arguments implements Arguments {

  private String name;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String name;

    public Builder name(String name) {
      this.name = name;
      return this;
    }

    public Query_person_Arguments build() {
      Query_person_Arguments obj = new Query_person_Arguments();
      obj.name = this.name;
      return obj;
    }
  }
}
