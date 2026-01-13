package viaduct.tenant.codegen.bytecode.exercise

import java.lang.reflect.InvocationTargetException
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import viaduct.api.ViaductTenantUsageException
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.codegen.km.getterName
import viaduct.engine.api.EngineObject
import viaduct.engine.api.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.cfg

internal suspend fun Exerciser.exerciseObjectV2(
    expected: ViaductSchema.Object,
    schema: ViaductGraphQLSchema
) {
    if (expected.name in cfg.nativeGraphQLTypeToKmName(ViaductBaseTypeMapper(this.schema)).keys) return
    val cls = check.tryResolveClass("OBJECT_CLASS_EXISTS", classResolver) {
        mainClassFor(expected.name)
    }
    cls ?: return
    val builderCls = check.tryResolveClass("OBJECT_BUILDER_CLASS_EXISTS", classResolver) {
        v2BuilderClassFor(expected.name)
    }
    builderCls ?: return
    exerciseBuilderRoundtrip(expected, schema)
    exerciseReflectionObject(cls.kotlin, expected)
}

private suspend fun Exerciser.exerciseBuilderRoundtrip(
    expected: ViaductSchema.Object,
    schema: ViaductGraphQLSchema
) {
    val objClazz = classResolver.mainClassFor(expected.name)
    val objCtor = objClazz.constructors.firstOrNull {
        it.parameterCount == 2 &&
            it.parameterTypes[0] == InternalContext::class.java &&
            it.parameterTypes[1] == EngineObject::class.java
    }
    check.isNotNull(objCtor, "OBJECT_CONSTRUCTOR")
    objCtor ?: return

    val builderClazz = classResolver.v2BuilderClassFor(expected.name)
    val builderCtor = builderClazz.constructors.firstOrNull {
        it.parameterCount == 1 && it.parameterTypes[0] == ExecutionContext::class.java
    }
    check.isNotNull(builderCtor, "OBJECT_BUILDER_CONSTRUCTOR")
    builderCtor ?: return

    val builder = builderCtor.newInstance(
        MockInternalContext(
            schema,
            GlobalIDCodecDefault,
            reflectionLoaderForClassResolver(classResolver)
        ).executionContext
    )

    val expValues = expected.fields.mapNotNull { field ->
        val fName = field.name
        val setter = builderClazz.declaredMethods.firstOrNull { it.name == fName && it.parameterCount == 1 }
        check.isNotNull(setter, "OBJECT_SETTER:$fName")

        val value = field.createValueV2(classResolver, schema)
        setter?.let {
            setter.invoke(builder, value)
            fName to value
        }
    }.toMap()

    val buildFunc = builderClazz.declaredMethods.firstOrNull { it.name == "build" && it.parameterCount == 0 }
    check.isNotNull(buildFunc, "OBJECT_BUILDER_BUILD")
    buildFunc ?: return

    val objFromBuilder = buildFunc.invoke(builder)
    expected.fields.forEach { field ->
        val fName = field.name
        // 2 getters are generated. First getter has 2 params: one for the nullable alias string,
        // and one for continuation object due to suspend.
        val getterWithAlias = objFromBuilder::class.declaredFunctions.firstOrNull {
            it.name == getterName(fName) && it.parameters.size == 2
        }
        check.isNotNull(getterWithAlias, "OBJECT_GETTER:$fName")
        // Second getter is to handle default null value.
        // Param count is one here due to continuation object param for suspending function.
        val getterForDefault = objFromBuilder::class.declaredFunctions.firstOrNull {
            it.name == getterName(fName) && it.parameters.size == 1
        }
        check.isNotNull(getterForDefault, "OBJECT_DEFAULT_GETTER:$fName")

        getterWithAlias?.let {
            val actValue = try {
                getterWithAlias.callSuspend(objFromBuilder, null)
            } catch (e: InvocationTargetException) {
                if (e.targetException is ViaductTenantUsageException) {
                    check.addFailure(
                        "missing setter for field $fName causing exception when get: $e",
                        "OBJECT_SETTER_MISSING:$fName",
                        emptyArray()
                    )
                } else {
                    check.addExceptionFailure(
                        e.targetException ?: e,
                        "OBJECT_GETTER_EXCEPTION:$fName - exception when getting field $fName",
                        emptyArray()
                    )
                }
                return@forEach
            }

            val expValue = expValues[fName]
            if (field.type.isList) {
                check.isEqualTo(
                    unwrapList(expValue as List<Any?>),
                    unwrapList(actValue as List<Any?>),
                    "OBJECT_GETTER_LIST_VALUE:$fName"
                )
                check.isEqualTo(
                    listDepth(expValue),
                    listDepth(actValue),
                    "OBJECT_GETTER_LIST_DEPTH:$fName"
                )
            } else if (field.type.baseTypeDef.isComposite) {
                check.isEqualTo(
                    (expValue as ObjectBase).engineObject,
                    (actValue as ObjectBase).engineObject,
                    "OBJECT_GETTER_ENGINE_OBJECT_DATA:$fName"
                )
            } else if (field.type.baseTypeDef is ViaductSchema.Enum) {
                check.isEqualTo((expValue as Enum<*>).name, (actValue as Enum<*>).name, "OBJECT_GETTER_ENUM_NAME:$fName")
                check.isEqualTo(expValue.ordinal, actValue.ordinal, "OBJECT_GETTER_ENUM_ORDS:$fName")
                check.isEqualTo(expValue.toString(), actValue.toString(), "OBJECT_GETTER_ENUM_TOSTRING:$fName")
            } else {
                check.isEqualTo(expValue, actValue, "OBJECT_GETTER_VALUE:$fName")
            }
        }
    }
}

private fun unwrapList(result: List<Any?>): Any? {
    return result.map {
        if (it is List<*>) {
            unwrapList(it)
        } else if (it is ObjectBase) {
            it.engineObject
        } else {
            it
        }
    }
}

private fun listDepth(result: List<Any?>): Int {
    return result.map {
        if (it is List<*>) {
            1 + listDepth(it)
        } else {
            1
        }
    }.max()
}
