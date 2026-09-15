package viaduct.x.javaapi.codegen;

/**
 * One generated field accessor: a single {@code AccessorForm} of a {@link FieldModel}, either with
 * or without the trailing alias parameter.
 *
 * <p>The accessor templates loop over these rather than over fields so that each emitted method has
 * one unconditional shape. Deriving the whole signature here also keeps the object and interface
 * templates from re-deriving names and return types independently.
 */
public record AccessorModel(
    String typeParameters, String returnType, String methodName, String parameters, String body) {

  // ST (StringTemplate) requires JavaBean-style getters
  public String getTypeParameters() {
    return typeParameters;
  }

  public String getReturnType() {
    return returnType;
  }

  public String getMethodName() {
    return methodName;
  }

  public String getParameters() {
    return parameters;
  }

  public String getBody() {
    return body;
  }
}
