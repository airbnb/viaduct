@file:OptIn(InternalApi::class)

package viaduct.arbitrary.graphql

import graphql.ExecutionInput
import graphql.language.AstPrinter
import graphql.language.Document
import graphql.language.OperationDefinition
import graphql.schema.GraphQLTypeUtil
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.flatMap
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.of
import viaduct.api.internal.EngineValueConv
import viaduct.apiannotations.InternalApi
import viaduct.arbitrary.common.Config
import viaduct.engine.api.ViaductSchema

/**
 * Generate an arbitrary GraphQL [ExecutionInput] for the provided schema and config.
 * The returned ExecutionInput is guaranteed to be executable and valid according to the rules
 * at
 *   https://spec.graphql.org/draft/#sec-Documents
 */
fun Arb.Companion.graphQLExecutionInput(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<ExecutionInput> =
    Arb.graphQLDocument(schema, cfg).flatMap { doc ->
        Arb.graphQLExecutionInput(schema, doc, cfg)
    }

/**
 * Generate an arbitrary GraphQL [ExecutionInput] for the provided document and schema.
 * The returned ExecutionInput is guaranteed to be executable and valid according to the rules
 * at
 *   https://spec.graphql.org/draft/#sec-Documents
 */
fun Arb.Companion.graphQLExecutionInput(
    schema: ViaductSchema,
    doc: Document,
    cfg: Config = Config.default
): Arb<ExecutionInput> =
    arbitrary { rs ->
        GraphQLExecutionInputGen(schema, cfg, rs).gen(doc)
    }

private class GraphQLExecutionInputGen(
    val schema: ViaductSchema,
    val cfg: Config,
    val rs: RandomSource
) {
    fun gen(doc: Document): ExecutionInput {
        val operations = doc.getDefinitionsOfType(OperationDefinition::class.java)
        val operation = Arb.of(operations).next(rs)

        val operationName: String? =
            if (operations.size == 1 && rs.sampleWeight(cfg[AnonymousOperationWeight])) {
                null
            } else {
                operation.name
            }

        val variables = operation.variableDefinitions.fold(emptyMap<String, Any?>()) { acc, def ->
            val schemaType = def.type.asSchemaType(schema)
            val canInull = (def.defaultValue != null) || GraphQLTypeUtil.isNullable(schemaType)
            if (canInull && rs.sampleWeight(cfg[ImplicitNullValueWeight])) {
                acc
            } else {
                val type = def.type.asSchemaType(schema)
                val value = Arb.ir(schema, type, cfg)
                    .map {
                        val conv = EngineValueConv(schema, type, null)
                        conv.invert(it)
                    }
                    .next(rs)
                acc + (def.name to value)
            }
        }

        return ExecutionInput
            .newExecutionInput()
            .query(AstPrinter.printAst(doc))
            .apply { operationName?.let { operationName(it) } }
            .variables(variables)
            .build()
    }
}
