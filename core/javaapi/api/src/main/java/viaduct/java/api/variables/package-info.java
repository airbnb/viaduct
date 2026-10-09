/**
 * Dynamic GraphQL variable provisioning for Java resolvers.
 *
 * <p>Defines {@link viaduct.java.api.variables.VariablesProvider}, the Java equivalent of the
 * Kotlin {@code VariablesProvider}. A resolver may declare a nested static class that implements
 * this interface (and is annotated with {@link viaduct.java.api.annotations.Variables}) to compute
 * variable values at request time.
 */
package viaduct.java.api.variables;
