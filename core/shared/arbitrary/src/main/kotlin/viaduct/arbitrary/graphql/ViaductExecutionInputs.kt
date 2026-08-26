package viaduct.arbitrary.graphql

import graphql.schema.GraphQLSchema
import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import java.util.Collections
import java.util.WeakHashMap
import viaduct.arbitrary.common.Config
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.scopes.SchemaView
import viaduct.graphql.scopes.ScopedSchemaBuilder
import viaduct.service.api.ExecutionInput
import viaduct.service.runtime.schemaScopingMode

/**
 * Generate an arbitrary Viaduct [ExecutionInput] for the provided schema and config.
 *
 * This delegates to [graphQLExecutionInput] and adapts the generated [graphql.ExecutionInput]
 * into Viaduct's service API execution input type.
 *
 * Documents are generated against [schema]'s [SchemaView.Base] view rather than [schema] itself.
 */
fun Arb.Companion.viaductExecutionInput(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<ExecutionInput> =
    Arb.graphQLExecutionInput(schema.baseView, cfg).map { input ->
        ExecutionInput.create(
            operationText = input.query,
            operationName = input.operationName,
            variables = input.variables,
        )
    }

/**
 * Cache of derived base views, keyed on the identity of the input schema.
 *
 * Deriving a base view costs a full schema traversal, and callers commonly build a fresh
 * [viaductExecutionInput] Arb for every generated sample. Weak keys let the entry go away with the
 * schema it describes.
 */
private val baseViews = Collections.synchronizedMap(WeakHashMap<GraphQLSchema, ViaductSchema>())

/**
 * The client-facing [SchemaView.Base] view of this schema, or this schema itself if it has no
 * fields to filter.
 */
private val ViaductSchema.baseView: ViaductSchema get() =
    baseViews.getOrPut(schema) {
        val filtered = ScopedSchemaBuilder(
            inputSchema = schema,
            scopingMode = schemaScopingMode(),
            additionalVisitorConstructors = emptyList(),
        ).build(SchemaView.Base).filtered

        if (filtered === schema) this else copy(schema = filtered)
    }
