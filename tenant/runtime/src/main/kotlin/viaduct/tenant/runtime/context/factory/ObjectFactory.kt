package viaduct.tenant.runtime.context.factory

import kotlin.reflect.KClass
import viaduct.api.internal.InternalContext
import viaduct.api.types.Object
import viaduct.engine.api.EngineObjectData
import viaduct.tenant.runtime.getGRTConstructor

class ObjectArgs(
    val internalContext: InternalContext,
    /** A request-scoped EngineObjectData of the object that a resolver is running on */
    val objectValue: EngineObjectData,
)

object ObjectFactory {
    /** create a [Factory] that returns [Object] values based on the provided [objectCls] */
    fun <T : Object> forClass(objectCls: KClass<out T>): Factory<ObjectArgs, T> {
        val constructor = objectCls.getGRTConstructor()
        return Factory { args ->
            constructor.call(args.internalContext, args.objectValue)
        }
    }
}
