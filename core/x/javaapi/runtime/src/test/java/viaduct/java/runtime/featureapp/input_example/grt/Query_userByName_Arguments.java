package viaduct.java.runtime.featureapp.input_example.grt;

import org.jspecify.annotations.Nullable;
import viaduct.java.api.types.Arguments;

/**
 * Arguments for Query.userByName field.
 *
 * <p>Maps to: userByName(input: UserInput!, limit: Int): User
 */
public class Query_userByName_Arguments implements Arguments {

  private UserInput input;
  private @Nullable Integer limit;

  public UserInput getInput() {
    return input;
  }

  public void setInput(UserInput input) {
    this.input = input;
  }

  public @Nullable Integer getLimit() {
    return limit;
  }

  public void setLimit(@Nullable Integer limit) {
    this.limit = limit;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private UserInput input;
    private @Nullable Integer limit;

    public Builder input(UserInput input) {
      this.input = input;
      return this;
    }

    public Builder limit(@Nullable Integer limit) {
      this.limit = limit;
      return this;
    }

    public Query_userByName_Arguments build() {
      Query_userByName_Arguments obj = new Query_userByName_Arguments();
      obj.input = this.input;
      obj.limit = this.limit;
      return obj;
    }
  }
}
