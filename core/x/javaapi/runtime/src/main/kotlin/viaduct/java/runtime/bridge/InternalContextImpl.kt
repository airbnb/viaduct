package viaduct.java.runtime.bridge

import graphql.schema.GraphQLInputObjectType
import viaduct.api.internal.InputTypeFactory
import viaduct.engine.api.ViaductSchema
import viaduct.errors.TenantUsageException
import viaduct.java.api.globalid.GlobalID
import viaduct.java.api.internal.InternalContext
import viaduct.java.api.types.NodeCompositeOutput
import viaduct.service.api.spi.GlobalIDCodec

/**
 * Runtime implementation of the Java [InternalContext].
 *
 * Java mirror of Kotlin's [viaduct.tenant.runtime.internal.InternalContextImpl]: an API-layer
 * interface whose implementation lives in the runtime layer. Built once per resolve from the
 * engine's [viaduct.engine.api.EngineExecutionContext], then attached to top-level GRTs and
 * propagated to nested GRTs via their constructors.
 */
internal class InternalContextImpl(
    private val schema: ViaductSchema,
    private val globalIDCodec: GlobalIDCodec,
    private val grtPackagePrefix: String? = null,
) : InternalContext {
    override fun getSchema(): ViaductSchema = schema

    override fun getArgumentsInputType(
        name: String,
        containingTypeName: String,
        fieldName: String,
    ): GraphQLInputObjectType = InputTypeFactory.argumentsInputType(name, containingTypeName, fieldName, schema)

    override fun getGlobalIDCodec(): GlobalIDCodec = globalIDCodec

    override fun <T : NodeCompositeOutput> deserializeGlobalID(serialized: String): GlobalID<T> {
        val (typeName, internalId) = try {
            globalIDCodec.deserialize(serialized)
        } catch (e: IllegalArgumentException) {
            throw TenantUsageException("Invalid GlobalID: \"$serialized\"", e)
        }
        return GlobalIDImpl(type = typeFromName(typeName, grtPackagePrefix), internalId = internalId)
    }
}
