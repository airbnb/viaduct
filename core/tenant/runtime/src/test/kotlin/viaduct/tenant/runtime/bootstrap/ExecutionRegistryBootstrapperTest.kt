package viaduct.tenant.runtime.bootstrap

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldValue
import viaduct.api.NodeResolverBase
import viaduct.api.Resolver
import viaduct.api.ResolverBase
import viaduct.api.bootstrap.test.grts.TestBatchNode
import viaduct.api.bootstrap.test.grts.TestNode
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.DefaultGRTConvFactory
import viaduct.api.internal.InternalContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistry
import viaduct.engine.api.bootstrap.executionregistry.FieldAPIData
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeAPIData
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlock
import viaduct.engine.api.bootstrap.executionregistry.VariableProviderEntry
import viaduct.engine.api.mocks.MockSchema
import viaduct.service.api.spi.TenantCodeInjector

class ExecutionRegistryBootstrapperTest {
    private val schema = MockSchema.mk(
        """
        type TestType {
            aField: String
        }
        extend type Query {
            testField: TestType
            testBatchField: String
            flagField: Boolean
            nodeLookup: TestNode
        }
        type TestNode implements Node @resolver {
            id: ID!
        }
        type TestBatchNode implements Node @resolver {
            id: ID!
        }
        """.trimIndent()
    )

    private val injector = TenantCodeInjector.Naive
    private val grtPackagePrefix = "viaduct.api.bootstrap.test.grts"

    // ── Resolver base + impl for a simple field ──────────────────────────────

    abstract class TestFieldResolverBase : ResolverBase<String> {
        abstract suspend fun resolve(ctx: Context): String

        class Context(
            private val inner: FieldExecutionContext<*, *, *, *>
        ) : FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
            by (inner as FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>),
            InternalContext by (inner as InternalContext)
    }

    class TestFieldResolver : TestFieldResolverBase() {
        override suspend fun resolve(ctx: Context): String = "hello"
    }

    // ── Resolver base + impl for a batching field ─────────────────────────────

    abstract class TestBatchFieldResolverBase : ResolverBase<String> {
        abstract suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<String>>

        class Context(
            private val inner: FieldExecutionContext<*, *, *, *>
        ) : FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
            by (inner as FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>),
            InternalContext by (inner as InternalContext)
    }

    class TestBatchFieldResolver : TestBatchFieldResolverBase() {
        override suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<String>> = emptyList()
    }

    // ── Node resolver base + impl ─────────────────────────────────────────────

    abstract class TestNodeResolverBase : NodeResolverBase<TestNode> {
        abstract suspend fun resolve(ctx: Context): TestNode

        class Context(
            private val inner: NodeExecutionContext<TestNode>
        ) : NodeExecutionContext<TestNode> by inner
    }

    @Resolver
    class TestNodeResolver : TestNodeResolverBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    // ── Batch node resolver base + impl ───────────────────────────────────────

    abstract class TestBatchNodeResolverBase : NodeResolverBase<TestBatchNode> {
        abstract suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<TestBatchNode>>

        class Context(
            private val inner: NodeExecutionContext<TestBatchNode>
        ) : NodeExecutionContext<TestBatchNode> by inner
    }

    @Resolver
    class TestBatchNodeResolver : TestBatchNodeResolverBase() {
        override suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<TestBatchNode>> = emptyList()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun bootstrapper(registry: ExecutionRegistry) =
        ExecutionRegistryBootstrapper(
            registry = registry,
            tenantCodeInjector = injector,
            grtPackagePrefix = grtPackagePrefix,
            grtConvFactory = DefaultGRTConvFactory,
        )

    private val pkg = ExecutionRegistryBootstrapperTest::class.java.name

    private fun fieldEntry(
        typeName: String,
        fieldName: String,
        resolverSimpleName: String,
        resolverBaseSimpleName: String,
        isBatching: Boolean = false,
        objectSelections: SelectionsBlock? = null,
        querySelections: SelectionsBlock? = null,
    ) = FieldEntry(
        typeName = typeName,
        fieldName = fieldName,
        isBatching = isBatching,
        isSelective = false,
        attribution = "$typeName.$fieldName",
        objectSelections = objectSelections,
        querySelections = querySelections,
        tenantAPIData = FieldAPIData(
            resolverClass = "$pkg\$$resolverSimpleName",
            resolverBaseClass = "$pkg\$$resolverBaseSimpleName",
        ),
    )

    private fun nodeEntry(
        typeName: String,
        resolverSimpleName: String,
        resolverBaseSimpleName: String,
        isBatching: Boolean = false,
    ) = NodeEntry(
        typeName = typeName,
        isBatching = isBatching,
        isSelective = false,
        attribution = typeName,
        tenantAPIData = NodeAPIData(
            resolverClass = "$pkg\$$resolverSimpleName",
            resolverBaseClass = "$pkg\$$resolverBaseSimpleName",
        ),
    )

    // ── toSelectionSetVariable (via buildVariables path) ─────────────────────

    // Fragment: flagField provides variable $x; testBatchField is conditionally included using it.
    // Two distinct fields prevent a variable-cycle error.
    private val fragmentWithVariable = "fragment _ on Query { flagField, testBatchField @include(if: \$x) }"

    @Test
    fun `toSelectionSetVariable - fromArgument provider is wired correctly`() {
        val selections = SelectionsBlock(
            selections = fragmentWithVariable,
            variablesProviders = listOf(
                VariableProviderEntry(
                    providedVariables = mapOf("x" to "Boolean!"),
                    providerVariablesAPIData = ProviderVariablesAPIData(type = "fromArgument", path = "flagField"),
                )
            )
        )
        val entry = fieldEntry(
            typeName = "Query",
            fieldName = "testField",
            resolverSimpleName = "TestFieldResolver",
            resolverBaseSimpleName = "TestFieldResolverBase",
            querySelections = selections,
        )
        val executors = bootstrapper(ExecutionRegistry(version = "1", executorFactory = "", fields = listOf(entry)))
            .fieldResolverExecutors(schema).toList()
        assert(executors.size == 1)
    }

    @Test
    fun `toSelectionSetVariable - fromObjectField provider is wired correctly`() {
        // objectSelections parsed against entry.typeName ("Query"); path must match a field in the fragment
        val selections = SelectionsBlock(
            selections = fragmentWithVariable,
            variablesProviders = listOf(
                VariableProviderEntry(
                    providedVariables = mapOf("x" to "Boolean!"),
                    providerVariablesAPIData = ProviderVariablesAPIData(type = "fromObjectField", path = "flagField"),
                )
            )
        )
        val entry = fieldEntry(
            typeName = "Query",
            fieldName = "testField",
            resolverSimpleName = "TestFieldResolver",
            resolverBaseSimpleName = "TestFieldResolverBase",
            objectSelections = selections,
        )
        val executors = bootstrapper(ExecutionRegistry(version = "1", executorFactory = "", fields = listOf(entry)))
            .fieldResolverExecutors(schema).toList()
        assert(executors.size == 1)
    }

    @Test
    fun `toSelectionSetVariable - fromQueryField provider is wired correctly`() {
        val selections = SelectionsBlock(
            selections = fragmentWithVariable,
            variablesProviders = listOf(
                VariableProviderEntry(
                    providedVariables = mapOf("x" to "Boolean!"),
                    providerVariablesAPIData = ProviderVariablesAPIData(type = "fromQueryField", path = "flagField"),
                )
            )
        )
        val entry = fieldEntry(
            typeName = "Query",
            fieldName = "testField",
            resolverSimpleName = "TestFieldResolver",
            resolverBaseSimpleName = "TestFieldResolverBase",
            querySelections = selections,
        )
        val executors = bootstrapper(ExecutionRegistry(version = "1", executorFactory = "", fields = listOf(entry)))
            .fieldResolverExecutors(schema).toList()
        assert(executors.size == 1)
    }

    @Test
    fun `toSelectionSetVariable - unknown provider type throws`() {
        val selections = SelectionsBlock(
            selections = fragmentWithVariable,
            variablesProviders = listOf(
                VariableProviderEntry(
                    providedVariables = mapOf("x" to "Boolean!"),
                    providerVariablesAPIData = ProviderVariablesAPIData(type = "unknown", path = "flagField"),
                )
            )
        )
        val entry = fieldEntry(
            typeName = "Query",
            fieldName = "testField",
            resolverSimpleName = "TestFieldResolver",
            resolverBaseSimpleName = "TestFieldResolverBase",
            querySelections = selections,
        )
        assertThrows<IllegalStateException> {
            bootstrapper(ExecutionRegistry(version = "1", executorFactory = "", fields = listOf(entry)))
                .fieldResolverExecutors(schema).toList()
        }
    }

    @Test
    fun `buildVariables - empty providers yields no variables`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            fields = listOf(fieldEntry("Query", "testField", "TestFieldResolver", "TestFieldResolverBase")),
        )
        val executors = bootstrapper(registry).fieldResolverExecutors(schema).toList()
        assert(executors.size == 1)
    }

    // ── Field resolver construction ───────────────────────────────────────────

    @Test
    fun `fieldResolverExecutors - non-batching field produces FieldResolverExecutor`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            fields = listOf(fieldEntry("Query", "testField", "TestFieldResolver", "TestFieldResolverBase")),
        )
        val executors = bootstrapper(registry).fieldResolverExecutors(schema).toList()
        assert(executors.size == 1)
        val (coord, executor) = executors[0]
        assert(coord == ("Query" to "testField"))
        assert(!executor.isBatching)
    }

    @Test
    fun `fieldResolverExecutors - batching field produces batching FieldResolverExecutor`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            fields = listOf(
                fieldEntry(
                    typeName = "Query",
                    fieldName = "testBatchField",
                    resolverSimpleName = "TestBatchFieldResolver",
                    resolverBaseSimpleName = "TestBatchFieldResolverBase",
                    isBatching = true,
                )
            ),
        )
        val executors = bootstrapper(registry).fieldResolverExecutors(schema).toList()
        assert(executors.size == 1)
        val (_, executor) = executors[0]
        assert(executor.isBatching)
    }

    @Test
    fun `fieldResolverExecutors - batching=true but no batchResolve method throws`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            fields = listOf(
                fieldEntry(
                    typeName = "Query",
                    fieldName = "testField",
                    resolverSimpleName = "TestFieldResolver",
                    resolverBaseSimpleName = "TestFieldResolverBase",
                    isBatching = true, // mismatch: TestFieldResolver has only resolve()
                )
            ),
        )
        assertThrows<IllegalStateException> {
            bootstrapper(registry).fieldResolverExecutors(schema).toList()
        }
    }

    // ── Node resolver construction ────────────────────────────────────────────

    @Test
    fun `nodeResolverExecutors - non-batching node produces NodeResolverExecutor`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            nodes = listOf(nodeEntry("TestNode", "TestNodeResolver", "TestNodeResolverBase")),
        )
        val executors = bootstrapper(registry).nodeResolverExecutors(schema).toList()
        assert(executors.size == 1)
        val (typeName, _) = executors[0]
        assert(typeName == "TestNode")
    }

    @Test
    fun `nodeResolverExecutors - batching node produces batching executor`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            nodes = listOf(
                nodeEntry("TestBatchNode", "TestBatchNodeResolver", "TestBatchNodeResolverBase", isBatching = true)
            ),
        )
        val executors = bootstrapper(registry).nodeResolverExecutors(schema).toList()
        assert(executors.size == 1)
    }

    @Test
    fun `nodeResolverExecutors - unknown class throws ClassNotFoundException`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            nodes = listOf(
                NodeEntry(
                    typeName = "TestNode",
                    isBatching = false,
                    isSelective = false,
                    attribution = "TestNode",
                    tenantAPIData = NodeAPIData(
                        resolverClass = "viaduct.does.not.exist.Resolver",
                        resolverBaseClass = "viaduct.does.not.exist.ResolverBase",
                    ),
                )
            ),
        )
        assertThrows<ClassNotFoundException> {
            bootstrapper(registry).nodeResolverExecutors(schema).toList()
        }
    }

    @Test
    fun `nodeResolverExecutors - unknown class exception preserves last attempt as cause`() {
        val registry = ExecutionRegistry(
            version = "1",
            executorFactory = "",
            nodes = listOf(
                NodeEntry(
                    typeName = "TestNode",
                    isBatching = false,
                    isSelective = false,
                    attribution = "TestNode",
                    tenantAPIData = NodeAPIData(
                        resolverClass = "viaduct.does.not.exist.Resolver",
                        resolverBaseClass = "viaduct.does.not.exist.ResolverBase",
                    ),
                )
            ),
        )
        assertThrows<ClassNotFoundException> {
            bootstrapper(registry).nodeResolverExecutors(schema).toList()
        }
    }
}
