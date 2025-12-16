package viaduct.tenant.codegen.bytecode.exercise

import graphql.schema.GraphQLInputObjectType
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.full.declaredMemberProperties
import viaduct.api.ViaductTenantUsageException
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.InputTypeFactory
import viaduct.api.internal.InternalContext
import viaduct.api.mocks.MockExecutionContext
import viaduct.engine.api.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg

internal fun Exerciser.exerciseArgInputV2(
    field: ViaductSchema.Field,
    schema: ViaductGraphQLSchema
) {
    val argumentName = cfg.argumentTypeName(field)
    val inputClazz = check.tryResolveClass("INPUT_CLASS_EXISTS", classResolver) {
        mainClassFor(argumentName)
    }
    inputClazz ?: return
    val builderClazz = check.tryResolveClass("INPUT_BUILDER_CLASS_EXISTS", classResolver) {
        v2BuilderClassFor(argumentName)
    }
    builderClazz ?: return
    exerciseBuilderRoundtrip(
        inputClazz,
        builderClazz,
        field.args,
        buildGraphqlInputObjectType(field, schema),
        schema
    )
}

internal fun Exerciser.exerciseInputV2(
    expected: ViaductSchema.Input,
    schema: ViaductGraphQLSchema
) {
    val inputClazz = check.tryResolveClass("INPUT_CLASS_EXISTS", classResolver) {
        mainClassFor(expected.name)
    }
    inputClazz ?: return
    val builderClazz = check.tryResolveClass("INPUT_BUILDER_CLASS_EXISTS", classResolver) {
        v2BuilderClassFor(expected.name)
    }
    builderClazz ?: return
    val publicBuilderCtor = builderClazz.constructors.firstOrNull {
        it.parameterCount == 1 && it.parameterTypes[0] == ExecutionContext::class.java
    }
    check.isNotNull(publicBuilderCtor, "INPUT_BUILDER_PUBLIC_CONSTRUCTOR_EXISTS")

    exerciseBuilderRoundtrip(
        inputClazz,
        builderClazz,
        expected.fields,
        schema.schema.getTypeAs(expected.name),
        schema
    )
    exerciseReflectionObject(inputClazz.kotlin, expected)
}

private fun Exerciser.exerciseBuilderRoundtrip(
    inputClazz: Class<*>,
    builderClazz: Class<*>,
    fields: Iterable<ViaductSchema.HasDefaultValue>,
    graphQLInputObjectType: GraphQLInputObjectType,
    schema: ViaductGraphQLSchema
) {
    val inputCtor = inputClazz.declaredConstructors.first { it.parameterCount == 3 }
    check.isNotNull(inputCtor, "INPUT_CONSTRUCTOR_EXISTS")
    inputCtor ?: return

    val toBuilderFunc = inputClazz.declaredMethods.firstOrNull { it.name == "toBuilder" }
    check.isNotNull(toBuilderFunc, "INPUT_TOBUILDER_EXISTS")
    toBuilderFunc ?: return

    val builderCtor = builderClazz.constructors.firstOrNull {
        it.parameterCount == 3 &&
            it.parameterTypes[0] == InternalContext::class.java &&
            it.parameterTypes[1] == GraphQLInputObjectType::class.java &&
            it.parameterTypes[2] == java.util.Map::class.java
    }
    check.isNotNull(builderCtor, "INPUT_BUILDER_CONSTRUCTOR_EXISTS")
    builderCtor ?: return
    val buildFunc = builderClazz.declaredMethods.firstOrNull { it.name == "build" }
    check.isNotNull(buildFunc, "INPUT_BUILDER_BUILD_EXISTS")
    buildFunc ?: return

    val context = MockExecutionContext.mk(schema, classLoader)

    // 1. get builder
    val builder = builderCtor.newInstance(context, graphQLInputObjectType, java.util.LinkedHashMap<String, Any?>())

    // 2. set values and get actualInput
    val expValues = fields.mapNotNull { field ->
        val fName = field.name
        val setter = builderClazz.declaredMethods.firstOrNull { it.name == fName && it.parameterCount == 1 }
        check.isNotNull(setter, "INPUT_BUILDER_SETTER_EXISTS:$fName")

        val value = field.createValueV2(classResolver, schema, classLoader = classLoader)
        setter?.let {
            setter.invoke(builder, value)
            fName to value
        }
    }.toMap()
    val actualInput = buildFunc.invoke(builder)

    // 3. compare actualInput with expValues
    compareFieldValues(expValues, actualInput, fields, inputClazz)

    // 4. use actualInput to test Input.toBuilder().build() roundtrip
    val builder1 = toBuilderFunc.invoke(actualInput)
    val actualInput1 = buildFunc.invoke(builder1)
    check.isEqualTo(actualInput, actualInput1, "INPUT_TOBUILDER_ROUNDTRIP")
}

private fun Exerciser.compareFieldValues(
    expected: Map<String, Any?>,
    actual: Any?,
    fields: Iterable<ViaductSchema.HasDefaultValue>,
    inputClazz: Class<*>
) {
    fields.forEach { field ->
        val fName = field.name
        val getter = inputClazz.kotlin.declaredMemberProperties.find { it.name == fName }
        check.isNotNull(getter, "INPUT_PROPERTY_EXISTS:$fName")
        getter ?: return@forEach

        val actValue = try {
            getter.getter.call(actual)
        } catch (e: InvocationTargetException) {
            if (e.targetException is ViaductTenantUsageException) {
                check.addFailure(
                    "missing setter for field $fName causing exception when get: $e",
                    "INPUT_BUILDER_SETTER_MISSING:$fName",
                    emptyArray()
                )
            } else {
                check.addFailure(
                    "exception when getting field $fName: $e",
                    "INPUT_GETTER_EXCEPTION:$fName",
                    emptyArray()
                )
            }
            return@forEach
        }
        val expValue = expected[fName]

        check.isEqualTo(expValue, actValue, "OBJECT_GETTER_VALUE:$fName")
    }
}

private fun buildGraphqlInputObjectType(
    field: ViaductSchema.Field,
    schema: ViaductGraphQLSchema
): GraphQLInputObjectType =
    InputTypeFactory.argumentsInputType(
        cfg.argumentTypeName(field.containingDef.name, field.name),
        schema
    )
