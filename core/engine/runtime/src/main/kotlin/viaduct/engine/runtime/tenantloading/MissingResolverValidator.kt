package viaduct.engine.runtime.tenantloading

import graphql.schema.GraphQLObjectType
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.validation.Validator

/**
 * Validates that every @resolver-annotated field and object type in the schema
 * has a corresponding dispatcher registered in the [DispatcherRegistry].
 */
class MissingResolverValidator(
    private val schema: ViaductSchema,
) : Validator<MissingResolverValidationCtx> {
    @Suppress("PARAMETER_NAME_CHANGED_ON_OVERRIDE", "LABEL_NAME_CLASH")
    override fun validate(ctx: MissingResolverValidationCtx) {
        val missingFieldResolvers = mutableListOf<String>()
        val missingNodeResolvers = mutableListOf<String>()

        schema.schema.allTypesAsList.forEach { type ->
            if (type !is GraphQLObjectType || type.name.startsWith("__")) return@forEach

            if (type.hasAppliedDirective(RESOLVER_DIRECTIVE)) {
                if (ctx.dispatcherRegistry.getNodeResolverDispatcher(type.name) == null) {
                    missingNodeResolvers.add(type.name)
                }
            }

            type.fieldDefinitions.forEach { field ->
                if (field.name.startsWith("__")) return@forEach
                if (field.hasAppliedDirective(RESOLVER_DIRECTIVE)) {
                    if (ctx.dispatcherRegistry.getFieldResolverDispatcher(type.name, field.name) == null) {
                        missingFieldResolvers.add("${type.name}.${field.name}")
                    }
                }
            }
        }

        if (missingFieldResolvers.isNotEmpty() || missingNodeResolvers.isNotEmpty()) {
            throw MissingResolversException(missingFieldResolvers, missingNodeResolvers)
        }
    }

    companion object {
        private const val RESOLVER_DIRECTIVE = "resolver"
    }
}

data class MissingResolverValidationCtx(
    val dispatcherRegistry: DispatcherRegistry,
)

class MissingResolversException(
    val missingFieldResolvers: List<String>,
    val missingNodeResolvers: List<String>,
) : RuntimeException(
        buildString {
            append("Missing resolver implementations for @resolver-annotated schema elements. ")
            if (missingFieldResolvers.isNotEmpty()) {
                append("Fields without resolvers: ${missingFieldResolvers.sorted().joinToString(", ")}. ")
            }
            if (missingNodeResolvers.isNotEmpty()) {
                append("Types without node resolvers: ${missingNodeResolvers.sorted().joinToString(", ")}.")
            }
        }
    )
