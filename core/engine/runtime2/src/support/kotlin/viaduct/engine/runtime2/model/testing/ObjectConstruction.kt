package viaduct.engine.runtime2.model.testing

import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.schemaType
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.graphql.schema.ViaductSchema

/** Constructs an object value by resolving type and field names in this reasoning world. */
fun Assumptions.objectOf(
    typeName: String,
    block: ObjectValueScope.() -> Unit = {},
): EngineObjectData.Sync = schema.objectOf(typeName, block)

/** Constructs an object value by resolving type and field names in this schema. */
fun ViaductSchema.objectOf(
    typeName: String,
    block: ObjectValueScope.() -> Unit = {},
): EngineObjectData.Sync {
    val type = requireType(typeName)
    require(type is ViaductSchema.Object) {
        "$typeName is not an object type"
    }
    return ObjectValueScope(this, type)
        .apply(block)
        .build()
}

@DslMarker
annotation class ObjectValueDsl

/** Field-construction scope for [objectOf]. */
@ObjectValueDsl
class ObjectValueScope internal constructor(
    private val schema: ViaductSchema,
    private val type: ViaductSchema.Object,
) {
    private val sourceSchema = SourceSchemaAdapter(schema)
    private val fields = linkedMapOf<String, EngineObjectDataEntry>()
    private var isBuilt = false

    /** Selects a field coordinate on this scope's object type. */
    fun field(
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): ObjectFieldReference {
        val field = sourceSchema.field(type.name, fieldName)
        require(field is ViaductSchema.ObjectField) {
            "${type.name}/$fieldName does not lower to an object field"
        }
        return field(field, *arguments)
    }

    /** Selects an already-lowered canonical field on this scope's object type. */
    fun field(
        field: ViaductSchema.ObjectField,
        vararg arguments: Pair<String, Any?>,
    ): ObjectFieldReference {
        require(field.containingDef == type) {
            "${field.containingDef.name}/${field.name} does not belong to ${type.name}"
        }
        if (arguments.size > 1) {
            require(arguments.map(Pair<String, Any?>::first).distinct().size == arguments.size) {
                "Arguments for ${type.name}/${field.name} must have distinct names"
            }
        }
        return ObjectFieldReference(
            scope = this,
            key =
                ObjectEngineResult.GroundKey.of(
                    field = field,
                    arguments = arguments.toMap(),
                ),
        )
    }

    /** Assigns [value] to this argumentless field. */
    infix fun String.setTo(value: Any?) {
        this@ObjectValueScope.field(this).setTo(value)
    }

    /** Assigns [value] to this exact field coordinate. */
    infix fun ObjectFieldReference.setTo(value: Any?) {
        require(!isBuilt) {
            "Cannot assign fields after constructing ${type.name}"
        }
        require(scope === this@ObjectValueScope) {
            "A field reference cannot be assigned in another object scope"
        }
        val arguments = key.arguments
        require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
            "Passive object field ${type.name}/${key.field.name} must be argumentless"
        }
        val fieldName = key.field.name
        require(fieldName !in fields) {
            "Duplicate object field ${type.name}/${key.field.name}"
        }
        fields[fieldName] =
            EngineObjectDataEntry.of(
                selection = fieldName,
                field = key.field,
                value = sourceSchema.lowerOutput(key.field, value),
            )
    }

    /** Constructs a nested object value using the same schema. */
    fun objectOf(
        typeName: String,
        block: ObjectValueScope.() -> Unit = {},
    ): EngineObjectData.Sync = schema.objectOf(typeName, block)

    internal fun build(): EngineObjectData.Sync {
        isBuilt = true
        return engineObjectDataOf(
            schemaType = type,
            fields = fields.values,
        )
    }
}

/** One exact object-field coordinate selected in an [ObjectValueScope]. */
class ObjectFieldReference internal constructor(
    internal val scope: ObjectValueScope,
    internal val key: ObjectEngineResult.GroundKey,
)
