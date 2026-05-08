package viaduct.tenant.runtime.bootstrap

import java.net.URI
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
import viaduct.api.internal.InternalContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Object
import viaduct.api.types.Query
import viaduct.engine.api.bootstrap.executionregistry.FieldAPIData
import viaduct.engine.api.bootstrap.executionregistry.FieldEntry
import viaduct.engine.api.bootstrap.executionregistry.NodeAPIData
import viaduct.engine.api.bootstrap.executionregistry.NodeEntry
import viaduct.engine.api.bootstrap.executionregistry.ProviderVariablesAPIData
import viaduct.engine.api.bootstrap.executionregistry.SelectionsBlock
import viaduct.engine.api.bootstrap.executionregistry.VariableProviderEntry
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.service.api.spi.CodeInjector

@Suppress("USELESS_IS_CHECK", "UNCHECKED_CAST")
class ViaductModernExecutorFactoryTest {
    abstract class TestFieldResolverBase : ResolverBase<String> {
        abstract suspend fun resolve(ctx: Context): String

        class Context(
            private val inner: FieldExecutionContext<*, *, *, *>,
        ) : FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
            by (inner as FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>),
            InternalContext by (inner as InternalContext)
    }

    class TestFieldResolver : TestFieldResolverBase() {
        override suspend fun resolve(ctx: Context): String = "hello"
    }

    abstract class TestBatchFieldResolverBase : ResolverBase<String> {
        abstract suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<String>>

        class Context(
            private val inner: FieldExecutionContext<*, *, *, *>,
        ) : FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>
            by (inner as FieldExecutionContext<Object, Query, Arguments.NoArguments, CompositeOutput.NotComposite>),
            InternalContext by (inner as InternalContext)
    }

    class TestBatchFieldResolver : TestBatchFieldResolverBase() {
        override suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<String>> = emptyList()
    }

    abstract class TestNodeResolverBase : NodeResolverBase<TestNode> {
        abstract suspend fun resolve(ctx: Context): TestNode

        class Context(
            private val inner: NodeExecutionContext<TestNode>,
        ) : NodeExecutionContext<TestNode> by inner
    }

    @Resolver
    class TestNodeResolver : TestNodeResolverBase() {
        override suspend fun resolve(ctx: Context): TestNode = TestNode()
    }

    abstract class TestBatchNodeResolverBase : NodeResolverBase<TestBatchNode> {
        abstract suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<TestBatchNode>>

        class Context(
            private val inner: NodeExecutionContext<TestBatchNode>,
        ) : NodeExecutionContext<TestBatchNode> by inner
    }

    @Resolver
    class TestBatchNodeResolver : TestBatchNodeResolverBase() {
        override suspend fun batchResolve(ctxs: List<Context>): List<FieldValue<TestBatchNode>> = emptyList()
    }

    private val schema = MockSchema.mk("type TestType { aField: String }")

    private val pkg = ViaductModernExecutorFactoryTest::class.java.name

    private fun factory() =
        ViaductModernExecutorFactory(
            codeInjector = CodeInjector.Naive,
            grtPackagePrefix = "viaduct.api.bootstrap.test.grts",
            configUrl = URI("file:///dev/null").toURL(),
        )

    private fun fieldEntry(
        typeName: String = "TestType",
        fieldName: String = "aField",
        resolverSimpleName: String,
        resolverBaseSimpleName: String,
        isBatching: Boolean = false,
        hasArguments: Boolean = false,
        queryTypeName: String = "Query",
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
            hasArguments = hasArguments,
            queryTypeName = queryTypeName,
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
    fun `createFieldResolverExecutor - unknown resolverClass throws ClassNotFoundException`() {
        assertThrows<ClassNotFoundException> {
            factory().createFieldResolverExecutor(
                FieldEntry(
                    typeName = "TestType",
                    fieldName = "aField",
                    isBatching = false,
                    isSelective = false,
                    attribution = "TestType.aField",
                    tenantAPIData = FieldAPIData(
                        resolverClass = "com.does.not.Exist",
                        resolverBaseClass = "com.does.not.ExistBase",
                        queryTypeName = "Query",
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
                querySelections = SelectionsBlock(selections = "fragment _ on Query { __typename }"),
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
                    querySelections = SelectionsBlock(selections = "{ __typename }"),
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
    fun `createNodeResolverExecutor - unknown resolverClass throws ClassNotFoundException`() {
        assertThrows<ClassNotFoundException> {
            factory().createNodeResolverExecutor(
                NodeEntry(
                    typeName = "TestNode",
                    isBatching = false,
                    isSelective = false,
                    attribution = "TestNode",
                    tenantAPIData = NodeAPIData(
                        resolverClass = "com.does.not.Exist",
                        resolverBaseClass = "com.does.not.ExistBase",
                    ),
                ),
                schema,
            )
        }
    }

    // ── toSelectionSetVariable / buildVariables ───────────────────────────────

    // Fragment: flagField provides variable $x; testBatchField conditionally included using it.
    private val fragmentWithVariable = "fragment _ on Query { flagField, testBatchField @include(if: \$x) }"

    private fun fieldEntryWithQuerySelections(selections: SelectionsBlock) =
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
                SelectionsBlock(
                    selections = fragmentWithVariable,
                    variablesProviders = listOf(
                        VariableProviderEntry(
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
                objectSelections = SelectionsBlock(
                    selections = fragmentWithVariable,
                    variablesProviders = listOf(
                        VariableProviderEntry(
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
                SelectionsBlock(
                    selections = fragmentWithVariable,
                    variablesProviders = listOf(
                        VariableProviderEntry(
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
                    SelectionsBlock(
                        selections = fragmentWithVariable,
                        variablesProviders = listOf(
                            VariableProviderEntry(
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
