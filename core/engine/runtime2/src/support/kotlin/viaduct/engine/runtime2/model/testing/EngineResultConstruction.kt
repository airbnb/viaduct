package viaduct.engine.runtime2.model.testing

import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.conformsToResultSchemaType
import viaduct.engine.runtime2.model.outputType
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.toEngineResult
import viaduct.graphql.schema.ViaductSchema

/** Constructs an object engine result by resolving type and field names in this reasoning world. */
fun Assumptions.engineResultOf(
    typeName: String,
    block: EngineResultScope.() -> Unit = {},
): ObjectEngineResult = schema.engineResultOf(typeName, block)

/** Constructs an object engine result by resolving type and field names in this schema. */
fun ViaductSchema.engineResultOf(
    typeName: String,
    block: EngineResultScope.() -> Unit = {},
): ObjectEngineResult {
    val type = requireType(typeName)
    require(type is ViaductSchema.Object) {
        "$typeName is not an object type"
    }
    return EngineResultScope(this, type)
        .apply(block)
        .build()
}

/** Constructs a list engine result in this reasoning world. */
fun Assumptions.listResultOf(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    vararg values: Any?,
): ListEngineResult = schema.listResultOf(typeExpr, *values)

/** Constructs a list engine result whose elements have [typeExpr]. */
fun ViaductSchema.listResultOf(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    vararg values: Any?,
): ListEngineResult =
    ListEngineResult.of(
        typeExpr = typeExpr,
        values = values.map { value -> coerceEngineResult(typeExpr, value) },
    )

@DslMarker
annotation class EngineResultDsl

/** Field-construction scope for [engineResultOf]. */
@EngineResultDsl
class EngineResultScope internal constructor(
    private val schema: ViaductSchema,
    private val type: ViaductSchema.Object,
) {
    private val values = linkedMapOf<ObjectEngineResult.GroundKey, EngineResult?>()
    private val fieldCheckerResults =
        linkedMapOf<ObjectEngineResult.GroundKey, CheckerResult?>()

    /** Selects a field coordinate on this scope's object type. */
    fun field(
        fieldName: String,
        vararg arguments: Pair<String, Any?>,
    ): EngineResultFieldReference {
        require(arguments.map(Pair<String, Any?>::first).distinct().size == arguments.size) {
            "Arguments for ${type.name}/$fieldName must have distinct names"
        }
        return EngineResultFieldReference(
            key =
                ObjectEngineResult.GroundKey.of(
                    field = schema.requireObjectField(type.name, fieldName),
                    arguments = arguments.toMap(),
                ),
        )
    }

    /** Resolves this argumentless field to [value] with accepted access. */
    infix fun String.resolvesTo(value: Any?) {
        field(this).resolvesTo(value)
    }

    /** Resolves this argumentless field to [value] with the supplied checker results. */
    fun String.resolvesTo(
        value: Any?,
        fieldCheckerResult: CheckerResult?,
    ) {
        field(this).resolvesTo(value, fieldCheckerResult)
    }

    /** Resolves this exact field coordinate to [value] with accepted access. */
    infix fun EngineResultFieldReference.resolvesTo(value: Any?) {
        resolvesTo(value, null)
    }

    /** Resolves this exact field coordinate to [value] with the supplied checker results. */
    fun EngineResultFieldReference.resolvesTo(
        value: Any?,
        fieldCheckerResult: CheckerResult?,
    ) {
        require(key !in values) {
            "Duplicate engine-result field ${type.name}/${key.field.name}"
        }
        values[key] = coerceEngineResult(key.field.outputType, value)
        fieldCheckerResults[key] = fieldCheckerResult
    }

    /** Constructs a nested object engine result using the same schema. */
    fun engineResultOf(
        typeName: String,
        block: EngineResultScope.() -> Unit = {},
    ): ObjectEngineResult = schema.engineResultOf(typeName, block)

    internal fun build(): ObjectEngineResult =
        ObjectEngineResult.of(
            type = type,
            values = values.toMap(),
            fieldCheckerResults = fieldCheckerResults.toMap(),
        )
}

/** One exact object-field coordinate selected in an [EngineResultScope]. */
class EngineResultFieldReference internal constructor(
    internal val key: ObjectEngineResult.GroundKey,
)

private fun coerceEngineResult(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    value: Any?,
): EngineResult? {
    if (value.conformsToResultSchemaType(typeExpr)) return value

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        require(value is List<*>) {
            "Expected a list result for $typeExpr"
        }
        return ListEngineResult.of(
            typeExpr = elementType,
            values =
                value.map { element ->
                    coerceEngineResult(elementType, element)
                },
        )
    }
    return when (val type = typeExpr.baseTypeDef) {
        is ViaductSchema.SimpleTypeDef ->
            coerceSimpleValue(type, requireNotNull(value)).toEngineResult(type)
        is ViaductSchema.CompositeTypeDef ->
            throw IllegalArgumentException(
                "Expected an object engine result for ${type.name}",
            )
        else -> error("Output field has a non-output type")
    }
}
