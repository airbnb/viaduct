package viaduct.graphql.schema.validation

import viaduct.graphql.Scalars

/**
 * Standard GraphQL built-in types.
 *
 * Using a centralized object ensures consistency across validation rules
 * and enables IDE support for code completion and refactoring.
 */
object GraphQLBuiltIns {
    /**
     * The five built-in GraphQL scalar types as defined in the GraphQL specification.
     * @see <a href="https://spec.graphql.org/October2021/#sec-Scalars.Built-in-Scalars">GraphQL Spec</a>
     */
    val SCALARS: Set<String> = setOf("String", "Int", "Float", "Boolean", "ID")

    /**
     * Scalar types provided by the Viaduct framework (not defined in the GraphQL spec).
     * Derived from [Scalars.viaductStandardScalars] to stay in sync with the framework's
     * actual scalar definitions.
     */
    val VIADUCT_SCALARS: Set<String> = Scalars.viaductStandardScalars.map { it.name }.toSet()
}
