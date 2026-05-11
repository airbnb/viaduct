package viaduct.engine.runtime.mocks

import graphql.ExecutionResult
import graphql.Scalars
import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.CompleteSelectionSetOptions
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ExecutionInput
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class ContextMocks(
    myFullSchema: ViaductSchema? = null,
    myDispatcherRegistry: DispatcherRegistry? = null,
    myResolverInstrumentation: Instrumentation? = null,
    myFlagManager: FlagManager? = null,
    myEngine: Engine? = null,
    myGlobalIDCodec: GlobalIDCodec? = null,
    myEngineExecutionContextFactory: EngineExecutionContextFactory? = null,
    private val myEngineExecutionContext: EngineExecutionContext? = null,
    myBaseLocalContext: CompositeLocalContext? = null,
    myScopedSchema: ViaductSchema? = myFullSchema,
    private val myRequestContext: Any? = null,
) {
    val fullSchema: ViaductSchema = myFullSchema ?: ViaductSchema(
        GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject().name("Query")
                    .field(GraphQLFieldDefinition.newFieldDefinition().name("empty").type(Scalars.GraphQLInt))
                    .build()
            )
            .build()
    )
    val scopedSchema: ViaductSchema = myScopedSchema ?: fullSchema
    val viaductSchema: ViaductSchema = myFullSchema ?: fullSchema

    val dispatcherRegistry: DispatcherRegistry = myDispatcherRegistry ?: DispatcherRegistry.Empty
    val resolverInstrumentation: Instrumentation = myResolverInstrumentation ?: SimplePerformantInstrumentation()
    val flagManager: FlagManager = myFlagManager ?: MockFlagManager.Enabled
    val engine: Engine = myEngine ?: NoOpEngine
    val globalIDCodec: GlobalIDCodec = myGlobalIDCodec ?: GlobalIDCodecDefault

    val engineExecutionContext: EngineExecutionContext get() =
        when {
            myEngineExecutionContext != null -> myEngineExecutionContext
            else -> engineExecutionContextFactory.create(scopedSchema, myRequestContext)
        }

    val engineExecutionContextImpl: EngineExecutionContextImpl get() =
        engineExecutionContext as EngineExecutionContextImpl

    val engineExecutionContextFactory =
        myEngineExecutionContextFactory ?: EngineExecutionContextFactory(
            viaductSchema,
            dispatcherRegistry,
            resolverInstrumentation,
            flagManager,
            engine,
            globalIDCodec,
            meterRegistry = null,
        )

    val localContext: CompositeLocalContext by lazy {
        myBaseLocalContext?.addOrUpdate(engineExecutionContextImpl) ?: CompositeLocalContext.withContexts(engineExecutionContextImpl)
    }
}

private object NoOpEngine : Engine {
    override val schema: ViaductSchema get() = error("NoOpEngine: schema not configured")

    override suspend fun execute(executionInput: ExecutionInput): ExecutionResult = error("NoOpEngine: execute not configured")

    override suspend fun resolveSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData = error("NoOpEngine: resolveSelectionSet not configured")

    override suspend fun resolveSelectionSetSync(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: EngineSelectionSet,
        options: ResolveSelectionSetOptions,
    ): EngineObjectData.Sync = error("NoOpEngine: resolveSelectionSetSync not configured")

    override suspend fun completeSelectionSet(
        executionHandle: EngineExecutionContext.ExecutionHandle,
        selectionSet: RequiredSelectionSet,
        targetResult: ObjectEngineResult?,
        arguments: Map<String, Any?>,
        options: CompleteSelectionSetOptions,
    ): ExecutionResult = error("NoOpEngine: completeSelectionSet not configured")
}
