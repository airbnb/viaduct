package viaduct.engine.runtime2.model.testing

import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.requireType
import viaduct.graphql.schema.ViaductSchema

internal fun coerceSimpleValue(
    type: ViaductSchema.SimpleTypeDef,
    value: Any,
): EngineOutputData =
    when (type) {
        is ViaductSchema.Scalar ->
            when (type.name) {
                "Int" -> requireType<Int>(value, type)
                "Float" ->
                    requireType<Double>(value, type).also {
                        require(it.isFinite()) { "GraphQL Float values must be finite" }
                    }
                "String" -> requireType<String>(value, type)
                "Boolean" -> requireType<Boolean>(value, type)
                "ID" -> requireType<String>(value, type)
                else -> error("Unsupported scalar: ${type.name}")
            }
        is ViaductSchema.Enum ->
            requireType<String>(value, type).also {
                require(type.value(it) != null) { "$it is not a value of ${type.name}" }
            }
        else -> error("Unsupported simple type: ${type.name}")
    }

private inline fun <reified T : Any> requireType(
    value: Any,
    type: ViaductSchema.TypeDef,
): T {
    require(value is T) {
        "Expected ${T::class.simpleName} for ${type.name}"
    }
    return value
}
