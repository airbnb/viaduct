package viaduct.arbitrary.graphql

import graphql.language.ArrayValue
import graphql.language.BooleanValue
import graphql.language.EnumValue
import graphql.language.FloatValue
import graphql.language.IntValue
import graphql.language.NullValue
import graphql.language.ObjectField
import graphql.language.ObjectValue
import graphql.language.ScalarValue
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLInputType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLType
import graphql.schema.GraphQLTypeReference
import graphql.schema.GraphQLTypeUtil
import viaduct.arbitrary.graphql.BridgeGJToRaw.ifNotINull
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.mapping.graphql.RawENull
import viaduct.mapping.graphql.RawEnum
import viaduct.mapping.graphql.RawINull
import viaduct.mapping.graphql.RawInput
import viaduct.mapping.graphql.RawList
import viaduct.mapping.graphql.RawObject
import viaduct.mapping.graphql.RawScalar
import viaduct.mapping.graphql.RawValue
import viaduct.mapping.graphql.RawValue.Companion.scalar
import viaduct.mapping.graphql.ValueMapper

private data class BridgeTypeCtx(
    val type: ViaductExtendedSchema.TypeExpr,
    val listDepth: Int,
) {
    val isList: Boolean = type.isList && type.listDepth > listDepth
    val isScalar: Boolean = !isList && type.baseTypeDef is ViaductExtendedSchema.Scalar
    val isInput: Boolean = !isList && type.baseTypeDef is ViaductExtendedSchema.Input
    val isEnum: Boolean = !isList && type.baseTypeDef is ViaductExtendedSchema.Enum
    val isNullable: Boolean = type.nullableAtDepth(listDepth)

    fun <U> asNullable(fn: () -> U): U {
        if (!isNullable) throw IllegalStateException("Expected nullable type but found $type")
        return fn()
    }

    fun <U> asScalar(fn: (ViaductExtendedSchema.Scalar) -> U): U {
        if (!isScalar) throw IllegalStateException("Expected scalar type but found $type")
        return fn(type.baseTypeDef as ViaductExtendedSchema.Scalar)
    }

    fun <U> asEnum(fn: (ViaductExtendedSchema.Enum) -> U): U {
        if (!isEnum) throw IllegalStateException("Expected enum type but found $type")
        return fn(type.baseTypeDef as ViaductExtendedSchema.Enum)
    }

    fun <U> asInput(fn: (ViaductExtendedSchema.Input) -> U): U {
        if (!isInput) throw IllegalStateException("Expected input type but found $type")
        return fn(type.baseTypeDef as ViaductExtendedSchema.Input)
    }

    fun <U> asList(fn: () -> U): U {
        if (!isList) throw IllegalStateException("Expected list type but found $type")
        return fn()
    }
}

// GJTypeCtx is used during type generation to generate default values, and may be used with a
// GraphQLTypeReference that may not be resolvable
private class GJTypeCtx private constructor(
    val type: GraphQLType?,
    private val resolver: TypeReferenceResolver,
    private val nullable: Boolean = true
) {
    val isList: Boolean = GraphQLTypeUtil.isList(type)
    val isScalar: Boolean = GraphQLTypeUtil.isScalar(type)
    val isInput: Boolean = type is GraphQLInputObjectType
    val isEnum: Boolean = GraphQLTypeUtil.isEnum(type)

    fun <U> asNullable(fn: () -> U): U {
        check(nullable) { "Expected nullable type but found $type" }
        return fn()
    }

    fun <U> asScalar(fn: (GraphQLScalarType) -> U): U {
        check(isScalar) { "Expected scalar type but found $type" }
        return fn(type as GraphQLScalarType)
    }

    fun <U> asEnum(fn: (GraphQLEnumType) -> U): U {
        check(isEnum) { "Expected enum type but found $type" }
        return fn(type as GraphQLEnumType)
    }

    fun <U> asInput(fn: (GraphQLInputObjectType) -> U): U {
        check(isInput) { "Expected input type but found $type" }
        return fn(type as GraphQLInputObjectType)
    }

    fun <U> asList(fn: (GraphQLList) -> U): U {
        check(isList) { "Expected list type but found $type" }
        return fn(type as GraphQLList)
    }

    fun traverse(type: GraphQLType): GJTypeCtx = mk(type, resolver)

    companion object {
        fun mk(
            type: GraphQLType,
            resolver: TypeReferenceResolver
        ): GJTypeCtx {
            val resolved = GraphQLTypeUtil.unwrapNonNull(type).let {
                if (it is GraphQLTypeReference) {
                    resolver(it)
                } else {
                    it
                }
            }
            return GJTypeCtx(resolved, resolver, GraphQLTypeUtil.isNullable(type))
        }
    }
}

object BridgeGJToRaw : ValueMapper<ViaductExtendedSchema.TypeExpr, Value<*>, RawValue>, RawValue.DSL() {
    override fun invoke(
        type: ViaductExtendedSchema.TypeExpr,
        value: Value<*>
    ): RawValue = toRaw(BridgeTypeCtx(type, 0), value)

    private fun toRaw(
        ctx: BridgeTypeCtx,
        value: Value<*>
    ): RawValue =
        when (val v = value) {
            is NullValue -> ctx.asNullable { RawENull }
            is ScalarValue -> ctx.asScalar { ScalarGJToRaw(ctx.type.baseTypeDef.name, v) }
            is EnumValue -> ctx.asEnum { RawEnum(v.name) }
            is ObjectValue -> ctx.asInput { input ->
                val seenKeys = mutableSetOf<String>()
                val pairs = v.objectFields.map { f ->
                    val bridgeField = input.field(f.name)!!
                    val rawValue = toRaw(
                        ctx.copy(type = bridgeField.type, listDepth = 0),
                        f.value
                    )
                    seenKeys += f.name
                    f.name to rawValue
                }
                val withInulls = input.fields.fold(pairs) { acc, f ->
                    if (f.name in seenKeys) {
                        acc
                    } else {
                        acc + (f.name to RawINull)
                    }
                }
                RawInput(withInulls)
            }
            is ArrayValue -> ctx.asList {
                RawList(
                    v.values.map { innerV ->
                        toRaw(ctx.copy(listDepth = ctx.listDepth + 1), innerV)
                    }
                )
            }
            else -> throw IllegalArgumentException("Cannot convert to RawValue: $v")
        }
}

internal object ScalarRawToGJ : ValueMapper<String, RawScalar, ScalarValue<*>> {
    override fun invoke(
        type: String,
        v: RawScalar
    ): ScalarValue<*> =
        when (type) {
            "String", "ID" -> StringValue.of(v.value as String)
            "Int" -> IntValue.of(v.value as Int)
            "Boolean" -> BooleanValue.of(v.value as Boolean)
            "Float" -> FloatValue.of(v.value as Double)
            else -> throw IllegalArgumentException("Unsupported scalar value type: $type")
        }
}

internal object ScalarGJToRaw : ValueMapper<String, ScalarValue<*>, RawScalar> {
    override fun invoke(
        type: String,
        v: ScalarValue<*>
    ): RawScalar =
        when (type) {
            "String", "ID" -> (v as StringValue).value.scalar
            "Int" -> (v as IntValue).value.intValueExact().scalar
            "Boolean" -> (v as BooleanValue).isValue.scalar
            "Float" -> (v as FloatValue).value.toDouble().scalar
            else -> throw IllegalArgumentException("Unsupported scalar value type: $type")
        }
}

object BridgeRawToGJ : ValueMapper<ViaductExtendedSchema.TypeExpr, RawValue, Value<*>> {
    override fun invoke(
        type: ViaductExtendedSchema.TypeExpr,
        value: RawValue
    ): Value<*> = toGJ(BridgeTypeCtx(type, 0), value)

    private fun toGJ(
        ctx: BridgeTypeCtx,
        value: RawValue
    ): Value<*> =
        when (value) {
            RawENull -> ctx.asNullable { gjNull }
            is RawScalar -> ctx.asScalar { ScalarRawToGJ(it.name, value) }
            is RawEnum -> ctx.asEnum { EnumValue.of(value.valueName) }
            is RawInput -> ctx.asInput { input ->
                val fieldValues = value.values.fold(emptyList<ObjectField>()) { acc, (name, value) ->
                    when (value) {
                        RawINull -> acc
                        else -> {
                            val fieldType = input.field(name)!!.type
                            acc + ObjectField(name, toGJ(ctx.copy(fieldType), value))
                        }
                    }
                }
                ObjectValue(fieldValues)
            }

            is RawList -> ctx.asList {
                val innerCtx = ctx.copy(listDepth = ctx.listDepth + 1)
                val values = value.values.map { toGJ(innerCtx, it) }
                ArrayValue(values)
            }

            else -> throw IllegalArgumentException("Cannot convert to GJ Value: $value")
        }
}

class GJRawToGJ(private val resolver: TypeReferenceResolver) : ValueMapper<GraphQLInputType, RawValue, Value<*>> {
    override fun invoke(
        type: GraphQLInputType,
        value: RawValue
    ): Value<*> = toGJ(GJTypeCtx.mk(type, resolver), value)

    private fun toGJ(
        ctx: GJTypeCtx,
        value: RawValue
    ): Value<*> =
        when (value) {
            RawENull -> ctx.asNullable { gjNull }
            is RawScalar -> ctx.asScalar { ScalarRawToGJ(it.name, value) }
            is RawEnum -> ctx.asEnum { EnumValue.of(value.valueName) }
            is RawInput -> ctx.asInput { type ->
                val fieldDefs = type.fields.associateBy { it.name }
                val fieldVals = value.values.mapNotNull { (name, value) ->
                    value.ifNotINull {
                        val fieldCtx = ctx.traverse(fieldDefs[name]!!.type)
                        ObjectField(name, toGJ(fieldCtx, value))
                    }
                }
                ObjectValue(fieldVals)
            }

            is RawList -> ctx.asList {
                val innerCtx = ctx.traverse(it.wrappedType)
                ArrayValue(
                    value.values.map {
                        toGJ(innerCtx, it)
                    }
                )
            }

            else -> throw IllegalArgumentException("Cannot convert to GJ Value: $value")
        }
}

class GJGJToRaw(private val resolver: TypeReferenceResolver) : ValueMapper<GraphQLInputType, Value<*>, RawValue> {
    override fun invoke(
        type: GraphQLInputType,
        value: Value<*>
    ): RawValue = toRaw(GJTypeCtx.mk(type, resolver), value)

    private fun toRaw(
        ctx: GJTypeCtx,
        value: Value<*>
    ): RawValue =
        when (val v = value) {
            is NullValue -> ctx.asNullable { RawENull }
            is ScalarValue -> ctx.asScalar { ScalarGJToRaw((ctx.type as GraphQLScalarType).name, v) }
            is EnumValue -> ctx.asEnum { RawEnum(v.name) }
            is ObjectValue -> ctx.asInput { input ->
                val seenKeys = mutableSetOf<String>()
                val pairs = v.objectFields.map { f ->
                    val field = input.getField(f.name)
                    val rawValue = toRaw(ctx.traverse(field.type), f.value)
                    seenKeys += f.name
                    f.name to rawValue
                }
                val withInulls = input.fields.fold(pairs) { acc, f ->
                    if (f.name in seenKeys) {
                        acc
                    } else {
                        pairs + (f.name to RawINull)
                    }
                }
                RawInput(withInulls)
            }

            is ArrayValue -> ctx.asList {
                RawList(
                    v.values.map { innerV ->
                        toRaw(ctx.traverse(it.wrappedType), innerV)
                    }
                )
            }

            else -> throw IllegalArgumentException("Cannot convert to RawValue: $v")
        }
}

/** map a [RawValue] to a native kotlin value (sometimes called "internal" by graphql-java) */
object RawToKotlin : ValueMapper<Any, RawValue, Any?> {
    override fun invoke(
        type: Any,
        value: RawValue
    ): Any? = toInternal(value)

    private fun toInternal(value: RawValue): Any? =
        when (val v = value) {
            is RawENull -> null
            is RawScalar -> v.value
            is RawEnum -> v.valueName
            is RawList -> v.values.map(::toInternal)
            is RawInput ->
                v.values.fold(emptyMap<String, Any?>()) { acc, (k, v) ->
                    if (v == RawINull) {
                        acc
                    } else {
                        acc + (k to toInternal(v))
                    }
                }
            is RawINull -> throw IllegalStateException("Unexpected inull")
            is RawObject -> throw UnsupportedOperationException("output objects cannot be mapped to internal values")
        }
}

/** map a native kotlin value to a [RawValue] */
class GJKotlinToRaw(private val resolver: TypeReferenceResolver) : ValueMapper<GraphQLInputType, Any?, RawValue> {
    override fun invoke(
        type: GraphQLInputType,
        value: Any?
    ): RawValue = toRaw(GJTypeCtx.mk(type, resolver), value)

    private fun toRaw(
        ctx: GJTypeCtx,
        value: Any?
    ): RawValue =
        if (ctx.type == null) {
            throw UnsupportedOperationException("Cannot map values for an unresolvable type")
        } else if (value == null) {
            ctx.asNullable { RawENull }
        } else if (ctx.isScalar) {
            ctx.asScalar {
                RawScalar(it.name, value)
            }
        } else if (ctx.isEnum) {
            ctx.asEnum {
                RawEnum(value as String)
            }
        } else if (ctx.isList) {
            ctx.asList {
                val listCtx = ctx.traverse(it.wrappedType)
                val values = (value as List<*>).map { item -> toRaw(listCtx, item) }
                RawList(values)
            }
        } else if (ctx.isInput) {
            @Suppress("UNCHECKED_CAST")
            ctx.asInput { iot ->
                value as Map<String, Any?>
                val entries = iot.fields.fold(emptyList<Pair<String, RawValue>>()) { acc, f ->
                    val v =
                        if (f.name !in value) {
                            RawINull
                        } else {
                            toRaw(ctx.traverse(f.type), value[f.name])
                        }
                    acc + (f.name to v)
                }
                RawInput(entries)
            }
        } else {
            throw UnsupportedOperationException("Cannot map type: ${ctx.type}")
        }
}

private val gjNull = NullValue.of()
