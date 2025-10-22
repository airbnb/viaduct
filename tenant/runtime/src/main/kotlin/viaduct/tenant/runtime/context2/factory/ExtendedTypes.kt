package viaduct.tenant.runtime.context2.factory

import kotlin.reflect.KClass
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.GRT
import viaduct.api.types.Object
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ViaductSchema
import viaduct.tenant.runtime.getArgumentsGRTConstructor
import viaduct.tenant.runtime.getGRTConstructor

sealed class ExtendedType<T : GRT>(
    cls: KClass<T>,
) : Type<T> {
    override val name: String = cls.simpleName!!
    override val kcls: KClass<out T> = cls

    override fun equals(other: Any?): Boolean {
        if (other !is Type<*>) return false
        return name == other.name && kcls == other.kcls
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + kcls.hashCode()
        return result
    }
}

class ObjectType<T : Object>(
    cls: KClass<T>,
) : ExtendedType<T>(cls) {
    private val constructor = kcls.getGRTConstructor()

    fun makeGRT(
        ctx: InternalContext,
        eod: EngineObjectData
    ) = constructor.call(ctx, eod)
}

class ArgumentsType<T : Arguments>(
    cls: KClass<T>,
    schema: ViaductSchema,
) : ExtendedType<T>(cls) {
    private val isNoArguments = (cls == Arguments.NoArguments::class)
    private val constructor = if (isNoArguments) null else cls.getArgumentsGRTConstructor()
    private val graphqlInputObjectType = if (isNoArguments) null else Arguments.inputType(cls.simpleName!!, schema)

    @Suppress("UNCHECKED_CAST")
    fun makeGRT(
        ctx: InternalContext,
        args: Map<String, Any?>
    ): T =
        if (isNoArguments) {
            Arguments.NoArguments as T
        } else {
            constructor!!.call(ctx, args, graphqlInputObjectType!!)
        }
}
