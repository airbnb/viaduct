@file:Suppress("ForbiddenImport")

package viaduct.engine.api.mocks

import graphql.execution.AsyncExecutionStrategy
import graphql.execution.ExecutionStrategy
import graphql.execution.SimpleDataFetcherExceptionHandler
import graphql.execution.instrumentation.ChainedInstrumentation
import graphql.execution.instrumentation.Instrumentation
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLType
import graphql.schema.idl.RuntimeWiring
import graphql.schema.idl.SchemaGenerator
import graphql.schema.idl.SchemaParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import viaduct.dataloader.mocks.MockNextTickDispatcher
import viaduct.engine.ViaductSchemaLoadException
import viaduct.engine.ViaductWiringFactory
import viaduct.engine.api.CheckerExecutor
import viaduct.engine.api.CheckerExecutorFactory
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FieldResolverDispatcher
import viaduct.engine.api.FieldResolverDispatcherRegistry
import viaduct.engine.api.FieldResolverExecutor
import viaduct.engine.api.NodeResolverExecutor
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.ResolverMetadata
import viaduct.engine.api.TenantAPIBootstrapper
import viaduct.engine.api.TenantModuleBootstrapper
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.coroutines.CoroutineInterop
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.CheckerDispatcherImpl
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcherImpl
import viaduct.engine.runtime.execution.DefaultCoroutineInterop
import viaduct.engine.runtime.mocks.ContextMocks
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl
import viaduct.engine.runtime.select.RawSelectionSetImpl
import viaduct.graphql.utils.DefaultSchemaProvider

typealias CheckerFn = suspend (arguments: Map<String, Any?>, objectDataMap: Map<String, EngineObjectData>) -> Unit
typealias NodeBatchResolverFn = suspend (selectors: List<NodeResolverExecutor.Selector>, context: EngineExecutionContext) -> Map<NodeResolverExecutor.Selector, Result<EngineObjectData>>
typealias NodeUnbatchedResolverFn = suspend (id: String, selections: RawSelectionSet?, context: EngineExecutionContext) -> EngineObjectData
typealias FieldUnbatchedResolverFn = suspend (
    arguments: Map<String, Any?>,
    objectValue: EngineObjectData,
    queryValue: EngineObjectData,
    selections: RawSelectionSet?,
    context: EngineExecutionContext
) -> Any?

typealias FieldBatchResolverFn = suspend (selectors: List<FieldResolverExecutor.Selector>, context: EngineExecutionContext) -> Map<FieldResolverExecutor.Selector, Result<Any?>>
typealias VariablesResolverFn = suspend (ctx: VariablesResolver.ResolveCtx) -> Map<String, Any?>

fun mkCoroutineInterop(): CoroutineInterop = DefaultCoroutineInterop

fun mkExecutionStrategy(): ExecutionStrategy = AsyncExecutionStrategy(SimpleDataFetcherExceptionHandler())

fun mkInstrumentation(): Instrumentation = ChainedInstrumentation(listOf<Instrumentation>())

fun RawSelectionSet.variables() = (this as RawSelectionSetImpl).ctx.variables

fun mkRawSelectionSet(
    parsedSelections: ParsedSelections,
    viaductSchema: ViaductSchema,
    variables: Map<String, Any?>
): RawSelectionSet =
    RawSelectionSetImpl.create(
        parsedSelections,
        variables,
        viaductSchema
    )

fun mkRawSelectionSetFactory(viaductSchema: ViaductSchema) = RawSelectionSetFactoryImpl(viaductSchema)

fun mkRSS(
    typeName: String,
    selectionString: String,
    variableProviders: List<VariablesResolver> = emptyList(),
    forChecker: Boolean = false,
    attribution: ExecutionAttribution = ExecutionAttribution.DEFAULT
) = RequiredSelectionSet(SelectionsParser.parse(typeName, selectionString), variableProviders, forChecker, attribution)

class MockVariablesResolver(
    vararg names: String,
    override val requiredSelectionSet: RequiredSelectionSet? = null,
    val resolveFn: VariablesResolverFn,
) : VariablesResolver {
    override val variableNames: Set<String> = names.toSet()

    override suspend fun resolve(ctx: VariablesResolver.ResolveCtx): Map<String, Any?> = resolveFn(ctx)
}

/**
 * Create a [ViaductSchema] with mock wiring, which allows for schema parsing and validation.
 * This is useful for testing local changes that are unnecessary for a full engine execution,
 * e.g., unit tests.
 *
 * @param sdl The SDL string to parse and create the schema.
 */
fun mkSchema(sdl: String): ViaductSchema {
    val tdr = SchemaParser().parse(sdl).apply {
        DefaultSchemaProvider.addDefaults(this)
    }
    return ViaductSchema(SchemaGenerator().makeExecutableSchema(tdr, RuntimeWiring.MOCKED_WIRING))
}

/**
 * Create a [ViaductSchema] with actual wiring, which allows for real execution.
 * This is useful for testing the actual engine behaviors, e.g., engine feature test.
 *
 * @param sdl The SDL string to parse and create the schema.
 */
fun mkSchemaWithWiring(sdl: String): ViaductSchema {
    val tdr = SchemaParser().parse(sdl)
    try {
        DefaultSchemaProvider.addDefaults(tdr)
    } catch (e: Exception) {
        throw ViaductSchemaLoadException(
            "Failed to add default schema components.",
            e
        )
    }
    val actualWiringFactory = ViaductWiringFactory(DefaultCoroutineInterop)
    val wiring = RuntimeWiring.newRuntimeWiring().wiringFactory(actualWiringFactory).apply {
        DefaultSchemaProvider.defaultScalars().forEach { scalar(it) }
    }.build()

    // Let SchemaProblem and other GraphQL validation errors pass through
    return ViaductSchema(SchemaGenerator().makeExecutableSchema(tdr, wiring))
}

object MockSchema {
    val minimal: ViaductSchema = mkSchema("extend type Query { empty: Int }")

    fun mk(sdl: String) = mkSchema(sdl)
}

fun mkDispatcherRegistry(
    fieldResolverExecutors: Map<Coordinate, FieldResolverExecutor> = emptyMap(),
    nodeResolverExecutors: Map<String, NodeResolverExecutor> = emptyMap(),
    fieldCheckerExecutors: Map<Coordinate, CheckerExecutor> = emptyMap(),
    typeCheckerExecutors: Map<String, CheckerExecutor> = emptyMap(),
): DispatcherRegistry {
    return DispatcherRegistry(
        fieldResolverDispatchers = fieldResolverExecutors.map { (k, v) -> k to FieldResolverDispatcherImpl(v) }.toMap(),
        nodeResolverDispatchers = nodeResolverExecutors.map { (k, v) -> k to NodeResolverDispatcherImpl(v) }.toMap(),
        fieldCheckerDispatchers = fieldCheckerExecutors.map { (k, v) -> k to CheckerDispatcherImpl(v) }.toMap(),
        typeCheckerDispatchers = typeCheckerExecutors.map { (k, v) -> k to CheckerDispatcherImpl(v) }.toMap(),
    )
}

class MockFieldResolverDispatcherRegistry(vararg bindings: Pair<Coordinate, FieldResolverDispatcher>) : FieldResolverDispatcherRegistry {
    private val bindingsMap = bindings.toMap()

    override fun getFieldResolverDispatcher(
        typeName: String,
        fieldName: String
    ): FieldResolverDispatcher? = bindingsMap[typeName to fieldName]
}

open class MockFieldUnbatchedResolverExecutor(
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    val resolverName: String = "mock-field-unbatched-resolver",
    override val resolverId: String,
    open val unbatchedResolveFn: FieldUnbatchedResolverFn = { _, _, _, _, _ -> null }
) : FieldResolverExecutor {
    override val isBatching: Boolean = false
    override val metadata = ResolverMetadata.forMock(resolverName)

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> {
        require(selectors.size == 1) { "Unbatched resolver should only receive single selector, got {}".format(selectors.size) }
        val selector = selectors.first()
        return mapOf(selector to runCatching { unbatchedResolveFn(selector.arguments, selector.objectValue, selector.queryValue, selector.selections, context) })
    }

    companion object {
        /** a [FieldResolverExecutor] implementation that always returns `null` */
        val Null: MockFieldUnbatchedResolverExecutor = MockFieldUnbatchedResolverExecutor(resolverId = "") { _, _, _, _, _ -> null }
    }
}

open class MockFieldBatchResolverExecutor(
    override val objectSelectionSet: RequiredSelectionSet? = null,
    override val querySelectionSet: RequiredSelectionSet? = null,
    val resolverName: String = "mock-field-batch-resolver",
    override val resolverId: String,
    open val batchResolveFn: FieldBatchResolverFn = { _, _ -> throw NotImplementedError() }
) : FieldResolverExecutor {
    override val isBatching: Boolean = true
    override val metadata = ResolverMetadata.forMock(resolverName)

    override suspend fun batchResolve(
        selectors: List<FieldResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<FieldResolverExecutor.Selector, Result<Any?>> = batchResolveFn(selectors, context)
}

@OptIn(ExperimentalCoroutinesApi::class)
fun FieldResolverExecutor.invoke(
    fullSchema: ViaductSchema,
    coord: Coordinate,
    arguments: Map<String, Any?> = emptyMap(),
    objectValue: Map<String, Any?> = emptyMap(),
    queryValue: Map<String, Any?> = emptyMap(),
    selections: RawSelectionSet? = null,
    context: EngineExecutionContext = ContextMocks(fullSchema).engineExecutionContext,
) = runBlocking(MockNextTickDispatcher()) {
    val selector = FieldResolverExecutor.Selector(
        arguments = arguments,
        objectValue = mkEngineObjectData(fullSchema.schema.getObjectType(coord.first), objectValue),
        queryValue = mkEngineObjectData(fullSchema.schema.queryType, queryValue),
        selections = selections,
    )
    batchResolve(listOf(selector), context)[selector]?.getOrNull()
}

@OptIn(ExperimentalCoroutinesApi::class)
fun CheckerExecutor.invoke(
    fullSchema: ViaductSchema,
    coord: Coordinate,
    arguments: Map<String, Any?> = emptyMap(),
    objectDataMap: Map<String, Map<String, Any?>> = emptyMap(),
    context: EngineExecutionContext = ContextMocks(fullSchema).engineExecutionContext,
) = runBlocking(MockNextTickDispatcher()) {
    val objectType = fullSchema.schema.getObjectType(coord.first)!!
    val objectMap = objectDataMap.mapValues { (_, it) -> mkEngineObjectData(objectType, it) }
    execute(arguments, objectMap, context)
}

class MockCheckerErrorResult(override val error: Exception) : CheckerResult.Error {
    override fun isErrorForResolver(ctx: CheckerResultContext): Boolean {
        return true
    }

    override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error {
        return fieldResult
    }
}

class MockCheckerExecutor(
    override val requiredSelectionSets: Map<String, RequiredSelectionSet?> = emptyMap(),
    val executeFn: CheckerFn = { _, _ -> }
) : CheckerExecutor {
    override suspend fun execute(
        arguments: Map<String, Any?>,
        objectDataMap: Map<String, EngineObjectData>,
        context: EngineExecutionContext
    ): CheckerResult {
        try {
            executeFn(arguments, objectDataMap)
        } catch (e: Exception) {
            return MockCheckerErrorResult(e)
        }
        return CheckerResult.Success
    }
}

class MockNodeUnbatchedResolverExecutor(
    override val typeName: String = "MockNode",
    val unbatchedResolveFn: NodeUnbatchedResolverFn = { _, _, _ -> throw NotImplementedError() }
) : NodeResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forMock("Node:$typeName")
    override val isBatching: Boolean = false

    override suspend fun batchResolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> {
        return selectors.associateWith { selector ->
            try {
                Result.success(unbatchedResolveFn(selector.id, selector.selections, context))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}

class MockNodeBatchResolverExecutor(
    override val typeName: String,
    val batchResolveFn: NodeBatchResolverFn = { _, _ -> throw NotImplementedError() }
) : NodeResolverExecutor {
    override val metadata: ResolverMetadata = ResolverMetadata.forMock("Node:$typeName")
    override val isBatching: Boolean = true

    override suspend fun batchResolve(
        selectors: List<NodeResolverExecutor.Selector>,
        context: EngineExecutionContext
    ): Map<NodeResolverExecutor.Selector, Result<EngineObjectData>> = batchResolveFn(selectors, context)
}

class MockTenantAPIBootstrapper(
    val tenantModuleBootstrappers: List<TenantModuleBootstrapper> = emptyList()
) : TenantAPIBootstrapper {
    override suspend fun tenantModuleBootstrappers(): Iterable<TenantModuleBootstrapper> = tenantModuleBootstrappers
}

class MockTenantModuleBootstrapper(
    val fullSchema: ViaductSchema,
    val fieldResolverExecutors: Iterable<Pair<Coordinate, FieldResolverExecutor>> = emptyList(),
    val nodeResolverExecutors: Iterable<Pair<String, NodeResolverExecutor>> = emptyList(),
    val checkerExecutors: Map<Coordinate, CheckerExecutor> = emptyMap(),
    val typeCheckerExecutors: Map<String, CheckerExecutor> = emptyMap(),
) : TenantModuleBootstrapper {
    override fun fieldResolverExecutors(schema: ViaductSchema): Iterable<Pair<Coordinate, FieldResolverExecutor>> = fieldResolverExecutors

    override fun nodeResolverExecutors(schema: ViaductSchema): Iterable<Pair<String, NodeResolverExecutor>> = nodeResolverExecutors

    fun resolverAt(coord: Coordinate) = fieldResolverExecutors(fullSchema).first { it.first == coord }.second

    fun checkerAt(coord: Coordinate) = checkerExecutors[coord]

    companion object {
        /**
         * Create a [MockTenantModuleBootstrapper] with the provided schema SDL.
         * This will parse the SDL and create a [ViaductSchema] with actual wiring.
         */
        operator fun invoke(
            schemaSDL: String,
            block: MockTenantModuleBootstrapperDSL<Unit>.() -> Unit
        ) = invoke(mkSchemaWithWiring(schemaSDL), block)

        /**
         * Create a [MockTenantModuleBootstrapper] with the provided [ViaductSchema].
         * The provided schema should already be built with actual wiring via `mkSchemaWithWiring`,
         * not `mkSchema` with mock wiring.
         */
        operator fun invoke(
            schemaWithWiring: ViaductSchema,
            block: MockTenantModuleBootstrapperDSL<Unit>.() -> Unit
        ) = MockTenantModuleBootstrapperDSL(schemaWithWiring, Unit).apply { block() }.create()
    }

    fun resolveField(
        coord: Coordinate,
        arguments: Map<String, Any?> = emptyMap(),
        objectValue: Map<String, Any?> = emptyMap(),
        queryValue: Map<String, Any?> = emptyMap(),
        selections: RawSelectionSet? = null,
        context: EngineExecutionContext = contextMocks.engineExecutionContext,
    ) = resolverAt(coord).invoke(fullSchema, coord, arguments, objectValue, queryValue, selections, context)

    fun checkField(
        coord: Coordinate,
        arguments: Map<String, Any?> = emptyMap(),
        objectDataMap: Map<String, Map<String, Any?>> = emptyMap(),
        context: EngineExecutionContext = contextMocks.engineExecutionContext,
    ) = checkerAt(coord)!!.invoke(fullSchema, coord, arguments, objectDataMap, context)

    fun toDispatcherRegistry(
        checkerExecutors: Map<Coordinate, CheckerExecutor>? = null,
        typeCheckerExecutors: Map<String, CheckerExecutor>? = null
    ): DispatcherRegistry =
        mkDispatcherRegistry(
            fieldResolverExecutors.toMap(),
            nodeResolverExecutors.toMap(),
            checkerExecutors ?: this.checkerExecutors,
            typeCheckerExecutors ?: this.typeCheckerExecutors,
        )

    val contextMocks by lazy {
        ContextMocks(
            myFullSchema = fullSchema,
            myDispatcherRegistry = this.toDispatcherRegistry(),
        )
    }
}

fun mkEngineObjectData(
    graphQLObjectType: GraphQLObjectType,
    data: Map<String, Any?>,
): ResolvedEngineObjectData {
    fun cvt(
        type: GraphQLType,
        value: Any?
    ): Any? =
        @Suppress("UNCHECKED_CAST")
        when (type) {
            is GraphQLNonNull -> cvt(type.wrappedType as GraphQLOutputType, value)
            is GraphQLList -> (value as List<*>?)?.map {
                cvt(type.wrappedType as GraphQLOutputType, it)
            }

            is GraphQLObjectType -> (value as Map<String, Any?>?)?.let { mkEngineObjectData(type, it) }
            is GraphQLCompositeType -> throw IllegalArgumentException("don't know how to wrap type $type with value $value")
            else -> value
        }

    return ResolvedEngineObjectData
        .Builder(graphQLObjectType)
        .apply {
            data.forEach { (fname, value) ->
                val cvtValue = cvt(graphQLObjectType.getFieldDefinition(fname).type, value)
                put(fname, cvtValue)
            }
        }.build()
}

class MockCheckerExecutorFactory(
    val checkerExecutors: Map<Coordinate, CheckerExecutor>? = null,
    val typeCheckerExecutors: Map<String, CheckerExecutor>? = null
) : CheckerExecutorFactory {
    override fun checkerExecutorForField(
        schema: ViaductSchema,
        typeName: String,
        fieldName: String
    ): CheckerExecutor? {
        return checkerExecutors?.get(Pair(typeName, fieldName))
    }

    override fun checkerExecutorForType(
        schema: ViaductSchema,
        typeName: String
    ): CheckerExecutor? {
        return typeCheckerExecutors?.get(typeName)
    }
}

object Samples {
    val testSchema = mkSchemaWithWiring(
        """
        extend type Query {
            foo: String
        }
        type TestType {
            aField: String
            bIntField: Int
            parameterizedField(experiment: Boolean): Boolean
            cField(f1: String, f2: Int): String
            dField: String
            batchField: String
        }
        type TestNode implements Node { id: ID! }
        type TestBatchNode implements Node { id: ID! }
        """.trimIndent()
    )

    val mockTenantModule = MockTenantModuleBootstrapper(testSchema) {
        // Add resolver for aField
        field("TestType" to "aField") {
            resolver {
                fn { _, _, _, _, _ -> "aField" }
            }
        }

        // Add resolver for bIntField
        field("TestType" to "bIntField") {
            resolver {
                fn { _, _, _, _, _ -> 42 }
            }
        }

        // Add resolver for parameterizedField with a required selection set
        field("TestType" to "parameterizedField") {
            resolver {
                objectSelections("fragment _ on TestType { aField @include(if: \$experiment) bIntField }") {
                    variables("experiment") { ctx ->
                        mapOf("experiment" to (ctx.arguments["experiment"] ?: false))
                    }
                }
                fn { args, _, _, _, _ -> args["experiment"] as? Boolean ?: false }
            }
        }

        // Add resolver for cField
        field("TestType" to "cField") {
            resolver {
                fn { _, _, _, _, _ -> "cField" }
            }
        }

        // Add resolver for dField with variable provider
        field("TestType" to "dField") {
            resolver {
                objectSelections("fragment _ on TestType { aField @include(if: \$experiment) bIntField }") {
                    variables("experiment") { _ ->
                        mapOf("experiment" to true)
                    }
                }
                fn { _, _, _, _, _ -> "dField" }
            }
        }

        // Add batch resolver for batchField
        field("TestType" to "batchField") {
            resolver {
                fn { _, _ -> mapOf() }
            }
        }

        // Add node resolver for TestNode
        type("TestNode") {
            nodeUnbatchedExecutor { id, _, _ ->
                mkEngineObjectData(
                    testSchema.schema.getObjectType("TestNode"),
                    mapOf("id" to id)
                )
            }
        }

        // Add a batch node resolver for TestBatchNode
        type("TestBatchNode") {
            nodeBatchedExecutor { selectors, _ ->
                selectors.associateWith { selector ->
                    Result.success(
                        mkEngineObjectData(
                            testSchema.schema.getObjectType("TestBatchNode"),
                            mapOf("id" to selector.id)
                        )
                    )
                }
            }
        }
    }
}
