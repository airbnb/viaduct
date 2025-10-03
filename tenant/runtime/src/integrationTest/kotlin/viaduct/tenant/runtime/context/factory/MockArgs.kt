package viaduct.tenant.runtime.context.factory

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.internal.select.SelectionsLoader
import viaduct.api.mocks.MockExecutionContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockSelectionSetFactory
import viaduct.api.mocks.MockSelectionsLoader
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.mkRawSelectionSetFactory
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl

/**
 * An implementation of Args for testing within the `factory` module.
 * This provides safe defaults for all Args interfaces.
 */
@ExperimentalCoroutinesApi
class MockArgs(
    val schema: ViaductSchema = contextFactoryTestSchema,
    val reflectionLoader: ReflectionLoader = defaultReflectionLoader,
    val arguments: Map<String, Any?> = emptyMap(),
    val resolverId: String = "Query.empty",
    val globalID: String = "Baz:1",
    typeName: String = Query.Reflection.name,
    objectData: Map<String, Any?> = emptyMap(),
    selectionString: String? = null,
) {
    companion object {
        val contextFactoryTestSchema: ViaductSchema by lazy {
            ViaductSchema(
                UnExecutableSchemaGenerator.makeUnExecutableSchema(
                    SchemaParser().parse(ContextFactoryFeatureAppTest().sdl).apply {
                        DefaultSchemaProvider.addDefaults(this)
                    }
                )
            )
        }
        val defaultReflectionLoader: ReflectionLoader = ReflectionLoaderImpl { name -> Class.forName("viaduct.tenant.runtime.context.factory.$name").kotlin }
        val rawSelectionsFactory = mkRawSelectionSetFactory(contextFactoryTestSchema)
        val selectionSetFactory: SelectionSetFactory = MockSelectionSetFactory()
    }

    val objectValue: EngineObjectData = mkEngineObjectData(schema.schema.getObjectType(typeName), objectData)

    val queryValue: EngineObjectData = mkEngineObjectData(schema.schema.getObjectType(Query.Reflection.name), emptyMap())

    val selections: RawSelectionSet? = selectionString?.let {
        rawSelectionsFactory.rawSelectionSet(typeName, it, emptyMap())
    }
    val internalContext: InternalContext = MockInternalContext(schema, reflectionLoader = reflectionLoader)

    val selectionsLoaderFactory: SelectionsLoader.Factory =
        MockSelectionsLoader.Factory(
            Query.Builder(MockExecutionContext(internalContext)).build(),
            Mutation.Builder(MockExecutionContext(internalContext)).build()
        )

    val engineExecutionContext: EngineExecutionContext = mockk {
        every {
            fullSchema
        } returns contextFactoryTestSchema
    }

    fun getFieldArgs() =
        FieldArgs(
            internalContext = internalContext,
            arguments = arguments,
            objectValue = objectValue,
            queryValue = queryValue,
            resolverId = resolverId,
            selectionSetFactory = selectionSetFactory,
            selections = selections,
            selectionsLoaderFactory = selectionsLoaderFactory,
            engineExecutionContext = engineExecutionContext
        )

    fun getNodeArgs() =
        NodeArgs(
            internalContext = internalContext,
            selections = selections,
            globalID = globalID,
            selectionSetFactory = selectionSetFactory,
            resolverId = resolverId,
            selectionsLoaderFactory = selectionsLoaderFactory,
            engineExecutionContext = engineExecutionContext,
        )

    fun getObjectArgs() =
        ObjectArgs(
            internalContext = internalContext,
            objectValue = objectValue,
        )

    fun getExecutionContextArgs() =
        ResolverExecutionContextArgs(
            internalContext = internalContext,
            selectionSetFactory = selectionSetFactory,
            selectionsLoaderFactory = selectionsLoaderFactory,
            resolverId = resolverId,
            engineExecutionContext = engineExecutionContext,
        )

    fun getSelectionSetArgs() =
        SelectionSetArgs(
            internalContext = internalContext,
            selections = selections,
        )

    fun getSelectionsLoaderArgs() =
        SelectionsLoaderArgs(
            resolverId = resolverId,
            selectionsLoaderFactory = selectionsLoaderFactory,
        )

    fun getArgumentsArgs() =
        ArgumentsArgs(
            internalContext = internalContext,
            arguments = arguments,
        )
}
