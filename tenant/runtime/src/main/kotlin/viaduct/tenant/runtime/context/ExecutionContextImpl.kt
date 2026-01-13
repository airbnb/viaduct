package viaduct.tenant.runtime.context

import viaduct.api.context.ExecutionContext
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.Type
import viaduct.api.types.NodeCompositeOutput
import viaduct.api.types.NodeObject
import viaduct.tenant.runtime.globalid.GlobalIDImpl

/**

 * The root of our ExecutionContext implementation hierarchy.  We
 * don't bother with a direct implementaiton of InternalContext
 * because this would be the only subclass of such a class.
 * Strangely, we do take a [baseData] object that happens to be an
 * [InternalContext] -- and we copy its elements into the field of our
 * own [InternalContext].  We could've used delegation here, but
 * that's been a bit confusing so we explicitly delegate here.
 *
 * The reason there's a separate "base data" object floating around is
 * because of GRT factories: they also need an [InternalContext]
 * object, and we want to create those factories independent of our
 * context objects, so we create a "base data" object to be used
 * both for creating the GRT factories and for creating these
 * context objects.
 *
 * The hierarchy:
 *
 * ExecutionContextImpl (sealed)
 *     VariableProviderContextImpl
 *     ResolverExecutionContextImpl (sealed)
 *         NodeExecutionContextImpl
 *         SealedFieldExecutionContextImpl (sealed)
 *             FieldExecutionContextImpl
 *             MutationFieldExecutionContextImpl
 */
sealed class ExecutionContextImpl(
    private val baseData: InternalContext,
) : ExecutionContext, InternalContext {
    override val schema = baseData.schema
    override val globalIDCodec = baseData.globalIDCodec
    override val reflectionLoader = baseData.reflectionLoader

    override fun <T : NodeObject> globalIDFor(
        type: Type<T>,
        internalID: String
    ) = GlobalIDImpl(type, internalID)

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> = baseData.deserializeGlobalID(serialized)
}
