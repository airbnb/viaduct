package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.introspection.Introspection as GJIntrospection
import graphql.introspection.Introspection.SchemaMetaFieldDef
import graphql.introspection.Introspection.TypeMetaFieldDef
import graphql.introspection.Introspection.TypeNameMetaFieldDef
import graphql.introspection.IntrospectionDisabledError
import viaduct.engine.runtime.execution.FieldExecutionHelpers.collectFields

/**
 * A viaduct-specific validator for Introspection queries.
 * If the provided [ExecutionParameters.executionContext] contains a [graphql.GraphQLContext] and
 * introspection has been disabled via [GJIntrospection.INTROSPECTION_DISABLED], then this component
 * will return an  [IntrospectionDisabledError] if the query selects the `__type` or `__schema`
 * introspection fields.
 *
 * This is a viaduct fork of graphql-java's [GJIntrospection]. Some notable differences are:
 * - [GJIntrospection.enabledJvmWide] can be used to configure introspection across the whole JVM,
 *   rather than per-request. This functionality is not supported by this component.
 *
 * - graphql-java will run an additional [graphql.introspection.GoodFaithIntrospection] check,
 *   which will reject expensive introspection queries even when introspection is allowed.
 *   This functionality is not supported by this component.
 */
object Introspection {
    internal val disallowedIntrospectionFields: Set<String> = setOf(SchemaMetaFieldDef.name, TypeMetaFieldDef.name)
    internal val allowedIntrospectionFields: Set<String> = setOf(TypeNameMetaFieldDef.name)

    /**
     * A viaduct-specific port of [GJIntrospection.isIntrospectionSensible], see the docs of [Introspection] for errata.
     *
     * @return a [GraphQLError] if introspection was detected and is not allowed. Otherwise, null
     */
    fun checkIntrospection(parameters: ExecutionParameters): GraphQLError? {
        val ctx = parameters.executionContext.graphQLContext

        // if the value of INTROSPECTION_DISABLED is not true then return early
        if (!ctx.getBoolean(GJIntrospection.INTROSPECTION_DISABLED, false)) {
            return null
        }

        val queryType = parameters.graphQLSchema.queryType

        val collected = collectFields(queryType, parameters)
        return collected.selections.firstNotNullOfOrNull {
            when (val sel = it) {
                is QueryPlan.CollectedField -> {
                    if (sel.fieldName in disallowedIntrospectionFields) {
                        IntrospectionDisabledError(sel.mergedField.singleField.sourceLocation)
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
    }
}
