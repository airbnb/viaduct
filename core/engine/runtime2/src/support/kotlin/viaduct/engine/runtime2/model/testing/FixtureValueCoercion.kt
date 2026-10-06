package viaduct.engine.runtime2.model.testing

import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.conformsToScalarOutput
import viaduct.engine.runtime2.model.requireType
import viaduct.graphql.schema.ViaductSchema

internal fun coerceSimpleValue(
    type: ViaductSchema.SimpleTypeDef,
    value: Any,
): EngineOutputData =
    when (type) {
        is ViaductSchema.Scalar ->
            value.also {
                require(it.conformsToScalarOutput(type.name)) { "Value does not conform to scalar ${type.name}" }
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
