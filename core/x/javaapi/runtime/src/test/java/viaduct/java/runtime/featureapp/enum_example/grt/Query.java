package viaduct.java.runtime.featureapp.enum_example.grt;

/**
 * Query GRT representing the GraphQL Query type.
 *
 * <p>This is a minimal Query implementation used for testing. In production, this would be
 * generated from the GraphQL schema with getters/setters for all Query fields.
 */
public class Query implements viaduct.java.api.types.Query {

  private String _empty;
  private Status currentStatus;

  public String get_empty() {
    return this._empty;
  }

  public void set_empty(String _empty) {
    this._empty = _empty;
  }

  public Status getCurrentStatus() {
    return this.currentStatus;
  }

  public void setCurrentStatus(Status currentStatus) {
    this.currentStatus = currentStatus;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private String _empty;
    private Status currentStatus;

    public Builder _empty(String _empty) {
      this._empty = _empty;
      return this;
    }

    public Builder currentStatus(Status currentStatus) {
      this.currentStatus = currentStatus;
      return this;
    }

    public Query build() {
      Query obj = new Query();
      obj._empty = this._empty;
      obj.currentStatus = this.currentStatus;
      return obj;
    }
  }
}
