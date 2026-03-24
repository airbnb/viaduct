package viaduct.engine.runtime.graphql_java

import graphql.GraphQLContext
import graphql.execution.values.InputInterceptor
import graphql.execution.values.legacycoercing.LegacyCoercingInputInterceptor
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputType
import java.util.Locale
import viaduct.graphql.Scalars

/**
 * Prior to graphql-java v22.0, GJ was lax in how it would coerce inputs.
 * For example, A "true" string value could be coerced into a boolean value for a GraphQL Boolean type,
 * and an already-parsed Instant value could be supplied to a DateTime value.
 *
 * GJ v22 tightened coercion rules to match the behavior of graphql-js. To reuse the same example,
 * a GraphQL Boolean coercing will now only accept a true/false boolean value, and the DateTime coercing
 * will only accept a String value
 *
 * This class provides an [InputInterceptor] that can coerce input values to match the behavior
 * in use by clients and other viaduct tenants
 */
internal object LegacyInputInterceptor : InputInterceptor {
    private val gjLegacyInterceptor = LegacyCoercingInputInterceptor.migratesValues()

    override fun intercept(
        value: Any?,
        graphQLType: GraphQLInputType,
        graphqlContext: GraphQLContext,
        locale: Locale
    ): Any? =
        // Tenant fragments are exposed as a Map<String, Any?> -- tenants often put
        // already-coerced values into this map, which cannot be re-coerced after graphql-java
        // v22.0. For types whose uncoerced values are represented as a String, we reserialize them
        if (value == null) {
            null
        } else if (Scalars.DateTimeScalar.equals(graphQLType)) {
            Scalars.DateTimeScalar.coercing.serialize(value, graphqlContext, locale)
        } else if (Scalars.GraphQLLong.equals(graphQLType)) {
            value.toString()
        } else if (graphQLType is GraphQLEnumType) {
            value.toString()
        } else {
            gjLegacyInterceptor.intercept(value, graphQLType, graphqlContext, locale)
        }
}
