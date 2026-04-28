package viaduct.engine.runtime.graphql_java

import graphql.execution.values.InputInterceptor
import graphql.introspection.Introspection
import graphql.parser.ParserOptions

/**
 * Container for graphql-java per-execution configurations.
 * These can be converted to a graphql-java [graphql.GraphQLContext].
 */
data class GraphQLJavaConfig(
    val parserOptions: ParserOptions?,
    val inputInterceptor: InputInterceptor?,
    val introspectionEnabled: Boolean?,
) {
    /**
     * Generate a Map that is suitable for populating a graphql-java [graphql.GraphQLContext]
     */
    fun asMap(): Map<Any, Any?> {
        val map = mutableMapOf<Any, Any?>()
        if (parserOptions != null) {
            map.put(ParserOptions::class.java, parserOptions)
        }
        if (inputInterceptor != null) {
            map.put(InputInterceptor::class.java, inputInterceptor)
        }
        if (introspectionEnabled != null) {
            map.put(Introspection.INTROSPECTION_DISABLED, !introspectionEnabled)
        }
        return map.toMap()
    }

    companion object {
        val none: GraphQLJavaConfig = GraphQLJavaConfig(null, null, null)

        val default: GraphQLJavaConfig = GraphQLJavaConfig(
            /**
             * The graphql-java ParserOptions to use when parsing a Document from a String.
             *
             * By default, graphql-java uses a conservative configuration of ParserOptions that
             * causes a [graphql.parser.exceptions.ParseCancelledException] to be thrown when parsing
             * large documents. While this mechanism is designed to protect graphql services from DDOS
             * attacks, it has a tendency to throw exceptions for legit queries.
             *
             * Architects using these options should be aware that they are trading off some protection against
             * malicious queries in exchange for enabling larger queries to be parsed successfully, and
             * should consider other mechanisms for protecting their services against DDOS attacks.
             *
             * The default here uses a maximally permissive set of ParserOptions. Services that want a
             * more conservative configuration should override this property.
             */
            parserOptions = ParserOptions.getDefaultSdlParserOptions(),
            /**
             * In v22, graphql-java tightened the builtin coercing rules to match graphql-js, which does not
             * coerce values like "true" to a GraphQL Boolean.
             *
             * We use a [viaduct.engine.LegacyInputInterceptor] to transform previously-acceptable inputs into a parseable form.
             * Services that want different behavior should override this to `null` to get the default behavior
             * or provide a different implementation of [InputInterceptor]
             */
            inputInterceptor = LegacyInputInterceptor,
            /***
             * Whether or not introspection is enabled for the given request. Enabled by default.
             * Whether or not introspection is enabled is controlled by the caller of GraphQL.execute.
             */
            introspectionEnabled = true,
        )
    }
}
