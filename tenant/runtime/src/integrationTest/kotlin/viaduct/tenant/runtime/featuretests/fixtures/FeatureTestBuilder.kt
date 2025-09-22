package viaduct.tenant.runtime.featuretests.fixtures

import graphql.execution.instrumentation.Instrumentation
import io.micrometer.core.instrument.MeterRegistry
import kotlin.reflect.KClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.api.FieldValue
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.ObjectBase
import viaduct.api.reflect.Type
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.NodeObject
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.select.SelectionsParser
import viaduct.service.api.spi.Flags
import viaduct.service.api.spi.mocks.MockFlagManager
import viaduct.service.runtime.StandardViaduct
import viaduct.service.runtime.ViaductSchemaRegistryBuilder
import viaduct.tenant.runtime.bootstrap.ViaductTenantAPIBootstrapper
import viaduct.tenant.runtime.context.factory.ArgumentsArgs
import viaduct.tenant.runtime.context.factory.ArgumentsFactory
import viaduct.tenant.runtime.context.factory.Factory
import viaduct.tenant.runtime.context.factory.FieldExecutionContextMetaFactory
import viaduct.tenant.runtime.context.factory.MutationFieldExecutionContextMetaFactory
import viaduct.tenant.runtime.context.factory.NodeExecutionContextMetaFactory
import viaduct.tenant.runtime.context.factory.NodeResolverContextFactory
import viaduct.tenant.runtime.context.factory.ObjectFactory
import viaduct.tenant.runtime.context.factory.ResolverContextFactory
import viaduct.tenant.runtime.context.factory.SelectionSetFactory as SelectionSetContextFactory
import viaduct.tenant.runtime.internal.VariablesProviderInfo

/**
 * Configuration for [FeatureTest].
 * Provides resolvers, schema, and a test Guice module for a test Viaduct Modern engine.
 */
@ExperimentalCoroutinesApi
@Suppress("ktlint:standard:indent")
class FeatureTestBuilder {
    companion object {
        internal const val SCHEMA_ID = "scopedSchema"
    }

    lateinit var sdl: String
    private var scopedSchemaSdl: String? = null
    private var grtPackage: String? = null
    private val packageToResolverBases = mutableMapOf<String, Set<Class<*>>>()
    private val resolverStubs = mutableMapOf<Coordinate, FieldUnbatchedResolverStub<*>>()
    private val nodeUnbatchedResolverStubs = mutableMapOf<String, NodeUnbatchedResolverStub>()
    private val nodeBatchResolverStubs = mutableMapOf<String, NodeBatchResolverStub>()
    private val fieldCheckerStubs = mutableMapOf<Coordinate, CheckerExecutorStub>()
    private val typeCheckerStubs = mutableMapOf<String, CheckerExecutorStub>()
    private var instrumentation: Instrumentation? = null
    private var meterRegistry: MeterRegistry? = null

    /**
     * Configure a resolver that binds the provided [resolveFn] to the provided schema [coordinate].
     *
     * Types are inferred from the provided [Ctx] type parameter.
     * For a simpler API, see the overloaded [resolver] that operates on an [UntypedFieldContext].
     */
    inline fun <
        reified Ctx : FieldExecutionContext<T, Q, A, O>,
        reified T : Object,
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
            Ctx::class,
            T::class,
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

    fun <
        Ctx : FieldExecutionContext<T, Q, A, O>,
        T : Object,
        Q : Query,
        A : Arguments,
        O : CompositeOutput
        > resolver(
        ctxCls: KClass<Ctx>,
        objCls: KClass<T>,
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
        val objFactory =
            ObjectFactory.forClass(
                objCls
                    .takeIf { it.supertypes.any { it.classifier == ObjectBase::class } }
                    ?: ObjectStub::class
            )

        val queryFactory =
            ObjectFactory.forClass(
                queryCls
                    .takeIf { it.supertypes.any { it.classifier == Query::class } }
                    ?: QueryStub::class
            )

        val argsFactory = ArgumentsFactory.ifClass(argumentsCls)
            ?: Factory { args: ArgumentsArgs -> ArgumentsStub(args.arguments) }

        val ctxFactory = ResolverContextFactory.ifContext(
            ctxCls,
            MutationFieldExecutionContextMetaFactory.ifMutation(
                ctxCls,
                FieldExecutionContextMetaFactory.create(
                    objFactory,
                    queryFactory,
                    argsFactory,
                    SelectionSetContextFactory.forClass(outputCls)
                )
            )
        )

        val objectSelections = objectValueFragment?.let { SelectionsParser.parse(coordinate.first, it) }
        val querySelections = queryValueFragment?.let {
            checkNotNull(this.sdl) {
                "Cannot set queryValueFragment before setting sdl"
            }
            SelectionsParser.parse("Query", it)
        }
        resolverStubs[coordinate] =
            FieldUnbatchedResolverStub<Ctx>(
                objectSelections = objectSelections,
                querySelections = querySelections,
                resolverFactory = ctxFactory,
                argumentsFactory = argsFactory,
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
        resolver<UntypedFieldContext, ObjectStub, QueryStub, ArgumentsStub, CompositeStub>(
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
        resolver<UntypedMutationFieldContext, ObjectStub, QueryStub, ArgumentsStub, CompositeStub>(
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
        noinline resolveFn: suspend (ctx: Ctx) -> NodeObject
    ): FeatureTestBuilder = nodeResolver(Ctx::class, T::class, typeName, resolveFn)

    @Suppress("UNUSED_PARAMETER")
    fun <Ctx : NodeExecutionContext<T>, T : NodeObject> nodeResolver(
        ctxCls: KClass<Ctx>,
        nodeCls: KClass<T>,
        typeName: String,
        resolveFn: suspend (ctx: Ctx) -> NodeObject
    ): FeatureTestBuilder {
        val resolver = NodeUnbatchedResolverStub(
            NodeResolverContextFactory.ifContext(
                ctxCls,
                NodeExecutionContextMetaFactory.create(
                    selections = SelectionSetContextFactory.forTypeName(typeName)
                )
            )
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
        resolveFn: suspend (ctx: UntypedNodeContext) -> NodeObject
    ): FeatureTestBuilder = nodeResolver<UntypedNodeContext, NodeObject>(typeName, resolveFn)

    inline fun <reified Ctx : NodeExecutionContext<T>, reified T : NodeObject> nodeBatchResolver(
        typeName: String,
        noinline batchResolveFn: suspend (ctxs: List<Ctx>) -> List<FieldValue<T>>
    ): FeatureTestBuilder = nodeBatchResolver(Ctx::class, typeName, batchResolveFn)

    @Suppress("UNCHECKED_CAST")
    fun <Ctx : NodeExecutionContext<T>, T : NodeObject> nodeBatchResolver(
        ctxCls: KClass<Ctx>,
        typeName: String,
        batchResolveFn: suspend (ctxs: List<Ctx>) -> List<FieldValue<NodeObject>>
    ): FeatureTestBuilder {
        val resolver = NodeBatchResolverStub(
            NodeResolverContextFactory.ifContext(
                ctxCls,
                NodeExecutionContextMetaFactory.create(
                    selections = SelectionSetContextFactory.forTypeName(typeName)
                )
            )
        ) { ctxs ->
            batchResolveFn(ctxs as List<Ctx>)
        }
        nodeBatchResolverStubs[typeName] = resolver
        return this
    }

    @JvmName("nodeBatchResolver2")
    fun nodeBatchResolver(
        typeName: String,
        resolveFn: suspend (ctxs: List<UntypedNodeContext>) -> List<FieldValue<NodeObject>>
    ): FeatureTestBuilder = nodeBatchResolver<UntypedNodeContext, NodeObject>(typeName, resolveFn)

    /**
     * Configure a field checker for the field with the given [coordinate].
     */
    fun fieldChecker(
        coordinate: Coordinate,
        executeFn: suspend (arguments: Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit,
        vararg requiredSelections: Triple<String, String, String>
    ): FeatureTestBuilder {
        fieldCheckerStubs[coordinate] = checker(executeFn, *requiredSelections)
        return this
    }

    /**
     * Configure a type checker for the specified type.
     */
    fun typeChecker(
        typeName: String,
        executeFn: suspend (arguments: Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit,
        vararg requiredSelections: Triple<String, String, String>
    ): FeatureTestBuilder {
        typeCheckerStubs[typeName] = checker(executeFn, *requiredSelections)
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
        vararg requiredSelections: Triple<String, String, String>
    ): CheckerExecutorStub {
        val rssMap = requiredSelections.map { (checkerKey, typeName, selectionsString) ->
            Pair(
                checkerKey,
                RequiredSelectionSet(
                    SelectionsParser.parse(typeName, selectionsString),
                    emptyList()
                )
            )
        }.toMap()
        return CheckerExecutorStub(rssMap, executeFn)
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

    /**
     * Registers the full schema for the test Viaduct Modern engine.
     */
    fun sdl(schema: String): FeatureTestBuilder {
        sdl = schema
        return this
    }

    /**
     * Registers the scoped schema for the test Viaduct Modern engine.
     */
    fun scopedSchemaSdl(schema: String): FeatureTestBuilder {
        scopedSchemaSdl = schema
        return this
    }

    /** set the grtPackage to the provided value */
    fun grtPackage(grtPackage: String): FeatureTestBuilder =
        this.also {
            this.grtPackage = grtPackage
        }

    /** set the grtPackage to a value derived from the provided KClass */
    fun grtPackage(cls: KClass<*>): FeatureTestBuilder =
        this.also {
            cls.qualifiedName?.also {
                grtPackage(it.split(".").dropLast(1).joinToString("."))
            }
        }

    /** set the grtPackage to a value derived from the provided Type */
    fun grtPackage(type: Type<*>): FeatureTestBuilder = grtPackage(type.kcls)

    /** chain the instrumentation with the default instrumentations */
    fun instrumentation(instrumentation: Instrumentation): FeatureTestBuilder =
        this.also {
            this.instrumentation = instrumentation
    }

    fun meterRegistry(meterRegistry: MeterRegistry) =
        this.also {
            this.meterRegistry = meterRegistry
        }

    fun build(): FeatureTest {
        val tenantPackageFinder = TestTenantPackageFinder(packageToResolverBases)

        val featureTestTenantAPIBootstrapperBuilder = FeatureTestTenantAPIBootstrapperBuilder(
            resolverStubs,
            nodeUnbatchedResolverStubs,
            nodeBatchResolverStubs,
        )

        @Suppress("DEPRECATION")
        val viaductTenantAPIBootstrapperBuilder = ViaductTenantAPIBootstrapper.Builder().tenantPackageFinder(tenantPackageFinder)
        val builders = listOf(viaductTenantAPIBootstrapperBuilder, featureTestTenantAPIBootstrapperBuilder)
        val viaductSchemaRegistryBuilder = ViaductSchemaRegistryBuilder()
            .withFullSchemaFromSdl(sdl)
            .apply {
                scopedSchemaSdl?.let { registerSchemaFromSdl(SCHEMA_ID, it) } ?: registerFullSchema(SCHEMA_ID)
            }

        val standardViaduct = StandardViaduct.Builder()
            .withTenantAPIBootstrapperBuilders(builders)
            .withFlagManager(
                MockFlagManager.mk(
                    Flags.EXECUTE_ACCESS_CHECKS_IN_MODERN_EXECUTION_STRATEGY
                )
            )
            .withSchemaRegistryBuilder(viaductSchemaRegistryBuilder)
            .withCheckerExecutorFactory(
                object : CheckerExecutorFactory {
                    override fun checkerExecutorForField(
                        typeName: String,
                        fieldName: String
                    ): CheckerExecutor? = fieldCheckerStubs[typeName to fieldName]

                    override fun checkerExecutorForType(typeName: String): CheckerExecutor? = typeCheckerStubs[typeName]
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

        return FeatureTest(standardViaduct.build())
    }
}
