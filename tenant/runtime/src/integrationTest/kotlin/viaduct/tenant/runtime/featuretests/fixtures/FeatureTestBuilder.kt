package viaduct.tenant.runtime.featuretests.fixtures

import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
import kotlin.reflect.KClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.slf4j.LoggerFactory
import viaduct.api.FieldValue
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.ReflectionLoader
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Query
import viaduct.engine.SchemaFactory
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.CheckerMetadata
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.select.SelectionsParser
import viaduct.service.api.spi.ErrorReporter
import viaduct.service.api.spi.FlagManager.Flags
import viaduct.service.api.spi.GlobalIDCodec
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.service.runtime.SchemaConfiguration
import viaduct.service.runtime.StandardViaduct
import viaduct.tenant.runtime.context.factory.NodeExecutionContextFactory
import viaduct.tenant.runtime.internal.ReflectionLoaderImpl
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/**
 * Configuration for [FeatureTest].
 *
 * Usage:
 *
 * ```kotlin
 *    FeatureTestBuilder(<schema>)
 *      // configure a resolver class for a schema field
 *      .resolver(Query::class, FooField::class, FooFieldResolver::class)
 *      // or configure a resolver function that uses GRTs
 *      .resolver("Query" to "bar") { Bar.newBuilder(it).value(2).build() }
 *      // or configure a simple resolver function that does not read or write GRTs
 *      .resolver("Query" to "baz") { mapOf("value" to 2) }
 *      .build()
 * ```
 *
 * Creates a [FeatureTest] whose embedded [StandardViaduct] instance is configured with
 * the given schema and resolvers.  This instance log data-fetching exceptions using
 * SLF4J at info level to make their full stack trace available for debugging tests.
 */
@ExperimentalCoroutinesApi
@Suppress("ktlint:standard:indent")
class FeatureTestBuilder(
    private val sdl: String,
    private val useFakeGRTs: Boolean = false,
    private val instrumentation: Instrumentation? = null,
    private val meterRegistry: MeterRegistry? = null,
    private val resolverInstrumentation: ViaductResolverInstrumentation? = null
) {
    private val reflectionLoaderForFeatureTestBootstrapper: ReflectionLoader = run {
        if (useFakeGRTs) {
            FakeReflectionLoader(SchemaFactory().fromSdl(sdl))
        } else {
            ReflectionLoaderImpl { name -> Class.forName("viaduct.tenant.runtime.featuretests.fixtures.$name").kotlin }
        }
    }

    private val globalIDCodec: GlobalIDCodec = GlobalIDCodecDefault

    // These are all mutated by the resolver-setting functions below
    private val packageToResolverBases = mutableMapOf<String, Set<Class<*>>>()
    private val resolverStubs = mutableMapOf<Coordinate, FieldUnbatchedResolverStub<*>>()
    private val nodeUnbatchedResolverStubs = mutableMapOf<String, NodeUnbatchedResolverStub>()
    private val nodeBatchResolverStubs = mutableMapOf<String, NodeBatchResolverStub>()
    private val fieldCheckerStubs = mutableMapOf<Coordinate, CheckerExecutorStub>()
    private val typeCheckerStubs = mutableMapOf<String, CheckerExecutorStub>()

    /**
     * Configure a resolver that binds the provided [resolveFn] to the provided schema [coordinate].
     *
     * Types are inferred from the provided [Ctx] type parameter.
     * For a simpler API, see the overloaded [resolver] that operates on an [UntypedFieldContext].
     */
    inline fun <
        reified Ctx : BaseFieldExecutionContext<Q, A, O>,
        reified Q : Query,
        reified A : Arguments,
        reified O : CompositeOutput
        > resolver(
        coordinate: Coordinate,
        noinline resolveFn: suspend (ctx: Ctx) -> Any?,
        objectValueFragment: String? = null,
        queryValueFragment: String? = null,
        variables: List<SelectionSetVariable> = emptyList(),
        variablesProvider: VariablesProviderInfo? = null,
        resolverName: String? = null
    ): FeatureTestBuilder =
        resolver(
            Q::class,
            A::class,
            O::class,
            coordinate,
            resolveFn,
            objectValueFragment,
            queryValueFragment,
            variables,
            variablesProvider,
            resolverName
        )

    @Suppress("UNUSED", "UNUSED_PARAMETER", "UNUSED_VARIABLE")
    fun <
        Ctx : BaseFieldExecutionContext<Q, A, O>,
        Q : Query,
        A : Arguments,
        O : CompositeOutput
        > resolver(
        queryCls: KClass<Q>,
        argumentsCls: KClass<A>,
        outputCls: KClass<O>,
        coordinate: Coordinate,
        resolveFn: suspend (ctx: Ctx) -> Any?,
        objectValueFragment: String? = null,
        queryValueFragment: String? = null,
        variables: List<SelectionSetVariable> = emptyList(),
        variablesProvider: VariablesProviderInfo? = null,
        resolverName: String? = null
    ): FeatureTestBuilder {
        val objectSelections = objectValueFragment?.let { SelectionsParser.parse(coordinate.first, it) }
        val querySelections = queryValueFragment?.let {
            SelectionsParser.parse("Query", it)
        }
        resolverStubs[coordinate] =
            FieldUnbatchedResolverStub<Ctx>(
                objectSelections = objectSelections,
                querySelections = querySelections,
                coord = coordinate,
                variables = variables,
                resolveFn = { ctx ->
                    @Suppress("UNCHECKED_CAST")
                    resolveFn(ctx as Ctx)
                },
                variablesProvider = variablesProvider,
                resolverName = resolverName
            )

        return this
    }

    /**
     * Configure a simple query field resolver at the provided [coordinate].
     * The provided [resolveFn] may use the provided [UntypedFieldContext] to write GRT data,
     * but it may only read untyped data.
     *
     * For a more powerful API that allows reading GRT data, see the overloaded [resolver] method.
     */
    fun resolver(
        coordinate: Coordinate,
        resolverName: String? = null,
        resolveFn: suspend (ctx: UntypedFieldContext) -> Any?,
    ): FeatureTestBuilder =
        resolver<UntypedFieldContext, viaduct.api.types.Query, viaduct.api.types.Arguments, CompositeOutput>(
            coordinate = coordinate,
            resolveFn = resolveFn,
            resolverName = resolverName
        )

    /**
     * Configure a simple mutation field resolver at the provided [coordinate].
     * The provided [resolveFn] may use the provided [UntypedFieldContext] to write GRT data,
     * but it may only read untyped data.
     *
     * For a more powerful API that allows reading GRT data, see the overloaded [resolver] method.
     */
    fun mutation(
        coordinate: Coordinate,
        resolverName: String? = null,
        resolveFn: suspend (ctx: UntypedMutationFieldContext) -> Any?,
    ): FeatureTestBuilder =
        resolver<UntypedMutationFieldContext, viaduct.api.types.Query, viaduct.api.types.Arguments, CompositeOutput>(
            coordinate = coordinate,
            resolveFn = resolveFn,
            resolverName = resolverName
        )

    /**
     * Registers a GraphQL field resolver with no arguments to be run on a test Viaduct Modern engine.
     */
    fun resolver(
        grt: KClass<*>,
        base: KClass<*>,
        implementation: KClass<*>
    ): FeatureTestBuilder {
        return resolver(grt, base, Arguments.NoArguments::class, implementation)
    }

    inline fun <reified Ctx : NodeExecutionContext<T>, reified T : NodeObject> nodeResolver(
        typeName: String,
        resolverName: String? = null,
        noinline resolveFn: suspend (ctx: Ctx) -> NodeObject
    ): FeatureTestBuilder = nodeResolver(Ctx::class, T::class, typeName, resolverName, resolveFn)

    @Suppress("UNUSED_PARAMETER")
    fun <Ctx : NodeExecutionContext<T>, T : NodeObject> nodeResolver(
        ctxCls: KClass<Ctx>,
        nodeCls: KClass<T>,
        typeName: String,
        resolverName: String? = null,
        resolveFn: suspend (ctx: Ctx) -> NodeObject
    ): FeatureTestBuilder {
        @Suppress("UNCHECKED_CAST")
        val resultType = reflectionLoaderForFeatureTestBootstrapper.reflectionFor(typeName) as Type<NodeObject>

        val resolver = NodeUnbatchedResolverStub(
            NodeExecutionContextFactory(
                NodeExecutionContextFactory.FakeResolverBase::class.java,
                globalIDCodec,
                reflectionLoaderForFeatureTestBootstrapper,
                resultType
            ),
            resolverName,
        ) { ctx ->
            @Suppress("UNCHECKED_CAST")
            resolveFn(ctx as Ctx)
        }
        nodeUnbatchedResolverStubs[typeName] = resolver
        return this
    }

    @JvmName("nodeResolver2")
    fun nodeResolver(
        typeName: String,
        resolverName: String? = null,
        resolveFn: suspend (ctx: UntypedNodeContext) -> NodeObject
    ): FeatureTestBuilder = nodeResolver<UntypedNodeContext, NodeObject>(typeName, resolverName, resolveFn)

    inline fun <reified Ctx : NodeExecutionContext<T>, reified T : NodeObject> nodeBatchResolver(
        typeName: String,
        resolverName: String? = null,
        noinline batchResolveFn: suspend (ctxs: List<Ctx>) -> List<FieldValue<T>>
    ): FeatureTestBuilder = nodeBatchResolver(Ctx::class, typeName, resolverName, batchResolveFn)

    @Suppress("UNCHECKED_CAST")
    fun <Ctx : NodeExecutionContext<T>, T : NodeObject> nodeBatchResolver(
        @Suppress("UNUSED_PARAMETER") ctxCls: KClass<Ctx>,
        typeName: String,
        resolverName: String? = null,
        batchResolveFn: suspend (ctxs: List<Ctx>) -> List<FieldValue<NodeObject>>
    ): FeatureTestBuilder {
        val resultType = reflectionLoaderForFeatureTestBootstrapper.reflectionFor(typeName) as Type<NodeObject>

        val resolver = NodeBatchResolverStub(
            NodeExecutionContextFactory(
                NodeExecutionContextFactory.FakeResolverBase::class.java,
                globalIDCodec,
                reflectionLoaderForFeatureTestBootstrapper,
                resultType,
            ),
            resolverName,
        ) { ctxs ->
            batchResolveFn(ctxs as List<Ctx>)
        }
        nodeBatchResolverStubs[typeName] = resolver
        return this
    }

    @JvmName("nodeBatchResolver2")
    fun nodeBatchResolver(
        typeName: String,
        resolverName: String? = null,
        resolveFn: suspend (ctxs: List<UntypedNodeContext>) -> List<FieldValue<NodeObject>>
    ): FeatureTestBuilder = nodeBatchResolver<UntypedNodeContext, NodeObject>(typeName, resolverName, resolveFn)

    /**
     * Configure a field checker for the field with the given [coordinate].
     */
    fun fieldChecker(
        coordinate: Coordinate,
        checkerName: String? = null,
        executeFn: suspend (arguments: Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit,
        vararg requiredSelections: Triple<String, String, String>
    ): FeatureTestBuilder {
        val checkerMetadata = CheckerMetadata(
            checkerName = checkerName ?: "default-field-checker",
            typeName = coordinate.first,
            fieldName = coordinate.second,
        )
        fieldCheckerStubs[coordinate] = checker(executeFn, checkerMetadata, *requiredSelections)
        return this
    }

    /**
     * Configure a type checker for the specified type.
     */
    fun typeChecker(
        typeName: String,
        checkerName: String? = null,
        executeFn: suspend (arguments: Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit,
        vararg requiredSelections: Triple<String, String, String>
    ): FeatureTestBuilder {
        val checkerMetadata = CheckerMetadata(
            checkerName = checkerName ?: "default-type-checker",
            typeName = typeName,
        )
        typeCheckerStubs[typeName] = checker(executeFn, checkerMetadata, *requiredSelections)
        return this
    }

    /**
     * Create a [CheckerExecutor] from the provided [executeFn] lambda
     *
     * @param requiredSelections a `Triple(checkerKey, graphQLTypeName, selectionsString)` describing a
     * required selection set for this checker
     */
    private fun checker(
        executeFn: suspend (arguments: Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit,
        checkerMetadata: CheckerMetadata,
        vararg requiredSelections: Triple<String, String, String>,
    ): CheckerExecutorStub {
        val rssMap = requiredSelections.map { (checkerKey, typeName, selectionsString) ->
            Pair(
                checkerKey,
                RequiredSelectionSet(
                    SelectionsParser.parse(typeName, selectionsString),
                    emptyList(),
                    forChecker = true,
                )
            )
        }.toMap()
        return CheckerExecutorStub(rssMap, executeFn, checkerMetadata)
    }

    /**
     * Registers a GraphQL field resolver with arguments to be run on a test Viaduct Modern engine.
     */
    @Suppress("UNUSED_PARAMETER")
    fun resolver(
        grt: KClass<*>,
        base: KClass<*>,
        arguments: KClass<*>,
        implementation: KClass<*>
    ): FeatureTestBuilder {
        val packageName = base.java.`package`.name
        packageToResolverBases[packageName] = packageToResolverBases[packageName]?.let {
            it + base.java
        } ?: setOf(base.java)
        return this
    }

    fun build(): FeatureTest {
        TestTenantPackageFinder(packageToResolverBases)

        val featureTestTenantAPIBootstrapperBuilder = FeatureTestTenantAPIBootstrapperBuilder(
            resolverStubs,
            nodeUnbatchedResolverStubs,
            nodeBatchResolverStubs,
            reflectionLoaderForFeatureTestBootstrapper,
            globalIDCodec,
        )

        val builders = listOf(featureTestTenantAPIBootstrapperBuilder)
        val schemaConfiguration = SchemaConfiguration.fromSdl(
            sdl = sdl
        )

        @Suppress("DEPRECATION")
        val standardViaduct = StandardViaduct.Builder()
            .withTenantAPIBootstrapperBuilders(builders)
            .withFlagManager(
                MockFlagManager.mk(
                    Flags.EXECUTE_ACCESS_CHECKS
                )
            )
            .allowSubscriptions(true)
            .withSchemaConfiguration(schemaConfiguration)
            .withCheckerExecutorFactory(
                object : CheckerExecutorFactory {
                    override fun checkerExecutorForField(
                        schema: ViaductSchema,
                        typeName: String,
                        fieldName: String
                    ): CheckerExecutor? = fieldCheckerStubs[typeName to fieldName]

                    override fun checkerExecutorForType(
                        schema: ViaductSchema,
                        typeName: String
                    ): CheckerExecutor? = typeCheckerStubs[typeName]
                }
            )

        instrumentation?.let {
            @Suppress("DEPRECATION")
            standardViaduct.withInstrumentation(
                it,
                chainInstrumentationWithDefaults = true
            )
        }

        meterRegistry?.let {
            standardViaduct.withMeterRegistry(it)
        }

        resolverInstrumentation?.let {
            standardViaduct.withResolverInstrumentation(it)
        }

        // Log data-fetcher exceptions for diagnostic purposes
        standardViaduct.withResolverErrorReporter(
            ErrorReporter { exception, errorMessage, metadata ->
                logger.info("Resolver error: $errorMessage", exception)
                logger.info("Error metadata: $metadata")
            }
        )

        return FeatureTest(standardViaduct.build())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(FeatureTestBuilder::class.java)
    }
}
