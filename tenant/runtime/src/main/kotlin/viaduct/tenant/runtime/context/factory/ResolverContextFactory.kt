package viaduct.tenant.runtime.context.factory

import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.valueParameters
import viaduct.api.context.FieldExecutionContext

object ResolverContextFactory {
    /**
     * Create a [Factory] that returns a [Ctx] for the provided [contextCls].
     * [contextCls] must define a primary constructor that accepts a single
     * [FieldExecutionContext] argument
     */
    fun <Ctx : FieldExecutionContext<*, *, *, *>> forClass(
        contextCls: KClass<Ctx>,
        innerCtxFactory: FieldExecutionContextFactory
    ): FieldExecutionContextFactory {
        require(contextCls.hasRequiredCtor) {
            "Class ${contextCls.qualifiedName} does not define the expected constructor"
        }
        return FieldExecutionContextFactory { args -> contextCls.primaryConstructor!!.call(innerCtxFactory.make(args)) }
    }

    private val KClass<*>.hasRequiredCtor: Boolean get() =
        primaryConstructor?.valueParameters?.let { params ->
            if (params.size != 1) {
                false
            } else {
                when (val classifier = params[0].type.classifier) {
                    is KClass<*> -> classifier.isSubclassOf(FieldExecutionContext::class)
                    else -> false
                }
            }
        } ?: false
}
