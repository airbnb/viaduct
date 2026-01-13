package viaduct.engine.runtime.mocks

import graphql.execution.instrumentation.Instrumentation
import graphql.execution.instrumentation.SimplePerformantInstrumentation
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.engine.api.Engine
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.FragmentLoader
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.EngineExecutionContextFactory
import viaduct.engine.runtime.EngineExecutionContextImpl
import viaduct.engine.runtime.ViaductFragmentLoader
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.fragment.ViaductExecutableFragmentParser
import viaduct.service.api.spi.FlagManager
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.api.spi.mocks.MockFlagManager

@OptIn(ExperimentalCoroutinesApi::class)
class ContextMocks(
    myFullSchema: ViaductSchema? = null,
    myDispatcherRegistry: DispatcherRegistry? = null,
    myFragmentLoader: FragmentLoader? = null,
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
    val fullSchema: ViaductSchema = myFullSchema ?: mockk<ViaductSchema>()
    val scopedSchema: ViaductSchema = myScopedSchema ?: mockk<ViaductSchema>()
    val viaductSchema: ViaductSchema = myFullSchema ?: fullSchema

    val dispatcherRegistry: DispatcherRegistry = myDispatcherRegistry ?: DispatcherRegistry.Empty
    val fragmentLoader: FragmentLoader = myFragmentLoader ?: ViaductFragmentLoader(ViaductExecutableFragmentParser())
    val resolverInstrumentation: Instrumentation = myResolverInstrumentation ?: SimplePerformantInstrumentation()
    val flagManager: FlagManager = myFlagManager ?: MockFlagManager.Enabled
    val engine: Engine = myEngine ?: mockk(relaxed = true)
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
            fragmentLoader,
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
