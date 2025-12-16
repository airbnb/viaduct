package viaduct.api.grts

import graphql.schema.GraphQLInputObjectType
import kotlin.reflect.KClass
import viaduct.api.context.ExecutionContext
import viaduct.api.internal.FieldImpl
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.internal
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.Enum
import viaduct.api.types.Input
import viaduct.api.types.Object
import viaduct.engine.api.EngineObject

/**
 * This file contains GRTs for codegen exerciser tests. Some of these are malformed to test error cases.
 */
class ObjectV2(
    context: InternalContext,
    engineObject: EngineObject
) : ObjectBase(context, engineObject), Object {
    final suspend fun getStringField(alias: String?): String {
        return fetch("stringField", String::class, alias)
    }

    final suspend fun getStringField(): String {
        return getStringField(null)
    }

    final suspend fun getIntField(alias: String?): Int {
        return fetch("intField", Int::class, alias)
    }

    final suspend fun getIntField(): Int {
        return getIntField(null)
    }

    final suspend fun getListField(alias: String?): List<String?>? {
        return fetch("listField", String::class, alias)
    }

    final suspend fun getListField(): List<String?>? {
        return getListField(null)
    }

    final suspend fun getNestedListField(alias: String?): List<List<String?>?>? {
        return fetch("nestedListField", String::class, alias)
    }

    final suspend fun getNestedListField(): List<List<String?>?>? {
        return getNestedListField(null)
    }

    class Builder(context: ExecutionContext) :
        ObjectBase.Builder<ObjectV2>(
            context.internal,
            context.internal.schema.schema.getObjectType("ObjectV2"),
            null
        ) {
        fun stringField(stringField: String): Builder {
            putInternal("stringField", stringField)
            return this
        }

        fun intField(intField: Int?): Builder {
            put("intField", intField)
            return this
        }

        fun listField(listField: List<String?>?): Builder {
            put("listField", listField)
            return this
        }

        fun nestedListField(nestedListField: List<List<String?>?>?): Builder {
            put("nestedListField", nestedListField)
            return this
        }

        override fun build() = ObjectV2(context, buildEngineObjectData())
    }

    object Reflection : Type<ObjectV2> {
        override val kcls = ObjectV2::class
        override val name = "ObjectV2"

        object Fields {
            val __typename = FieldImpl("__typename", Reflection)
            val intField = FieldImpl("intField", Reflection)
            val stringField = FieldImpl("stringField", Reflection)
            val listField = FieldImpl("listField", Reflection)
            val nestedListField = FieldImpl("nestedListField", Reflection)
        }
    }
}

class MissingBuilderObjectV2(
    context: InternalContext,
    engineObject: EngineObject
) : ObjectBase(context, engineObject), Object {
    final suspend fun getStringField(alias: String?): String {
        return fetch("stringField", String::class, alias)
    }

    final suspend fun getStringField(): String {
        return getStringField(null)
    }
}

class MissingGetterObjectV2(
    context: InternalContext,
    engineObject: EngineObject
) : ObjectBase(context, engineObject), Object {
    class Builder(context: ExecutionContext) :
        ObjectBase.Builder<MissingGetterObjectV2>(
            context.internal,
            context.internal.schema.schema.getObjectType("MissingGetterObjectV2"),
            null
        ) {
        fun stringField(stringField: String): Builder {
            put("stringField", stringField)
            return this
        }

        override fun build() = MissingGetterObjectV2(context, buildEngineObjectData())
    }
}

class MissingDefaultGetterObjectV2(
    context: InternalContext,
    engineObject: EngineObject
) : ObjectBase(context, engineObject), Object {
    final suspend fun getStringField(alias: String?): String {
        return fetch("stringField", String::class, alias)
    }

    class Builder(context: ExecutionContext) :
        ObjectBase.Builder<MissingDefaultGetterObjectV2>(
            context.internal,
            context.internal.schema.schema.getObjectType("MissingDefaultGetterObjectV2"),
            null
        ) {
        fun stringField(stringField: String): Builder {
            put("stringField", stringField)
            return this
        }

        override fun build() = MissingDefaultGetterObjectV2(context, buildEngineObjectData())
    }
}

class MissingNonDefaultGetterObjectV2(
    context: InternalContext,
    engineObject: EngineObject
) : ObjectBase(context, engineObject), Object {
    final suspend fun getStringField(): String {
        return getStringField()
    }

    class Builder(context: ExecutionContext) :
        ObjectBase.Builder<MissingNonDefaultGetterObjectV2>(
            context.internal,
            context.internal.schema.schema.getObjectType("MissingNonDefaultGetterObjectV2"),
            null
        ) {
        fun stringField(stringField: String): Builder {
            put("stringField", stringField)
            return this
        }

        override fun build() = MissingNonDefaultGetterObjectV2(context, buildEngineObjectData())
    }
}

class MissingSetterObjectV2(
    context: InternalContext,
    engineObject: EngineObject
) : ObjectBase(context, engineObject), Object {
    final suspend fun getStringField(alias: String? = null): String {
        return fetch("stringField", String::class, alias)
    }

    final suspend fun getStringField(): String {
        return getStringField(null)
    }

    class Builder(context: ExecutionContext) :
        ObjectBase.Builder<MissingSetterObjectV2>(
            context.internal,
            context.internal.schema.schema.getObjectType("MissingSetterObjectV2"),
            null
        ) {
        override fun build() = MissingSetterObjectV2(context, buildEngineObjectData())
    }
}

class InputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    val stringField: String get() = get("stringField")
    val intField: Int? get() = get("intField")
    val listField: List<String?>? get() = get("listField")
    val nestedField: List<List<String?>?>? get() = get("nestedField")

    fun toBuilder(): Builder {
        return Builder(context, graphQLInputObjectType, inputData)
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        inputData: Map<String, Any?> = LinkedHashMap()
    ) : InputLikeBase.Builder() {
        // public for tenants
        constructor(context: ExecutionContext) : this(
            context.internal,
            context.internal.schema.schema.getTypeAs("InputV2")
        )

        override val inputData: MutableMap<String, Any?> = LinkedHashMap(inputData)

        fun stringField(value: String): Builder {
            inputData["stringField"] = value
            return this
        }

        fun intField(value: Int?): Builder {
            inputData["intField"] = value
            return this
        }

        fun listField(value: List<String?>?): Builder {
            inputData["listField"] = value
            return this
        }

        fun nestedField(value: List<List<String?>?>?): Builder {
            inputData["nestedField"] = value
            return this
        }

        fun build(): InputV2 {
            validateInputDataAndThrowAsTenantError()
            return InputV2(context, inputData, graphQLInputObjectType)
        }
    }

    object Reflection : Type<InputV2> {
        override val name: String = "InputV2"
        override val kcls: KClass<out InputV2> = InputV2::class

        object Fields {
            val __typename = FieldImpl("__typename", Reflection)
            val stringField = FieldImpl("stringField", Reflection)
            val intField = FieldImpl("intField", Reflection)
            val listField = FieldImpl("listField", Reflection)
            val nestedField = FieldImpl("nestedField", Reflection)
        }
    }
}

class MissingBuilderInputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    val stringField: String? get() = get("stringField")
}

class MissingBuilderBuildInputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    val stringField: String? get() = get("stringField")

    fun toBuilder(): Builder {
        return Builder(context, graphQLInputObjectType, inputData)
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        inputData: Map<String, Any?> = LinkedHashMap()
    ) : InputLikeBase.Builder() {
        // public for tenants
        constructor(context: ExecutionContext) : this(
            context.internal,
            context.internal.schema.schema.getTypeAs("MissingBuilderBuildInputV2")
        )

        override val inputData: MutableMap<String, Any?> = LinkedHashMap(inputData)

        fun stringField(value: String?): Builder {
            inputData["stringField"] = value
            return this
        }
    }
}

class MissingBuilderSetterInputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    val stringField: String? get() = get("stringField")

    fun toBuilder(): Builder {
        return Builder(context, graphQLInputObjectType, inputData)
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        inputData: Map<String, Any?> = LinkedHashMap()
    ) : InputLikeBase.Builder() {
        // public for tenants
        constructor(context: ExecutionContext) : this(
            context.internal,
            context.internal.schema.schema.getTypeAs("MissingToBuilderInputV2")
        )

        override val inputData: MutableMap<String, Any?> = LinkedHashMap(inputData)

        fun build(): MissingBuilderSetterInputV2 {
            validateInputDataAndThrowAsTenantError()
            return MissingBuilderSetterInputV2(context, inputData, graphQLInputObjectType)
        }
    }
}

class MissingPropertyInputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    fun toBuilder(): Builder {
        return Builder(context, graphQLInputObjectType, inputData)
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        inputData: Map<String, Any?> = LinkedHashMap()
    ) : InputLikeBase.Builder() {
        // public for tenants
        constructor(context: InternalContext) : this(
            context,
            context.schema.schema.getTypeAs("MissingToBuilderInputV2")
        )

        override val inputData: MutableMap<String, Any?> = LinkedHashMap(inputData)

        fun stringField(value: String?): Builder {
            inputData["stringField"] = value
            return this
        }

        fun build(): MissingPropertyInputV2 {
            validateInputDataAndThrowAsTenantError()
            return MissingPropertyInputV2(context, inputData, graphQLInputObjectType)
        }
    }
}

class MissingToBuilderInputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    val stringField: String? get() = get("stringField")

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType
    ) : InputLikeBase.Builder() {
        // public for tenants
        constructor(context: ExecutionContext) : this(
            context.internal,
            context.internal.schema.schema.getTypeAs("MissingToBuilderInputV2")
        )

        override val inputData: MutableMap<String, Any?> = mutableMapOf()

        fun stringField(value: String?): Builder {
            inputData["stringField"] = value
            return this
        }

        fun build(): MissingToBuilderInputV2 {
            validateInputDataAndThrowAsTenantError()
            return MissingToBuilderInputV2(context, inputData, graphQLInputObjectType)
        }
    }
}

class MissingGetterImplInputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    val enumField: ExerciserTestEnum? = null

    fun toBuilder(): Builder {
        return Builder(context, graphQLInputObjectType, inputData)
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        inputData: Map<String, Any?> = LinkedHashMap()
    ) : InputLikeBase.Builder() {
        constructor(context: ExecutionContext) : this(
            context.internal,
            context.internal.schema.schema.getTypeAs("MissingGetterImplInputV2")
        )

        override val inputData: MutableMap<String, Any?> = mutableMapOf(
            "enumField" to "A"
        )

        fun enumField(value: ExerciserTestEnum?): Builder {
            inputData["enumField"] = value
            return this
        }

        fun build(): MissingGetterImplInputV2 {
            validateInputDataAndThrowAsTenantError()
            return MissingGetterImplInputV2(context, inputData, graphQLInputObjectType)
        }
    }
}

class MissingBuilderConstructorInputV2 internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Input {
    val stringField: String get() = get("stringField")

    fun toBuilder(): Builder {
        return Builder(context, graphQLInputObjectType, inputData)
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        inputData: Map<String, Any?> = LinkedHashMap()
    ) : InputLikeBase.Builder() {
        override val inputData: MutableMap<String, Any?> = LinkedHashMap(inputData)

        fun stringField(value: String): Builder {
            inputData["stringField"] = value
            return this
        }

        fun build(): MissingBuilderConstructorInputV2 {
            validateInputDataAndThrowAsTenantError()
            return MissingBuilderConstructorInputV2(context, inputData, graphQLInputObjectType)
        }
    }
}

enum class ExerciserTestEnum : Enum {
    A,
    B,
    C;

    object Reflection : Type<ExerciserTestEnum> {
        override val kcls = ExerciserTestEnum::class
        override val name = "ExerciserTestEnum"
    }
}

class TestArgObject(
    context: InternalContext,
    engineObject: EngineObject
) : ObjectBase(context, engineObject), Object {
    final suspend fun getTest(alias: String? = null): String {
        return fetch("test", String::class, alias)
    }

    final suspend fun getTest(): String {
        return getTest(null)
    }

    class Builder(context: ExecutionContext) :
        ObjectBase.Builder<TestArgObject>(
            context.internal,
            context.internal.schema.schema.getObjectType("TestArgObject"),
            null
        ) {
        fun test(test: String): Builder {
            put("test", test)
            return this
        }

        override fun build() = TestArgObject(context, buildEngineObjectData())
    }
}

class TestArgObject_Test_Arguments internal constructor(
    override val context: InternalContext,
    override val inputData: Map<String, Any?>,
    override val graphQLInputObjectType: GraphQLInputObjectType,
) : InputLikeBase(), Arguments {
    val input: InputV2? get() = get("input")
    val enum: ExerciserTestEnum get() = get("enum")

    fun toBuilder(): Builder {
        return Builder(context, graphQLInputObjectType, inputData)
    }

    class Builder internal constructor(
        override val context: InternalContext,
        override val graphQLInputObjectType: GraphQLInputObjectType,
        inputData: Map<String, Any?> = LinkedHashMap()
    ) : InputLikeBase.Builder() {
        // public for tenants
        constructor(context: ExecutionContext) : this(
            context.internal,
            context.internal.schema.schema.getTypeAs("TestArgObject_Test_Arguments")
        )

        override val inputData: MutableMap<String, Any?> = LinkedHashMap(inputData)

        fun input(value: InputV2?): Builder {
            put("input", value)
            return this
        }

        fun enum(value: ExerciserTestEnum): Builder {
            put("enum", value)
            return this
        }

        fun build(): TestArgObject_Test_Arguments {
            validateInputDataAndThrowAsTenantError()
            return TestArgObject_Test_Arguments(context, inputData, graphQLInputObjectType)
        }
    }
}
