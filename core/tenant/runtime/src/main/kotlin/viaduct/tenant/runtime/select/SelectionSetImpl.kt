@file:OptIn(ExperimentalApi::class)

package viaduct.tenant.runtime.select

import graphql.schema.GraphQLCompositeType
import viaduct.api.internal.InternalSelectionSet
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.FieldCoordinate
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.apiannotations.ExperimentalApi
import viaduct.engine.api.EngineSelectionSet

/**
 * Provides a type-safe interface for manipulating an untyped [EngineSelectionSetImpl]
 */
class SelectionSetImpl<T : CompositeOutput>(
    override val type: Type<T>,
    override val engineSelectionSet: EngineSelectionSet
) : SelectionSet<T>, InternalSelectionSet {
    private val structure by lazy(LazyThreadSafetyMode.PUBLICATION) {
        engineSelectionSet.structure()
    }

    override fun selectedFieldCoordinates(): Set<FieldCoordinate> =
        engineSelectionSet.selections().mapTo(linkedSetOf()) { selection ->
            FieldCoordinate(selection.typeCondition, selection.fieldName)
        }

    override fun <U : T> contains(field: Field<U>): Boolean = engineSelectionSet.containsField(field.containingType.name, field.name)

    override fun <U : T> requestsType(type: Type<U>): Boolean = engineSelectionSet.requestsType(type.name)

    override fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> =
        SelectionSetImpl(
            field.type,
            engineSelectionSet.selectionSetForField(field.containingType.name, field.name)
        )

    override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> =
        SelectionSetImpl(
            type,
            engineSelectionSet.selectionSetForType(type.name)
        )

    override fun isEmpty(): Boolean = engineSelectionSet.isTransitivelyEmpty()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is SelectionSetImpl<*> &&
            structure == other.structure

    override fun hashCode(): Int = structure.hashCode()

    override fun toString(): String = "SelectionSetImpl(type=$type, engineSelectionSet=$engineSelectionSet)"
}

private data class SelectionStructure(
    val type: String,
    val requestedTypes: Set<String>,
    val fields: Set<FieldStructure>,
)

private data class FieldStructure(
    val typeCondition: String,
    val fieldName: String,
    val selections: SelectionStructure?,
)

private fun EngineSelectionSet.structure(): SelectionStructure {
    val fields =
        selections().mapTo(hashSetOf()) {
            it.typeCondition to it.fieldName
        }
    val traversableFields =
        traversableSelections().mapTo(hashSetOf()) {
            it.typeCondition to it.fieldName
        }
    val currentType = schema.schema.getType(type) as GraphQLCompositeType
    val requestedTypes =
        schema.schema.allTypesAsList
            .asSequence()
            .filterIsInstance<GraphQLCompositeType>()
            .filter { schema.rels.isSpreadable(currentType, it) }
            .filter { requestsType(it.name) }
            .mapTo(hashSetOf()) { it.name }

    return SelectionStructure(
        type = type,
        requestedTypes = requestedTypes,
        fields =
            fields.mapTo(hashSetOf()) { (typeCondition, fieldName) ->
                FieldStructure(
                    typeCondition = typeCondition,
                    fieldName = fieldName,
                    selections =
                        if (typeCondition to fieldName in traversableFields) {
                            selectionSetForField(typeCondition, fieldName).structure()
                        } else {
                            null
                        },
                )
            },
    )
}
