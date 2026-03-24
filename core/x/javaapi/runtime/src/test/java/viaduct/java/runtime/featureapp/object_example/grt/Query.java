package viaduct.java.runtime.featureapp.object_example.grt;

import org.jspecify.annotations.Nullable;

/**
 * Query GRT representing the GraphQL Query type.
 *
 * <p>This is a minimal Query implementation used for testing. In production, this would be
 * generated from the GraphQL schema with getters/setters for all Query fields.
 */
public class Query implements viaduct.java.api.types.Query {

  private @Nullable String _empty;
  private @Nullable Person person;

  public @Nullable String get_empty() {
    return this._empty;
  }

  public void set_empty(@Nullable String _empty) {
    this._empty = _empty;
  }

  public @Nullable Person getPerson() {
    return this.person;
  }

  public void setPerson(@Nullable Person person) {
    this.person = person;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private @Nullable String _empty;
    private @Nullable Person person;

    public Builder _empty(@Nullable String _empty) {
      this._empty = _empty;
      return this;
    }

    public Builder person(@Nullable Person person) {
      this.person = person;
      return this;
    }

    public Query build() {
      Query obj = new Query();
      obj._empty = this._empty;
      obj.person = this.person;
      return obj;
    }
  }
}
