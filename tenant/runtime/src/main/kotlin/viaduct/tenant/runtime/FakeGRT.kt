package viaduct.tenant.runtime

import graphql.schema.GraphQLInputObjectType
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.Mutation
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.TestingApi
import viaduct.engine.api.EngineObjectData

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
@TestingApi
annotation class FakeGRT

/**
 * For testing without codegen: [KClass.makeGRT] will construct these
 * directly independent of the constructor-protocol of "real" GRTs.
 */
@FakeGRT
open class FakeObject(val ctx: InternalContext, val data: EngineObjectData) : ObjectBase(ctx, data), Object {
    suspend inline fun <reified T> get(
        fieldName: String,
        alias: String? = null
    ): T {
        // lists are not supported due to type erasure
        require(!T::class.isSubclassOf(List::class)) {
            "Lists are not supported by this implementation of get. Use `get(key, baseClass)`"
        }
        return get(fieldName, T::class, alias)
    }
}

/**
 * For testing without codegen: [KClass.makeGRT] will construct these
 * directly independent of the constructor-protocol of "real" GRTs.
 */
@FakeGRT
class FakeQuery(ctx: InternalContext, data: EngineObjectData) : FakeObject(ctx, data), Query {
    object Reflection : Type<Query> {
        override val name: String = "Query"
        override val kcls = FakeQuery::class
    }
}

/**
 * For testing without codegen: [KClass.makeGRT] will construct these
 * directly independent of the constructor-protocol of "real" GRTs.
 */
@FakeGRT
class FakeMutation(ctx: InternalContext, data: EngineObjectData) : FakeObject(ctx, data), Mutation {
    object Reflection : Type<Mutation> {
        override val name: String = "Mutation"
        override val kcls = FakeMutation::class
    }
}

/**
 * For testing without codegen: [KClass.makeGRT] will construct these
 * directly independent of the constructor-protocol of "real" GRTs.
 */
@FakeGRT
@Suppress("UNUSED_PARAMETER")
class FakeArguments(
    context: InternalContext? = null,
    val inputData: Map<String, Any?>,
    inputType: GraphQLInputObjectType? = null,
) : Arguments {
    /** try to get the argument with the provided name, returning null if no value is found */
    @Suppress("UNCHECKED_CAST")
    fun <T> tryGet(argName: String): T? = inputData[argName] as? T

    /** get the argument with the provided name, will throw IllegalArgumentException if no non-null value is found */
    fun <T> get(argName: String): T = requireNotNull(tryGet(argName)) { "$argName is unset or null." }
}
