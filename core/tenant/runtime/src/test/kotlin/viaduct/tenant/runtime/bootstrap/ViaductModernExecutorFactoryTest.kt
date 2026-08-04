package viaduct.tenant.runtime.bootstrap

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.FieldResolverBase
import viaduct.api.FieldValue
import viaduct.api.NodeResolverBase
import viaduct.api.ResolverBase
import viaduct.api.bootstrap.test.grts.TestBatchNode
import viaduct.api.bootstrap.test.grts.TestNode
import viaduct.api.context.BaseFieldExecutionContext
import viaduct.api.context.FieldExecutionContext
import viaduct.api.context.NodeExecutionContext
import viaduct.api.internal.BaseBatchedFieldResolver
import viaduct.api.internal.BaseBatchedNodeResolver
import viaduct.api.internal.BaseUnbatchedFieldResolver
import viaduct.api.internal.BaseUnbatchedNodeResolver
import viaduct.api.internal.InternalContext
import viaduct.api.resolver.Resolver
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.apiannotations.InternalApi
import viaduct.engine.api.bootstrap.executionregistry.ExecutionRegistryConfigFile
import viaduct.engine.api.bootstrap.executionregistry.FieldEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.NodeEntryConfig
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlockConfig
import viaduct.engine.api.bootstrap.executionregistry.VariableProviderEntryConfig
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector

@Suppress("USELESS_IS_CHECK", "UNCHECKED_CAST")
class ViaductModernExecutorFactoryTest {
    abstract class TestFieldResolverBase :
        ResolverBase<String>,
        FieldResolverBase<Object, Query, Arguments.NoArguments, String>,
        BaseUnbatchedFieldResolver {
        abstract suspend fun resolve(ctx: Context): String

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldResolver(context: BaseFieldExecutionContext<*, *, *>): Any? = resolve(Context(context as FieldExecutionContext<*, *, *, *>))

        class Context(
            private val inner: FieldExecutionContext<*, *, *, *>,
        ) : FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
            by (inner as FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>),
            InternalContext by (inner as InternalContext)
    }

    class TestFieldResolver : TestFieldResolverBase() {
        override suspend fun resolve(ctx: Context): String = "hello"
    }

    abstract class TestBatchFieldResolverBase :
        ResolverBase<String>,
        FieldResolverBase<Object, Query, Arguments.NoArguments, String>,
        BaseBatchedFieldResolver {
        abstract suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<String>>

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeFieldBatchResolver(contexts: List<BaseFieldExecutionContext<*, *, *>>): Any? = batchResolve(contexts.map { Context(it as FieldExecutionContext<*, *, *, *>) })

        class Context(
            private val inner: FieldExecutionContext<*, *, *, *>,
        ) : FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
            by (inner as FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>),
            InternalContext by (inner as InternalContext)
    }

    class TestBatchFieldResolver : TestBatchFieldResolverBase() {
        override suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<String>> = emptyList()
    }

    abstract class TestNodeResolverBase : NodeResolverBase<TestNode>, BaseUnbatchedNodeResolver {
        abstract suspend fun resolve(ctx: Context): TestNode

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeNodeResolver(context: NodeExecutionContext<*>): Any? = resolve(Context(context as NodeExecutionContext<TestNode>))

        class Context(
            private val inner: NodeExecutionContext<TestNode>,
        ) : NodeExecutionContext<TestNode> by inner
    }

    @Resolver
    class TestNodeResolver : TestNodeResolverBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    abstract class TestBatchNodeResolverBase :
        NodeResolverBase<TestBatchNode>,
        BaseBatchedNodeResolver {
        abstract suspend fun batchResolve(ctxs: List<Context>): Map<Context, FieldValue<TestBatchNode>>

        @Suppress("UNCHECKED_CAST")
        final override suspend fun invokeNodeBatchResolver(contexts: List<NodeExecutionContext<*>>): Map<NodeExecutionContext<*>, FieldValue<TestBatchNode>> {
            val wrappedContexts = contexts.map { Context(it as NodeExecutionContext<TestBatchNode>) }
            return batchResolve(wrappedContexts).mapKeys { it.key.inner }
        }

        class Context(
            @InternalApi internal val inner: NodeExecutionContext<TestBatchNode>,
        ) : NodeExecutionContext<TestBatchNode> by inner
    }

    @Resolver
    class TestBatchNodeResolver : TestBatchNodeResolverBase() {
        override suspend fun batchResolve(ctxs: List<Context>): Map<Context, FieldValue<TestBatchNode>> = emptyMap()
    }

    private val schema = MockSchema.mk("type TestType { aField: String }")

    private val pkg = ViaductModernExecutorFactoryTest::class.java.name

    private fun factory() =
        ViaductModernExecutorFactory(
            codeInjector = CodeInjector.Naive,
            grtPackagePrefix = "viaduct.api.bootstrap.test.grts",
            registry = ExecutionRegistryConfigFile(version = "1", executorFactory = ViaductModernExecutorFactory::class.java.name),
        )

    private fun fieldEntry(
        typeName: String = "TestType",
        fieldName: String = "aField",
        resolverSimpleName: String,
        resolverBaseSimpleName: String,
        isBatching: Boolean = false,
        hasArguments: Boolean = false,
        queryTypeName: String = "Query",
        objectSelections: SelectionsBlockConfig? = null,
        querySelections: SelectionsBlockConfig? = null,
    ) = FieldEntryConfig(
        typeName = typeName,
        fieldName = fieldName,
        isBatching = isBatching,
        isSelective = false,
        attribution = "$typeName.$fieldName",
        objectSelections = objectSelections,
        querySelections = querySelections,
        tenantAPIData = mapOf(
            "resolverClass" to "$pkg\$$resolverSimpleName",
            "resolverBaseClass" to "$pkg\$$resolverBaseSimpleName",
            "hasArguments" to hasArguments,
            "queryTypeName" to queryTypeName,
        ),
    )

    private fun nodeEntry(
        typeName: String,
        resolverSimpleName: String,
        resolverBaseSimpleName: String,
        isBatching: Boolean = false,
    ) = NodeEntryConfig(
        typeName = typeName,
        isBatching = isBatching,
        isSelective = false,
        attribution = typeName,
        tenantAPIData = mapOf(
            "resolverClass" to "$pkg\$$resolverSimpleName",
            "resolverBaseClass" to "$pkg\$$resolverBaseSimpleName",
        ),
    )

    // ── Field resolver ────────────────────────────────────────────────────────

    @Test
    fun `createFieldResolverExecutor - non-batching field produces non-batching executor`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry(resolverSimpleName = "TestFieldResolver", resolverBaseSimpleName = "TestFieldResolverBase"),
            schema,
        )
        assert(!executor.isBatching)
    }

    @Test
    fun `createFieldResolverExecutor - batching field produces batching executor`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry(
                resolverSimpleName = "TestBatchFieldResolver",
                resolverBaseSimpleName = "TestBatchFieldResolverBase",
                isBatching = true,
            ),
            schema,
        )
        assert(executor.isBatching)
    }

    @Test
    fun `createFieldResolverExecutor - isBatching=true but no batchResolve throws`() {
        assertThrows<IllegalStateException> {
            factory().createFieldResolverExecutor(
                fieldEntry(
                    resolverSimpleName = "TestFieldResolver",
                    resolverBaseSimpleName = "TestFieldResolverBase",
                    isBatching = true,
                ),
                schema,
            )
        }
    }

    @Test
    fun `createFieldResolverExecutor - non-batching field with batch adapter throws`() {
        assertThrows<IllegalStateException> {
            factory().createFieldResolverExecutor(
                fieldEntry(
                    resolverSimpleName = "TestBatchFieldResolver",
                    resolverBaseSimpleName = "TestBatchFieldResolverBase",
                ),
                schema,
            )
        }
    }

    @Test
    fun `createFieldResolverExecutor - unknown resolverClass throws ClassNotFoundException`() {
        assertThrows<ClassNotFoundException> {
            factory().createFieldResolverExecutor(
                FieldEntryConfig(
                    typeName = "TestType",
                    fieldName = "aField",
                    isBatching = false,
                    isSelective = false,
                    attribution = "TestType.aField",
                    tenantAPIData = mapOf(
                        "resolverClass" to "com.does.not.Exist",
                        "resolverBaseClass" to "com.does.not.ExistBase",
                        "queryTypeName" to "Query",
                    ),
                ),
                schema,
            )
        }
    }

    @Test
    fun `createFieldResolverExecutor - querySelections fragment type condition drives selection type name`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry(
                resolverSimpleName = "TestFieldResolver",
                resolverBaseSimpleName = "TestFieldResolverBase",
                querySelections = SelectionsBlockConfig(selections = "fragment _ on Query { __typename }"),
            ),
            schema,
        )
        assert(executor is FieldResolverExecutor)
    }

    @Test
    fun `createFieldResolverExecutor - querySelections with no fragment definition throws`() {
        assertThrows<IllegalArgumentException> {
            factory().createFieldResolverExecutor(
                fieldEntry(
                    resolverSimpleName = "TestFieldResolver",
                    resolverBaseSimpleName = "TestFieldResolverBase",
                    querySelections = SelectionsBlockConfig(selections = "{ __typename }"),
                ),
                schema,
            )
        }
    }

    // ── Node resolver ─────────────────────────────────────────────────────────

    @Test
    fun `createNodeResolverExecutor - non-batching node produces non-batching executor`() {
        val executor = factory().createNodeResolverExecutor(
            nodeEntry("TestNode", "TestNodeResolver", "TestNodeResolverBase"),
            schema,
        )
        assert(executor is NodeResolverExecutor)
        assert(!executor.isBatching)
    }

    @Test
    fun `createNodeResolverExecutor - batching node produces batching executor`() {
        val executor = factory().createNodeResolverExecutor(
            nodeEntry("TestBatchNode", "TestBatchNodeResolver", "TestBatchNodeResolverBase", isBatching = true),
            schema,
        )
        assert(executor.isBatching)
    }

    @Test
    fun `createNodeResolverExecutor - isBatching=true but no batchResolve throws`() {
        assertThrows<IllegalStateException> {
            factory().createNodeResolverExecutor(
                nodeEntry("TestNode", "TestNodeResolver", "TestNodeResolverBase", isBatching = true),
                schema,
            )
        }
    }

    @Test
    fun `createNodeResolverExecutor - non-batching node with batch adapter throws`() {
        assertThrows<IllegalStateException> {
            factory().createNodeResolverExecutor(
                nodeEntry("TestBatchNode", "TestBatchNodeResolver", "TestBatchNodeResolverBase"),
                schema,
            )
        }
    }

    @Test
    fun `createNodeResolverExecutor - unknown resolverClass throws ClassNotFoundException`() {
        assertThrows<ClassNotFoundException> {
            factory().createNodeResolverExecutor(
                NodeEntryConfig(
                    typeName = "TestNode",
                    isBatching = false,
                    isSelective = false,
                    attribution = "TestNode",
                    tenantAPIData = mapOf(
                        "resolverClass" to "com.does.not.Exist",
                        "resolverBaseClass" to "com.does.not.ExistBase",
                    ),
                ),
                schema,
            )
        }
    }

    // ── toSelectionSetVariable / buildVariables ───────────────────────────────

    // Fragment: flagField provides variable $x; testBatchField conditionally included using it.
    private val fragmentWithVariable = "fragment _ on Query { flagField, testBatchField @include(if: \$x) }"

    private fun fieldEntryWithQuerySelections(selections: SelectionsBlockConfig) =
        fieldEntry(
            typeName = "Query",
            fieldName = "aField",
            resolverSimpleName = "TestFieldResolver",
            resolverBaseSimpleName = "TestFieldResolverBase",
            querySelections = selections,
        )

    @Test
    fun `createFieldResolverExecutor - fromArgument variable provider is wired correctly`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntryWithQuerySelections(
                SelectionsBlockConfig(
                    selections = fragmentWithVariable,
                    variablesProviders = listOf(
                        VariableProviderEntryConfig(
                            providedVariables = mapOf("x" to "Boolean!"),
                            providerVariablesAPIData = ProviderVariablesAPIData(type = "fromArgument", path = "flagField"),
                        )
                    ),
                )
            ),
            schema,
        )
        assert(executor is FieldResolverExecutor)
    }

    @Test
    fun `createFieldResolverExecutor - fromObjectField variable provider is wired correctly`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry(
                typeName = "Query",
                fieldName = "aField",
                resolverSimpleName = "TestFieldResolver",
                resolverBaseSimpleName = "TestFieldResolverBase",
                objectSelections = SelectionsBlockConfig(
                    selections = fragmentWithVariable,
                    variablesProviders = listOf(
                        VariableProviderEntryConfig(
                            providedVariables = mapOf("x" to "Boolean!"),
                            providerVariablesAPIData = ProviderVariablesAPIData(type = "fromObjectField", path = "flagField"),
                        )
                    ),
                ),
            ),
            schema,
        )
        assert(executor is FieldResolverExecutor)
    }

    @Test
    fun `createFieldResolverExecutor - fromQueryField variable provider is wired correctly`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntryWithQuerySelections(
                SelectionsBlockConfig(
                    selections = fragmentWithVariable,
                    variablesProviders = listOf(
                        VariableProviderEntryConfig(
                            providedVariables = mapOf("x" to "Boolean!"),
                            providerVariablesAPIData = ProviderVariablesAPIData(type = "fromQueryField", path = "flagField"),
                        )
                    ),
                )
            ),
            schema,
        )
        assert(executor is FieldResolverExecutor)
    }

    @Test
    fun `createFieldResolverExecutor - unknown variable provider type throws`() {
        assertThrows<IllegalStateException> {
            factory().createFieldResolverExecutor(
                fieldEntryWithQuerySelections(
                    SelectionsBlockConfig(
                        selections = fragmentWithVariable,
                        variablesProviders = listOf(
                            VariableProviderEntryConfig(
                                providedVariables = mapOf("x" to "Boolean!"),
                                providerVariablesAPIData = ProviderVariablesAPIData(type = "unknown", path = "flagField"),
                            )
                        ),
                    )
                ),
                schema,
            )
        }
    }

    @Test
    fun `createFieldResolverExecutor - empty providers yields executor without variables`() {
        val executor = factory().createFieldResolverExecutor(
            fieldEntry(
                typeName = "Query",
                fieldName = "aField",
                resolverSimpleName = "TestFieldResolver",
                resolverBaseSimpleName = "TestFieldResolverBase",
            ),
            schema,
        )
        assert(executor is FieldResolverExecutor)
    }
}
