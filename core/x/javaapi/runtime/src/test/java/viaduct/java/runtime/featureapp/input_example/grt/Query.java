package viaduct.java.runtime.featureapp.input_example.grt;

import org.jspecify.annotations.Nullable;

/**
 * Query GRT representing the GraphQL Query type.
 *
 * <p>This is a minimal Query implementation used for testing. In production, this would be
 * generated from the GraphQL schema with getters/setters for all Query fields.
 */
public class Query implements viaduct.java.api.types.Query {

  private @Nullable String _empty;
  private @Nullable User userByName;

  public @Nullable String get_empty() {
    return this._empty;
  }

  public void set_empty(@Nullable String _empty) {
    this._empty = _empty;
  }

  public @Nullable User getUserByName() {
    return this.userByName;
  }

  public void setUserByName(@Nullable User userByName) {
    this.userByName = userByName;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private @Nullable String _empty;
    private @Nullable User userByName;

    public Builder _empty(@Nullable String _empty) {
      this._empty = _empty;
      return this;
    }

    public Builder userByName(@Nullable User userByName) {
      this.userByName = userByName;
      return this;
    }

    public Query build() {
      Query obj = new Query();
      obj._empty = this._empty;
      obj.userByName = this.userByName;
      return obj;
    }
  }
}
