package viaduct.arbitrary.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import viaduct.arbitrary.common.Config
import viaduct.engine.api.ViaductSchema
import viaduct.service.api.ExecutionInput

/**
 * Generate an arbitrary Viaduct [ExecutionInput] for the provided schema and config.
 *
 * This delegates to [graphQLExecutionInput] and adapts the generated [graphql.ExecutionInput]
 * into Viaduct's service API execution input type.
 */
fun Arb.Companion.viaductExecutionInput(
    schema: ViaductSchema,
    cfg: Config = Config.default
): Arb<ExecutionInput> =
    Arb.graphQLExecutionInput(schema, cfg).map { input ->
        ExecutionInput.create(
            operationText = input.query,
            operationName = input.operationName,
            variables = input.variables,
        )
    }
