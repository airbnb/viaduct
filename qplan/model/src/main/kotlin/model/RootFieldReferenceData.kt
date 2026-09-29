package model

import viaduct.graphql.schema.ViaductSchema

/**
 * A symbolic instruction to resolve one Query-reachable field.
 *
 * [path] starts at Query; every field before [targetField] is a singular, argumentless object
 * field. The target's [arguments] are completely grounded and variable-free. This carrier belongs
 * to [ResolverOutputData], not [EngineOutputData], and exposes no fields before the referenced
 * resolver runs. Equality is structural over the canonical path and arguments.
 */
sealed interface RootFieldReferenceData {
    val path: List<ViaductSchema.ObjectField>

    val arguments: Arguments.Resolved

    val targetField: ViaductSchema.ObjectField
        get() = path.last()

    /** The singular composite or simple type produced by [targetField]. */
    val type: ViaductSchema.OutputTypeDef

    companion object {
        /**
         * Constructs a reference after validating its canonical path, target shape, and arguments.
         */
        fun of(
            path: List<ViaductSchema.ObjectField>,
            arguments: Arguments.Resolved,
        ): RootFieldReferenceData {
            require(path.isNotEmpty()) { "Root-field-reference path must not be empty" }
            require(path.first().containingDef.name == "Query") {
                "Root-field-reference path must start at Query"
            }
            path.zipWithNext().forEach { (field, nextField) ->
                require(field.args.isEmpty()) {
                    "Root-field-reference path field " +
                        "${field.containingDef.name}/${field.name} must be argumentless"
                }
                require(!field.outputType.isList && field.outputType.baseTypeDef == nextField.containingDef) {
                    "Root-field-reference path is not connected at " +
                        "${field.containingDef.name}/${field.name}"
                }
            }
            val targetField = path.last()
            val targetType = targetField.outputType
            val targetTypeDef = targetType.baseTypeDef
            require(
                !targetType.isList &&
                    (
                        targetTypeDef is ViaductSchema.CompositeTypeDef ||
                            targetTypeDef is ViaductSchema.SimpleTypeDef
                    ),
            ) {
                "Root-field-reference target " +
                    "${targetField.containingDef.name}/${targetField.name} must return a " +
                    "singular composite or simple value"
            }
            require(arguments.conformsToArgumentDefinition(targetField)) {
                "Root-field-reference arguments do not belong to " +
                    "${targetField.containingDef.name}/${targetField.name}"
            }
            return RootFieldReferenceDataImpl(
                path = path.toList(),
                arguments = arguments,
                type = targetTypeDef,
            )
        }

        /** Constructs a reference with schema-coerced target arguments. */
        fun of(
            path: List<ViaductSchema.ObjectField>,
            arguments: Map<String, Any?>,
        ): RootFieldReferenceData {
            require(path.isNotEmpty()) { "Root-field-reference path must not be empty" }
            return of(path, Arguments.Resolved.of(path.last(), arguments))
        }
    }
}

private data class RootFieldReferenceDataImpl(
    override val path: List<ViaductSchema.ObjectField>,
    override val arguments: Arguments.Resolved,
    override val type: ViaductSchema.OutputTypeDef,
) : RootFieldReferenceData
