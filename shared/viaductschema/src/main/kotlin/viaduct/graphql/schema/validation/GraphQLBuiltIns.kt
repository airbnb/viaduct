package viaduct.graphql.schema.validation

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
}
