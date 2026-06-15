package viaduct.tenant.runtime.context

import viaduct.api.globalid.GlobalID
import viaduct.api.internal.InputLikeBase
import viaduct.api.internal.InternalContext
import viaduct.api.reflect.RootObjectField
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Mutation
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObject
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.errors.FrameworkException
import viaduct.errors.handleFrameworkErrors
import viaduct.errors.handleFrameworkErrorsSuspend
import viaduct.tenant.runtime.TenantApiInputValueNormalizer.normalizeVariablesForEngine
import viaduct.tenant.runtime.select.SelectionSetImpl
import viaduct.tenant.runtime.toObjectGRT

/**
 * A wrapper around [EngineExecutionContext] that converts EEC methods into
 * their Tenant-API equivalents.  We define an interface here for testing
 * purposes: this is a good place to provide mocks to test the other
 * context implementations.
 */
interface EngineExecutionContextWrapper {
    val engineExecutionContext: EngineExecutionContext

    suspend fun <T : Query> query(
        ctx: InternalContext,
        selections: SelectionSet<T>
    ): T

    suspend fun <T : Mutation> mutation(
        ctx: InternalContext,
        selections: SelectionSet<T>
    ): T

    fun <T : NodeObject> nodeRef(
        ctx: InternalContext,
        globalID: GlobalID<T>
    ): T

    fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T>

    fun <A : Arguments, BR : Object> rootFieldRef(
        ctx: InternalContext,
        field: RootObjectField<*, BR, A>,
        arguments: A
    ): BR
}

class EngineExecutionContextWrapperImpl(
    override val engineExecutionContext: EngineExecutionContext,
) : EngineExecutionContextWrapper {
    override suspend fun <T : Query> query(
        ctx: InternalContext,
        selections: SelectionSet<T>
    ): T =
        handleFrameworkErrorsSuspend("query") {
            engineExecutionContext.resolveSelectionSet(
                selections.getEngineSelectionSet(),
                ResolveSelectionSetOptions.DEFAULT
            ).toObjectGRT(ctx, selections.type.kcls)
        }

    override suspend fun <T : Mutation> mutation(
        ctx: InternalContext,
        selections: SelectionSet<T>
    ): T =
        handleFrameworkErrorsSuspend("mutation") {
            engineExecutionContext.resolveSelectionSet(
                selections.getEngineSelectionSet(),
                ResolveSelectionSetOptions.MUTATION
            ).toObjectGRT(ctx, selections.type.kcls)
        }

    private fun SelectionSet<*>.getEngineSelectionSet() =
        (this as? SelectionSetImpl)?.engineSelectionSet
            ?: throw FrameworkException("Unexpected implementation of SelectionSet: $this")

    override fun <T : CompositeOutput> selectionsFor(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> =
        handleFrameworkErrors("selectionsFor") {
            SelectionSetImpl(
                type,
                engineExecutionContext.engineSelectionSetFactory.engineSelectionSet(
                    typeName = type.name,
                    selections,
                    normalizeVariablesForEngine(variables, engineExecutionContext.globalIDCodec)
                )
            )
        }

    override fun <T : NodeObject> nodeRef(
        ctx: InternalContext,
        globalID: GlobalID<T>
    ): T =
        handleFrameworkErrors("nodeRef(${globalID.type.name})") {
            val typeName = globalID.type.name
            val graphqlObjectType = ctx.schema.schema.getObjectType(typeName)
            val id = ctx.globalIDCodec.serialize(globalID.type.name, globalID.internalID)
            val nodeReference = engineExecutionContext.createNodeReference(id, graphqlObjectType)

            nodeReference.toObjectGRT(ctx, globalID.type.kcls)
        }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Arguments, T : Object> rootFieldRef(
        ctx: InternalContext,
        field: RootObjectField<*, T, A>,
        arguments: A
    ): T =
        handleFrameworkErrors("rootFieldRef(${field.type.name})") {
            val graphqlType = ctx.schema.schema.getObjectType(field.type.name)
            val argsMap = when (arguments) {
                is Arguments.NoArguments -> emptyMap()
                is InputLikeBase -> arguments.inputData
                else -> throw IllegalArgumentException(
                    "Expected Arguments class to be NoArguments or an instance of InputLikeBase, got $arguments"
                )
            }
            val rootFieldReference = engineExecutionContext.createRootFieldReference(
                field.pathFromQueryRoot,
                graphqlType,
                argsMap
            )
            (rootFieldReference as EngineObject).toObjectGRT(ctx, field.type.kcls as kotlin.reflect.KClass<Object>) as T
        }
}
