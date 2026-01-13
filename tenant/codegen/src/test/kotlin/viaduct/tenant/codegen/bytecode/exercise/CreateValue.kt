package viaduct.tenant.codegen.bytecode.exercise

import graphql.schema.GraphQLInputObjectType
import java.time.ZoneOffset
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.reflect.Type
import viaduct.api.types.NodeObject
import viaduct.codegen.km.setterName
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.schema.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.loaders.core.edges.EdgesQueryResponse
import viaduct.schema.base.BuilderBase
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields
import viaduct.tenant.codegen.bytecode.config.grtNameForIdParam
import viaduct.types.I18nText
import viaduct.types.UGCText

/**
 * Create a value for this field, using the given [ClassLoader] to load classes
 * where the field-type is a GRT. In the general case, this function is able
 * to generate two distinct values for a field, one value when [value2] is false,
 * and an alternative when it is true. However, in some cases, it is not possible,
 * in which case the same value is returned regardless of the value of [value2].
 * The function [ViaductGraphQLSchema.TypeExpr.onlyOneValue] can be used
 * to determine if there is only one value for a type expression.
 *
 * This function creates V2 GRT objects if the field-type is a GRT.
 */
fun ViaductGraphQLSchema.HasDefaultValue.createValueV2(
    classResolver: ClassResolver,
    schema: ViaductSchema,
    value2: Boolean = false,
    baseTypeMapper: BaseTypeMapper = ViaductBaseTypeMapper(GJSchema.fromSchema(schema.schema)),
    classLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
): Any? = this.valueV2FromGenericValue(classResolver, schema, this.createGenericValue(value2, if (value2) 2 else 1, emptyList(), baseTypeMapper), classLoader = classLoader)

fun ViaductGraphQLSchema.HasDefaultValue.createGenericValue(
    value2: Boolean,
    quitCountDown: Int,
    seen: List<ViaductGraphQLSchema.HasDefaultValue>,
    baseTypeMapper: BaseTypeMapper,
): Any? {
    if (this.type.baseTypeDef.name == "Query") {
        if (this.type.nullableOrEmpty) {
            return this.type.nullOrEmpty()
        } else {
            val path = seen.joinToString("\n   ") { "${it.containingDef.name}.${it.describe()}" }
            throw IllegalArgumentException("Can't create type for ${this.containingDef.name}.$this\n   $path.")
        }
    }

    val cutoffDepth = if (value2) 4 else 2
    if (cutoffDepth < seen.size && this.type.nullableOrEmpty) {
        return this.type.nullOrEmpty()
    }

    val concreteTypeDef: ViaductGraphQLSchema.TypeDef = when (val baseTD = this.type.baseTypeDef) {
        is ViaductGraphQLSchema.Interface, is ViaductGraphQLSchema.Union -> {
            val possibleTypes = when {
                !value2 -> baseTD.possibleObjectTypes.toList()
                else -> baseTD.possibleObjectTypes.toList().reversed()
            }
            val idx = seen.filter { it == this }.count()
            val ret = possibleTypes.elementAtOrNull(idx)
            if (ret == null) {
                if (this.type.nullableOrEmpty) {
                    return this.type.nullOrEmpty()
                } else {
                    val path = seen.joinToString("\n   ") { "${it.containingDef.name}.${it.describe()}" }
                    throw IllegalArgumentException("No value exists for ${this.containingDef.name}.$this\n   $path")
                }
            } else {
                ret
            }
        }
        else -> baseTD
    }

    if ((quitCountDown == 0 || (value2 && concreteTypeDef.onlyOneValue())) && this.type.nullableOrEmpty) {
        return this.type.nullOrEmpty()
    }
    checkDepth(seen)
    val hash = if (value2) 1 else 0
    var result: Any? =
        if (concreteTypeDef is ViaductGraphQLSchema.Scalar) {
            when (concreteTypeDef.name) {
                "Boolean" -> value2
                "Date" -> java.time.LocalDate.ofEpochDay(hash.toLong())
                "DateTime" -> java.time.Instant.ofEpochSecond(hash.toLong())
                "Float" -> hash.toDouble()
                "ID" -> hash.toString()
                "Int" -> hash
                "JSON" -> listOf(mapOf("field" to hash.toString()))
                "Long" -> hash.toLong()
                "Short" -> hash.toShort()
                "String" -> hash.toString()
                "Time" -> java.time.OffsetTime.of(hash, hash, hash, hash, ZoneOffset.UTC)
                else -> throw IllegalArgumentException("Can't create value for ${this.containingDef.name}.$this.")
            }
        } else if (concreteTypeDef.name == "I18nText" && baseTypeMapper.useGlobalIdTypeAlias()) {
            if (value2) I18nText.V2 else I18nText.V1
        } else if (concreteTypeDef is ViaductGraphQLSchema.Object && concreteTypeDef.supers.any { it.name == "PagedConnection" } && baseTypeMapper.useGlobalIdTypeAlias()) {
            if (value2) EdgesQueryResponse.V2 else EdgesQueryResponse.V1
        } else if (concreteTypeDef.name == "UGCText" && baseTypeMapper.useGlobalIdTypeAlias()) {
            if (value2) UGCText.V2 else UGCText.V1
        } else if (concreteTypeDef is ViaductGraphQLSchema.Enum) {
            concreteTypeDef.values.elementAt(
                if (!value2) {
                    0
                } else {
                    concreteTypeDef.values.count() - 1
                }
            ).name
        } else if (concreteTypeDef is ViaductGraphQLSchema.Record) {
            concreteTypeDef.createGenericValue(
                value2,
                quitCountDown,
                seen,
                this,
                baseTypeMapper
            )
        } else {
            throw IllegalArgumentException("Can't create value for ${this.containingDef.name}.$this.")
        }
    repeat(type.listDepth) {
        result = listOf(result)
    }
    return result
}

private fun ViaductGraphQLSchema.Record.createGenericValue(
    value2: Boolean,
    quitCountDown: Int,
    seen: List<ViaductGraphQLSchema.HasDefaultValue>,
    def: ViaductGraphQLSchema.HasDefaultValue? = null,
    baseTypeMapper: BaseTypeMapper,
): RecordValue {
    val fieldsToGenerate = if (cfg.isHasClearableFieldsInputType(this) && value2) {
        // For hasClearableFields input types, if value2 don't set any of the nullable fields
        this.codegenIncludedFields.areGenerated().filter { !it.type.isNullable }
    } else {
        this.codegenIncludedFields.areGenerated()
    }
    val fieldValues = fieldsToGenerate.associate {
        it.name to it.createGenericValue(value2, quitCountDown, def?.let { seen + it } ?: seen, baseTypeMapper)
    }
    return RecordValue(this, fieldValues)
}

private fun checkDepth(seen: List<ViaductGraphQLSchema.HasDefaultValue>) {
    if (seen.size < 100) return

    // Returns the number of elements in seen before and including the first duplicate
    fun findShortestLoop(): Int {
        for (i in 1 until seen.size) {
            val el = seen[i]
            val result = seen.indexOfFirst { it == el }
            if (result < i) return i + 1
        }
        throw IllegalStateException("No loop found ($seen).")
    }
    val path = seen.take(findShortestLoop()).joinToString("\n   ") { "${it.containingDef.name}.${it.describe()}" }
    throw IllegalArgumentException("Type cycle with no nullable fields\n   $path }")
}

/**
 * Materialize a GRT-ish value for the provided untyped [genericValue].
 * The returned value may be a null value, an instance of a GRT, an enum value, or one
 * of the above wrapped in one or more lists.
 */
internal fun ViaductGraphQLSchema.HasDefaultValue.valueFromGenericValue(
    classResolver: ClassResolver,
    genericValue: Any?,
): Any? {
    var baseValue = genericValue
    var actualListDepth = 0
    while (baseValue != null && baseValue is List<*>) {
        baseValue = baseValue.getOrNull(0)
        actualListDepth++
    }
    if (baseValue == null) return genericValue

    var result: Any? = when (val baseTypeDef = this.type.baseTypeDef) {
        is ViaductGraphQLSchema.Input, is ViaductGraphQLSchema.Interface, is ViaductGraphQLSchema.Object, is ViaductGraphQLSchema.Union -> {
            if (baseValue is I18nText || baseValue is EdgesQueryResponse<*> || baseValue is UGCText) {
                baseValue
            } else {
                genericValueToValue(classResolver, baseValue as RecordValue)
            }
        }

        is ViaductGraphQLSchema.Enum -> {
            val idx = baseTypeDef.values.indexOfFirst { it.name == (baseValue as String) }
            val vals = classResolver.mainClassFor(baseTypeDef.name).getEnumConstants()!!
            vals[idx]
        }

        else -> baseValue
    }
    repeat(actualListDepth) { result = listOf(result) }
    return result
}

/**
 * Similar to above [ViaductGraphQLSchema.HasDefaultValue.valueFromGenericValue], but return v2 value.
 * If [asEngineObjectData] is true, return value with nested EngineObjectData, otherwise
 * return value with nested V2 GRT.
 */
internal fun ViaductGraphQLSchema.HasDefaultValue.valueV2FromGenericValue(
    classResolver: ClassResolver,
    schema: ViaductSchema,
    genericValue: Any?,
    asEngineObjectData: Boolean = false,
    classLoader: ClassLoader = ClassLoader.getSystemClassLoader(),
): Any? {
    var baseValue = genericValue
    var actualListDepth = 0
    while (baseValue != null && baseValue is List<*>) {
        baseValue = baseValue.getOrNull(0)
        actualListDepth++
    }
    if (baseValue == null) return genericValue

    var result: Any? = when (val baseTypeDef = this.type.baseTypeDef) {
        is ViaductGraphQLSchema.Interface, is ViaductGraphQLSchema.Object, is ViaductGraphQLSchema.Union -> {
            if (baseTypeDef.hasAppliedDirective("noBuild")) {
                null
            } else {
                baseValue as RecordValue
                val context = MockExecutionContext.mk(schema, classLoader)
                val data = genericValueToEngineObjectData(classResolver, schema, baseValue)
                if (asEngineObjectData) {
                    data
                } else {
                    val clazz = classResolver.mainClassFor(baseValue.concreteTypeDef.name)
                    clazz.constructors[0].newInstance(context, data)
                }
            }
        }

        is ViaductGraphQLSchema.Input -> {
            val recordValue = baseValue as RecordValue
            val concreteTypeDef = recordValue.concreteTypeDef
            val graphqlInputType = schema.schema.getTypeAs<GraphQLInputObjectType>(concreteTypeDef.name)
            val clazz = classResolver.mainClassFor(concreteTypeDef.name)
            val context = MockExecutionContext.mk(schema, classLoader)
            clazz.constructors[0].newInstance(context, recordValue.asGenericMap, graphqlInputType)
        }

        is ViaductGraphQLSchema.Enum -> {
            val idx = baseTypeDef.values.indexOfFirst { it.name == (baseValue as String) }
            val vals = classResolver.mainClassFor(baseTypeDef.name).getEnumConstants()!!
            vals[idx]
        }

        is ViaductGraphQLSchema.Scalar -> {
            if (baseTypeDef.name == "ID") {
                val globalIDTypeName = grtNameForIdParam()
                if (globalIDTypeName != null) {
                    val type = classResolver.reflectionFor(globalIDTypeName) as Type<NodeObject>
                    GlobalIDImpl(type, baseValue as String)
                } else {
                    baseValue
                }
            } else {
                baseValue
            }
        }

        else -> baseValue
    }
    repeat(actualListDepth) { result = listOf(result) }
    return result
}

/** Materialize a GRT value for the provided [recordValue] */
internal fun genericValueToEngineObjectData(
    classResolver: ClassResolver,
    schema: ViaductSchema,
    recordValue: RecordValue
): Any {
    val concreteTypeDef = recordValue.concreteTypeDef
    val engineObjectDataBuilder = EngineObjectDataBuilder.from(schema.schema.getObjectType(concreteTypeDef.name))

    concreteTypeDef.codegenIncludedFields.forEach { field ->
        field.valueV2FromGenericValue(classResolver, schema, recordValue[field.name], true)?.let {
            engineObjectDataBuilder.put(field.name, it)
        }
    }
    return engineObjectDataBuilder.build()
}

/** Materialize a GRT value for the provided [recordValue] */
internal fun genericValueToValue(
    classResolver: ClassResolver,
    recordValue: RecordValue
): Any {
    val concreteTypeDef = recordValue.concreteTypeDef
    return if (concreteTypeDef is ViaductGraphQLSchema.Input) {
        materializeViaCtor(classResolver, recordValue)
    } else {
        materializeViaBuilder(classResolver, recordValue)
    }
}

private fun materializeViaCtor(
    classResolver: ClassResolver,
    recordValue: RecordValue
): Any {
    val concreteTypeDef = recordValue.concreteTypeDef
    val clazz = classResolver.mainClassFor(concreteTypeDef.name)
    val concreteName = clazz.canonicalName
    val generatedFields = concreteTypeDef.codegenIncludedFields.areGenerated()
    val ctors = clazz.constructors
    val ctorArgsInOrder = cfg.getConstructorArgOrder(clazz)
    val ctor = ctors.firstOrNull {
        it.parameterCount == ctorArgsInOrder.count() &&
            it.parameterTypes.lastOrNull()?.name != "kotlin.jvm.internal.DefaultConstructorMarker"
    }
    if (ctor == null) {
        val counts = ctors.joinToString { it.parameterCount.toString() }
        throw IllegalStateException("Can't find constructor $concreteName(${generatedFields.count()} not in { $counts }).")
    }
    val result = try {
        ctor.newInstance(
            *ctorArgsInOrder.map { fname ->
                generatedFields.first { it.name == fname }.valueFromGenericValue(classResolver, recordValue[fname])
            }.toTypedArray()
        )
    } catch (e: Exception) {
        val args = ctorArgsInOrder.zip(ctor.parameterTypes).joinToString("; ") { (expected, actual) ->
            val expField = generatedFields.firstOrNull { it.name == expected }
            val expectedType =
                if (expField == null) {
                    "Can't find $expected"
                } else {
                    expField.valueFromGenericValue(classResolver, recordValue[expected])?.let {
                        it::class.java.typeName
                    } ?: "null"
                }
            "$expected: ${expField?.type} actual=${actual.typeName}, expected=$expectedType"
        }
        throw IllegalStateException("Can't instantiate $concreteName(${ctorArgsInOrder.joinToString()})($args)", e)
    }

    if (cfg.isHasClearableFieldsInputType(concreteTypeDef)) {
        // Call the setters for fields that weren't set in the constructor
        generatedFields.filter {
            !ctorArgsInOrder.contains(it.name) && recordValue.containsKey(it.name)
        }.forEach { field ->
            val setter = clazz.declaredMethods.first { it.name == setterName(field.name) }
            setter.invoke(result, field.valueFromGenericValue(classResolver, recordValue[field.name]))
        }
    }
    return result
}

@Suppress("UNCHECKED_CAST")
private fun materializeViaBuilder(
    classResolver: ClassResolver,
    recordValue: RecordValue
): Any {
    val concreteTypeDef = recordValue.concreteTypeDef
    require(concreteTypeDef is ViaductGraphQLSchema.Object)
    val clazz = classResolver.builderClassFor(concreteTypeDef.name)
    val ctor = clazz.declaredConstructors.find { it.parameterCount == 0 }
        ?: throw IllegalStateException("Cannot find empty Builder constructor for type ${concreteTypeDef.name}")

    val b = ctor.newInstance() as BuilderBase<Any>
    concreteTypeDef.codegenIncludedFields.areGenerated().forEach { field ->
        val value = field.valueFromGenericValue(classResolver, recordValue[field.name])
        val setter = clazz.declaredMethods.find { it.name == field.name && it.parameterCount == 1 }
            ?: throw IllegalStateException("Cannot find setter for ${clazz.name}.${field.name}")
        setter.invoke(b, value)
    }

    return b.build()
}

/** Returns true iff there's only one value for this collection of fields/args. */
fun Iterable<ViaductGraphQLSchema.HasDefaultValue>.onlyOneValue(seen: List<ViaductGraphQLSchema.HasDefaultValue> = emptyList()): Boolean {
    checkDepth(seen)
    return this.all { it.type.onlyOneValue(seen + it) }
}

/** Returns true iff there's only one value for this type expression. */
private fun ViaductGraphQLSchema.TypeExpr.onlyOneValue(seen: List<ViaductGraphQLSchema.HasDefaultValue>): Boolean =
    if (this.nullableOrEmpty) {
        false
    } else {
        when (val def = this.baseTypeDef) {
            is ViaductGraphQLSchema.Enum -> def.values.count() < 2
            is ViaductGraphQLSchema.Interface, is ViaductGraphQLSchema.Union -> {
                def.possibleObjectTypes.size == 1 && def.possibleObjectTypes.first().codegenIncludedFields.onlyOneValue(seen)
            }
            is ViaductGraphQLSchema.Record -> def.codegenIncludedFields.onlyOneValue(seen)
            else -> false
        }
    }

private fun ViaductGraphQLSchema.TypeDef.onlyOneValue(): Boolean =
    if (this is ViaductGraphQLSchema.Enum && values.count() < 2) {
        true
    } else if (this is ViaductGraphQLSchema.Record) {
        fields.onlyOneValue()
    } else {
        false
    }

private val ViaductGraphQLSchema.TypeExpr.nullableOrEmpty: Boolean get() =
    baseTypeNullable || (0 < listDepth)

private fun ViaductGraphQLSchema.TypeExpr.nullOrEmpty(): Any? {
    if (!nullableOrEmpty) throw IllegalArgumentException("Must be nullable somewhere ($this).")
    var result: Any? = null
    if (isNullable) return result
    var d = 0
    do {
        result = listOf(result)
    } while (++d < listDepth && !listNullable.get(d))
    if (d < listDepth) {
        return result
    } else {
        return emptyList<Any>()
    }
}

private val ViaductGraphQLSchema.Field.isGenerated: Boolean get() {
    return !this.name.startsWith("__")
}

internal fun Iterable<ViaductGraphQLSchema.Field>.areGenerated(): Iterable<ViaductGraphQLSchema.HasDefaultValue> = this.filter { it.isGenerated }

internal data class RecordValue(val concreteTypeDef: ViaductGraphQLSchema.Record, val fields: Map<String, Any?>) : Map<String, Any?> by fields {
    private fun Any?.unwrap(): Any? =
        when (this) {
            null -> null
            is List<*> -> map { it.unwrap() }
            is Map<*, *> -> mapValues { (k, v) -> v.unwrap() }
            else -> this
        }

    /** Unwrap this (and all nested) RecordValue's into generic values */
    @Suppress("UNCHECKED_CAST")
    val asGenericMap: Map<String, Any?> get() = fields.unwrap() as Map<String, Any?>
}
