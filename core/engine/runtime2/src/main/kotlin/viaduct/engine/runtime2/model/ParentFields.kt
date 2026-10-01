package viaduct.engine.runtime2.model

import viaduct.graphql.schema.ViaductSchema

/** The canonical schema directive that marks an engine-provided parent field. */
const val PARENT_DIRECTIVE_NAME: String = "parent"

/** Whether this canonical field is provided by traversing to the containing object's parent. */
fun ViaductSchema.Field.isParentField(): Boolean = hasAppliedDirective(PARENT_DIRECTIVE_NAME)

/**
 * Derives and validates the parent-field-to-producer-field relation for [schema].
 *
 * Qplan deliberately restricts a child-producing field paired with `@parent` to have no arguments.
 * Singular, list, and nested-list child outputs are all admitted, but their producer cannot
 * belong to the Query root or a namespace type.
 */
internal fun parentFieldRelations(schema: ViaductSchema): Map<ViaductSchema.ObjectField, ViaductSchema.ObjectField> {
    val objectFields =
        schema.types.values
            .filterIsInstance<ViaductSchema.Object>()
            .flatMap { type -> type.fields.filterIsInstance<ViaductSchema.ObjectField>() }
    return objectFields
        .filter(ViaductSchema.Field::isParentField)
        .associateWith { parentField ->
            require(parentField.args.isEmpty()) {
                "Parent field ${parentField.coordinate()} must not have arguments"
            }
            require(!parentField.type.isList) {
                "Parent field ${parentField.coordinate()} must not return a list"
            }
            val parentTarget =
                parentField.type.baseTypeDef as? ViaductSchema.CompositeTypeDef
                    ?: throw IllegalArgumentException(
                        "Parent field ${parentField.coordinate()} must return a composite type",
                    )
            val childType = parentField.containingDef
            val producers =
                objectFields.filter { candidate ->
                    !candidate.isParentField() &&
                        candidate.type.baseTypeDef == childType &&
                        parentTarget.possibleObjectTypes.containsAll(
                            candidate.containingDef.possibleObjectTypes,
                        )
                }
            require(producers.size == 1) {
                "Parent field ${parentField.coordinate()} requires exactly one compatible " +
                    "producer for ${childType.name}; found " +
                    producers.joinToString { producer -> producer.coordinate() }
            }
            producers.single().also { producer ->
                require(
                    producer.containingDef != schema.queryTypeDef &&
                        !producer.containingDef.hasAppliedDirective("namespaceType"),
                ) {
                    "Parent field ${parentField.coordinate()} must not refer back to Query or a " +
                        "@namespaceType; its child producer is ${producer.coordinate()}"
                }
                require(producer.args.isEmpty()) {
                    "Parent-field child producer ${producer.coordinate()} must not have arguments"
                }
            }
        }
}

private fun ViaductSchema.Field.coordinate(): String = "${containingDef.name}.$name"
