package viaduct.engine.runtime2.model

import graphql.schema.GraphQLObjectType
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.EngineObjectData
import viaduct.errors.UnsetFieldException
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.gjDef
import viaduct.utils.collections.HMap

/**
 * One construction-time EOD entry whose selection may be a field name or response alias.
 *
 * The constructed EOD retains the canonical field so aliases and opaque scalar values can be
 * validated without inferring a schema type from their JVM representation.
 */
sealed interface EngineObjectDataEntry {
    val selection: String
    val field: ViaductSchema.ObjectField
    val value: ResolverOutputData?

    companion object {
        fun of(
            selection: String,
            field: ViaductSchema.ObjectField,
            value: ResolverOutputData?,
        ): EngineObjectDataEntry = EngineObjectDataEntryImpl(selection, field, value)
    }
}

/**
 * Constructs a passive object whose selections are canonical argumentless field names.
 *
 * Callers supply values conforming to their fields' output types; construction does not recheck conformance.
 */
fun engineObjectDataOf(
    schemaType: ViaductSchema.Object,
    fields: Map<String, ResolverOutputData?> = emptyMap(),
): EngineObjectData.Sync =
    engineObjectDataOf(
        schemaType = schemaType,
        fields =
            fields.map { (name, value) ->
                val field = schemaType.field(name)
                require(field is ViaductSchema.ObjectField) {
                    "${schemaType.name} has no canonical object field named $name"
                }
                require(field.args.isEmpty()) {
                    "Passive object field ${schemaType.name}/$name must be argumentless"
                }
                EngineObjectDataEntry.of(name, field, value)
            },
    )

/**
 * Constructs a partial synchronous EOD from schema-associated selection values.
 *
 * Callers supply values conforming to their associated fields' output types without another conformance check.
 * [schemaType] supplies qplan's canonical lowered definition and the associated Engine API witness.
 * Source-backed objects expose the exact retained source GraphQL-Java object.
 */
fun engineObjectDataOf(
    schemaType: ViaductSchema.Object,
    fields: Iterable<EngineObjectDataEntry>,
): EngineObjectData.Sync = constructEngineObjectData(schemaType, fields)

/**
 * Constructs tenant-visible resolver input from conforming canonical values without rechecking conformance.
 */
fun materializedEngineObjectDataOf(
    schemaType: ViaductSchema.Object,
    fields: Iterable<EngineObjectDataEntry>,
): EngineObjectData.Sync = constructEngineObjectData(schemaType, fields)

private fun constructEngineObjectData(
    schemaType: ViaductSchema.Object,
    fields: Iterable<EngineObjectDataEntry>,
): EngineObjectData.Sync {
    val entries = fields.toList()
    entries.forEach { entry ->
        require(entry.field.containingDef == schemaType) {
            "${schemaType.name} cannot contain output field " +
                "${entry.field.containingDef.name}/${entry.field.name}"
        }
    }
    val values = entries.associate { entry -> entry.selection to entry.value }
    require(values.size == entries.size) {
        "Object ${schemaType.name} contains duplicate string selections"
    }
    return QPlanEngineObjectDataImpl(
        type = schemaType.engineObjectDataType,
        schemaType = schemaType,
        values = values,
        selectionFields = entries.associate { it.selection to it.field },
    )
}

internal val qplanEngineObjectDataTypeKey =
    HMap.Key.of<GraphQLObjectType>("QPlanEngineObjectDataType")

private val ViaductSchema.Object.engineObjectDataType: GraphQLObjectType
    get() =
        if (qplanEngineObjectDataTypeKey in holder) {
            holder[qplanEngineObjectDataTypeKey]
        } else {
            gjDef
        }

/**
 * The canonical qplan schema type retained by this qplan-owned EOD.
 *
 * Reads without a schema are available only for Runtime2-owned objects.
 */
val EngineObjectData.Sync.schemaType: ViaductSchema.Object
    get() =
        requireNotNull(qplanSchemaTypeOrNull) {
            "Engine object data for ${type.name} is not owned by qplan"
        }

internal val EngineObjectData.Sync.qplanSchemaTypeOrNull: ViaductSchema.Object?
    get() = (this as? QPlanEngineObjectData)?.schemaType

/** Resolves a compliant object's concrete type in the active canonical schema. */
fun EngineObjectData.Sync.schemaType(schema: ViaductSchema): ViaductSchema.Object =
    qplanSchemaTypeOrNull ?: run {
        schema.types[type.name] as? ViaductSchema.Object
            ?: throw IllegalArgumentException("Resolver output type ${type.name} is not an object")
    }

/** Resolves a compliant object's concrete type within a declared composite output type. */
internal fun EngineObjectData.Sync.schemaType(declaredType: ViaductSchema.CompositeTypeDef): ViaductSchema.Object =
    qplanSchemaTypeOrNull ?: run {
        declaredType.possibleObjectTypes.singleOrNull { it.name == type.name }
            ?: throw IllegalArgumentException("Resolver output type ${type.name} is not valid for ${declaredType.name}")
    }

/** Reads raw Runtime2 storage or error-free external resolver output through its synchronous getter. */
fun EngineObjectData.Sync.outputValue(selection: String): ResolverOutputData? {
    return when (this) {
        is QPlanEngineObjectData -> outputValue(selection)
        else -> get(selection)
    }
}

private data class EngineObjectDataEntryImpl(
    override val selection: String,
    override val field: ViaductSchema.ObjectField,
    override val value: ResolverOutputData?,
) : EngineObjectDataEntry

internal interface QPlanEngineObjectData : EngineObjectData.Sync {
    val schemaType: ViaductSchema.Object
    val selectionFields: Map<String, ViaductSchema.ObjectField>

    fun outputValue(selection: String): ResolverOutputData?
}

internal class EngineErrorDataReadException(
    val errorData: EngineErrorData,
) : RuntimeException(errorData.cause)

@OptIn(InternalApi::class)
private class QPlanEngineObjectDataImpl(
    override val type: GraphQLObjectType,
    override val schemaType: ViaductSchema.Object,
    private val values: Map<String, ResolverOutputData?>,
    override val selectionFields: Map<String, ViaductSchema.ObjectField>,
) : QPlanEngineObjectData {
    override suspend fun fetch(selection: String): Any? = get(selection)

    override suspend fun fetchOrNull(selection: String): Any? = getOrNull(selection)

    override suspend fun fetchSelections(): Iterable<String> = getSelections()

    override fun get(selection: String): Any? {
        val value = outputValue(selection)
        value.firstErrorDataOrNull()?.let { errorData ->
            throw EngineErrorDataReadException(errorData)
        }
        return value
    }

    override fun outputValue(selection: String): ResolverOutputData? {
        if (!isPresent(selection)) {
            throw UnsetFieldException(
                selection,
                type,
                "The selection is absent from qplan's partial object data",
            )
        }
        return values[selection]
    }

    override fun getOrNull(selection: String): Any? = if (isPresent(selection)) get(selection) else null

    override fun isPresent(selection: String): Boolean = selection in values

    override fun getSelections(): Iterable<String> = values.keys

    override fun toString(): String = "type=${type.name} values=$values"
}

private fun ResolverOutputData?.firstErrorDataOrNull(): EngineErrorData? =
    when (this) {
        is EngineErrorData -> this
        is List<*> -> firstNotNullOfOrNull { value -> value.firstErrorDataOrNull() }
        else -> null
    }
