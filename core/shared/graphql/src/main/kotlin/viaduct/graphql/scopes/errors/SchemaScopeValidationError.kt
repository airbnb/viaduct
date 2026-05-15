package viaduct.graphql.scopes.errors

import graphql.language.Node
import graphql.schema.GraphQLNamedSchemaElement

open class SchemaScopeValidationError(
    message: String,
    val node: Node<*>?
) : Throwable(message) {
    override val message: String?
        get() =
            super.message +
                " @ ${node?.sourceLocation?.sourceName}:${node?.sourceLocation?.line}:${node?.sourceLocation?.column}"
}

class DirectiveRetainedTypeScopeError
    private constructor(
        message: String,
        node: Node<*>?
    ) : SchemaScopeValidationError(message, node) {
        constructor(element: GraphQLNamedSchemaElement) : this(
            "Type '${element.name}' is used by a GraphQL directive and must be available in all scopes. " +
                "Use @scope(to: [\"*\"]) instead of enumerating individual scopes.",
            element.definition
        )
    }
