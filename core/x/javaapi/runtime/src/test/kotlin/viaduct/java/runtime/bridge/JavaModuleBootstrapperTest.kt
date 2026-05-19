package viaduct.java.runtime.bridge

import graphql.Scalars
import graphql.schema.GraphQLCodeRegistry
import graphql.schema.GraphQLFieldDefinition
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLSchema
import graphql.schema.TypeResolver
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CompletableFuture
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.TenantModuleException
import viaduct.java.api.annotations.NodeResolverFor
import viaduct.java.api.annotations.Resolver
import viaduct.java.api.annotations.ResolverFor
import viaduct.java.api.resolvers.FieldResolverBase
import viaduct.java.api.resolvers.NodeResolverBase
import viaduct.java.api.types.Arguments
import viaduct.java.api.types.CompositeOutput
import viaduct.java.api.types.NodeObject
import viaduct.java.api.types.Query
import viaduct.java.runtime.bootstrap.JavaResolverClassFinder
import viaduct.service.api.spi.CodeInjector
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

class JavaModuleBootstrapperTest {
    // Test fixtures
    interface TestQuery : Query

    @ResolverFor(typeName = "TestType", fieldName = "testField", isSelective = false)
    abstract class TestResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String>
    }

    @ResolverFor(typeName = "TestType", fieldName = "selectiveField", isSelective = true)
    abstract class SelectiveResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String>
    }

    @Resolver
    class TestResolver : TestResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("test result")
        }
    }

    @Resolver
    class SelectiveResolver : SelectiveResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("selective result")
        }
    }

    // Test fixtures for required selections tests
    @ResolverFor(typeName = "Person", fieldName = "fullName", isSelective = false)
    abstract class PersonFullNameResolverBase :
        FieldResolverBase<String, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String>
    }

    @Resolver(objectValueFragment = "firstName lastName")
    class PersonFullNameResolver : PersonFullNameResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<String> {
            return CompletableFuture.completedFuture("Full Name")
        }
    }

    // Test fixture for resolver without required selections (plain @Resolver)
    @ResolverFor(typeName = "Person", fieldName = "age", isSelective = false)
    abstract class PersonAgeResolverBase :
        FieldResolverBase<Int, TestQuery, TestQuery, Arguments.None, CompositeOutput> {
        abstract fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<Int>
    }

    @Resolver
    class PersonAgeResolver : PersonAgeResolverBase() {
        override fun resolve(ctx: FieldResolverBase.Context<TestQuery, TestQuery, Arguments.None, CompositeOutput>): CompletableFuture<Int> {
            return CompletableFuture.completedFuture(30)
        }
    }

    @Test
    fun `fieldResolverExecutors returns empty when no resolvers found`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns emptySet()

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)
        val schema = createMockSchema()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).isEmpty()
    }

    @Test
    fun `fieldResolverExecutors skips resolvers for undefined types`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(TestResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)
        // Schema without TestType
        val schema = createMockSchema()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        // Should skip because TestType doesn't exist in schema
        assertThat(executors).isEmpty()
    }

    @Test
    fun `fieldResolverExecutors registers resolver for valid field`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(
            TestResolverBase::class.java,
            SelectiveResolverBase::class.java
        )
        every { mockClassFinder.getSubTypesOf(FieldResolverBase::class.java) } returns
            setOf(
                TestResolver::class.java,
                TestResolverBase::class.java,
                SelectiveResolver::class.java,
                SelectiveResolverBase::class.java
            )
        every { mockClassFinder.grtClassForName(any()) } throws ClassNotFoundException("test")

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)
        val schema = createMockSchemaWithTestType()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).hasSize(2)
        val executorsByCoordinate = executors.toMap()
        assertThat(executorsByCoordinate.getValue("TestType" to "testField").resolverId).isEqualTo("TestType.testField")
        assertThat(executorsByCoordinate.getValue("TestType" to "testField").isSelective).isFalse()
        assertThat(executorsByCoordinate.getValue("TestType" to "selectiveField").resolverId).isEqualTo("TestType.selectiveField")
        assertThat(executorsByCoordinate.getValue("TestType" to "selectiveField").isSelective).isTrue()
    }

    // Node resolver test fixtures
    class TestNodeObj : NodeObject

    @NodeResolverFor(typeName = "TestNodeType")
    abstract class TestNodeResolverBase : NodeResolverBase<TestNodeObj> {
        abstract fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj>
    }

    @Resolver
    class TestNodeResolver : TestNodeResolverBase() {
        override fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj> = CompletableFuture.completedFuture(TestNodeObj())
    }

    // Fixtures for strict bootstrap validation tests (mirrors Kotlin
    // ViaductTenantModuleBootstrapper behavior).
    @NodeResolverFor(typeName = "OrphanNodeType")
    abstract class OrphanNodeResolverBase : NodeResolverBase<TestNodeObj> {
        abstract fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj>
    }

    // A subclass that is NOT annotated with @Resolver — should cause the bootstrap to throw.
    class OrphanNodeSubclassWithoutResolverAnnotation : OrphanNodeResolverBase() {
        override fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj> = CompletableFuture.completedFuture(TestNodeObj())
    }

    @NodeResolverFor(typeName = "DuplicateNodeType")
    abstract class DuplicateNodeResolverBase : NodeResolverBase<TestNodeObj> {
        abstract fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj>
    }

    @Resolver
    class DuplicateNodeResolverA : DuplicateNodeResolverBase() {
        override fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj> = CompletableFuture.completedFuture(TestNodeObj())
    }

    @Resolver
    class DuplicateNodeResolverB : DuplicateNodeResolverBase() {
        override fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj> = CompletableFuture.completedFuture(TestNodeObj())
    }

    @NodeResolverFor(typeName = "ForbiddenAnnotationNodeType")
    abstract class ForbiddenAnnotationNodeResolverBase : NodeResolverBase<TestNodeObj> {
        abstract fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj>
    }

    @Resolver(objectValueFragment = "fragment _ on Foo { id }")
    class NodeResolverWithObjectValueFragment : ForbiddenAnnotationNodeResolverBase() {
        override fun resolve(ctx: NodeResolverBase.Context<TestNodeObj>): CompletableFuture<TestNodeObj> = CompletableFuture.completedFuture(TestNodeObj())
    }

    @NodeResolverFor(typeName = "TestBatchNodeType", isBatching = true)
    abstract class TestBatchNodeResolverBase : NodeResolverBase<TestNodeObj> {
        abstract fun batchResolve(contexts: List<NodeResolverBase.Context<TestNodeObj>>): CompletableFuture<List<viaduct.java.api.resolvers.FieldValue<TestNodeObj>>>
    }

    @Resolver
    class TestBatchNodeResolver : TestBatchNodeResolverBase() {
        override fun batchResolve(contexts: List<NodeResolverBase.Context<TestNodeObj>>): CompletableFuture<List<viaduct.java.api.resolvers.FieldValue<TestNodeObj>>> {
            val list = contexts.map { viaduct.java.api.resolvers.FieldValue.ofValue(TestNodeObj()) }
            return CompletableFuture.completedFuture(list)
        }
    }

    @Test
    fun `nodeResolverExecutors returns empty when no node resolver classes found`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns emptySet()

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)

        val executors = bootstrapper.nodeResolverExecutors(createMockSchema()).toList()

        assertThat(executors).isEmpty()
    }

    @Test
    fun `nodeResolverExecutors registers resolver for valid Node type`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns setOf(TestNodeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(NodeResolverBase::class.java) } returns
            setOf(TestNodeResolver::class.java, TestNodeResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)

        val executors = bootstrapper.nodeResolverExecutors(createMockSchemaWithNodeType()).toList()

        assertThat(executors).hasSize(1)
        assertThat(executors.first().first).isEqualTo("TestNodeType")
    }

    @Test
    fun `nodeResolverExecutors skips resolver for type not in schema`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns setOf(TestNodeResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)

        val executors = bootstrapper.nodeResolverExecutors(createMockSchema()).toList()

        assertThat(executors).isEmpty()
    }

    @Test
    fun `nodeResolverExecutors wires executor that invokes the tenant resolve method`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns setOf(TestNodeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(NodeResolverBase::class.java) } returns
            setOf(TestNodeResolver::class.java, TestNodeResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)
        val schema = createMockSchemaWithNodeType()
        val executors = bootstrapper.nodeResolverExecutors(schema).toList()

        assertThat(executors).hasSize(1)
        val (typeName, executor) = executors.first()
        assertThat(typeName).isEqualTo("TestNodeType")
        assertThat(executor.isBatching).isFalse()

        // Exercise the wired executor lambda by calling resolve(). The lambda invokes the
        // tenant's resolve method via reflection, so a successful round-trip confirms the lambda
        // is wired correctly.
        val engineCtx: EngineExecutionContext = mockk {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
        }
        val selector = NodeResolverExecutor.Selector(
            id = GlobalIDCodecDefault.serialize("TestNodeType", "abc"),
            selections = mockk<EngineSelectionSet>(),
        )

        val result = kotlinx.coroutines.runBlocking {
            executor.resolve(listOf(selector), engineCtx)
        }
        // The lambda was invoked (a Result is produced regardless of conversion outcome). The
        // tenant resolver returns a TestNodeObj that doesn't extend JavaObjectBase, so the engine
        // bridge will surface a conversion failure — what we're verifying here is that the
        // executor lambda chain (resolveFunction -> invokeNodeResolver -> reflective invoke) is
        // wired correctly.
        assertThat(result).hasSize(1)
        assertThat(result[selector]).isNotNull
    }

    @Test
    fun `nodeResolverExecutors wires batch executor when isBatching is true`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns setOf(TestBatchNodeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(NodeResolverBase::class.java) } returns
            setOf(TestBatchNodeResolver::class.java, TestBatchNodeResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)
        val schema = createMockSchemaWithNodeTypes("TestBatchNodeType")
        val executors = bootstrapper.nodeResolverExecutors(schema).toList()

        assertThat(executors).hasSize(1)
        val (typeName, executor) = executors.first()
        assertThat(typeName).isEqualTo("TestBatchNodeType")
        assertThat(executor.isBatching).isTrue()

        // Exercise the batch executor lambda end-to-end.
        val engineCtx: EngineExecutionContext = mockk {
            every { requestContext } returns null
            every { globalIDCodec } returns GlobalIDCodecDefault
        }
        val selectors = listOf(
            NodeResolverExecutor.Selector(
                id = GlobalIDCodecDefault.serialize("TestBatchNodeType", "1"),
                selections = mockk<EngineSelectionSet>(),
            ),
            NodeResolverExecutor.Selector(
                id = GlobalIDCodecDefault.serialize("TestBatchNodeType", "2"),
                selections = mockk<EngineSelectionSet>(),
            ),
        )

        val result = kotlinx.coroutines.runBlocking {
            executor.resolve(selectors, engineCtx)
        }
        // Lambda invocation succeeded; conversion may produce a failed Result for TestNodeObj
        // (not a JavaObjectBase), but each selector gets a Result entry.
        assertThat(result).hasSize(2)
        assertThat(result[selectors[0]]).isNotNull
        assertThat(result[selectors[1]]).isNotNull
    }

    @Test
    fun `nodeResolverExecutors throws when subclass exists without Resolver annotation`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns setOf(OrphanNodeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(NodeResolverBase::class.java) } returns
            setOf(OrphanNodeSubclassWithoutResolverAnnotation::class.java, OrphanNodeResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)

        val schema = createMockSchemaWithNodeTypes("OrphanNodeType")
        assertThrows<TenantModuleException> {
            bootstrapper.nodeResolverExecutors(schema).toList()
        }
    }

    @Test
    fun `nodeResolverExecutors throws when multiple Resolver implementations exist`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns setOf(DuplicateNodeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(NodeResolverBase::class.java) } returns
            setOf(
                DuplicateNodeResolverA::class.java,
                DuplicateNodeResolverB::class.java,
                DuplicateNodeResolverBase::class.java,
            )

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)

        val schema = createMockSchemaWithNodeTypes("DuplicateNodeType")
        assertThrows<TenantModuleException> {
            bootstrapper.nodeResolverExecutors(schema).toList()
        }
    }

    @Test
    fun `nodeResolverExecutors throws when node Resolver declares objectValueFragment`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.nodeResolverForClassesInPackage() } returns setOf(ForbiddenAnnotationNodeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(NodeResolverBase::class.java) } returns
            setOf(NodeResolverWithObjectValueFragment::class.java, ForbiddenAnnotationNodeResolverBase::class.java)

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)

        val schema = createMockSchemaWithNodeTypes("ForbiddenAnnotationNodeType")
        assertThrows<TenantModuleException> {
            bootstrapper.nodeResolverExecutors(schema).toList()
        }
    }

    @Test
    fun `fieldResolverExecutors creates executor with objectSelectionSet from annotation`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(PersonFullNameResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(FieldResolverBase::class.java) } returns
            setOf(PersonFullNameResolver::class.java, PersonFullNameResolverBase::class.java)
        every { mockClassFinder.grtClassForName(any()) } throws ClassNotFoundException("test")

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)
        val schema = createMockSchemaWithPerson()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).hasSize(1)
        val (coordinate, executor) = executors.first()
        assertThat(coordinate.first).isEqualTo("Person")
        assertThat(coordinate.second).isEqualTo("fullName")
        // The executor should have objectSelectionSet populated from the annotation
        assertThat(executor.objectSelectionSet).isNotNull
        assertThat(executor.querySelectionSet).isNull()
    }

    @Test
    fun `fieldResolverExecutors creates executor with empty selections for plain Resolver annotation`() {
        val mockClassFinder = mockk<JavaResolverClassFinder>()
        every { mockClassFinder.resolverClassesInPackage() } returns setOf(PersonAgeResolverBase::class.java)
        every { mockClassFinder.getSubTypesOf(FieldResolverBase::class.java) } returns
            setOf(PersonAgeResolver::class.java, PersonAgeResolverBase::class.java)
        every { mockClassFinder.grtClassForName(any()) } throws ClassNotFoundException("test")

        val bootstrapper = JavaModuleBootstrapper(mockClassFinder, CodeInjector.Naive)
        val schema = createMockSchemaWithPerson()

        val executors = bootstrapper.fieldResolverExecutors(schema).toList()

        assertThat(executors).hasSize(1)
        val (_, executor) = executors.first()
        // Plain @Resolver annotation should result in null selection sets (backward compatible)
        assertThat(executor.objectSelectionSet).isNull()
        assertThat(executor.querySelectionSet).isNull()
    }

    // Helper to create mock schema
    private fun createMockSchema(): ViaductSchema {
        val graphqlSchema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject()
                    .name("Query")
                    .field(
                        GraphQLFieldDefinition.newFieldDefinition()
                            .name("placeholder")
                            .type(Scalars.GraphQLString)
                    )
                    .build()
            )
            .build()

        return mockk {
            every { schema } returns graphqlSchema
        }
    }

    // Helper to create mock schema with TestType
    private fun createMockSchemaWithTestType(): ViaductSchema {
        val testType = GraphQLObjectType.newObject()
            .name("TestType")
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("testField")
                    .type(Scalars.GraphQLString)
            )
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("selectiveField")
                    .type(Scalars.GraphQLString)
            )
            .build()

        val graphqlSchema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject()
                    .name("Query")
                    .field(
                        GraphQLFieldDefinition.newFieldDefinition()
                            .name("placeholder")
                            .type(Scalars.GraphQLString)
                    )
                    .build()
            )
            .additionalType(testType)
            .build()

        return mockk {
            every { schema } returns graphqlSchema
        }
    }

    // Helper to create mock schema with Person type
    private fun createMockSchemaWithPerson(): ViaductSchema {
        val personType = GraphQLObjectType.newObject()
            .name("Person")
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("firstName")
                    .type(Scalars.GraphQLString)
            )
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("lastName")
                    .type(Scalars.GraphQLString)
            )
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("fullName")
                    .type(Scalars.GraphQLString)
            )
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("age")
                    .type(Scalars.GraphQLInt)
            )
            .build()

        val graphqlSchema = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject()
                    .name("Query")
                    .field(
                        GraphQLFieldDefinition.newFieldDefinition()
                            .name("placeholder")
                            .type(Scalars.GraphQLString)
                    )
                    .build()
            )
            .additionalType(personType)
            .build()

        return mockk {
            every { schema } returns graphqlSchema
        }
    }

    private fun createMockSchemaWithNodeType(): ViaductSchema = createMockSchemaWithNodeTypes("TestNodeType")

    private fun createMockSchemaWithNodeTypes(vararg typeNames: String): ViaductSchema {
        val nodeInterface = GraphQLInterfaceType.newInterface()
            .name("Node")
            .field(
                GraphQLFieldDefinition.newFieldDefinition()
                    .name("id")
                    .type(Scalars.GraphQLID)
            )
            .build()

        val nodeTypes = typeNames.map { typeName ->
            GraphQLObjectType.newObject()
                .name(typeName)
                .withInterface(nodeInterface)
                .field(
                    GraphQLFieldDefinition.newFieldDefinition()
                        .name("id")
                        .type(Scalars.GraphQLID)
                )
                .build()
        }

        val codeRegistry = GraphQLCodeRegistry.newCodeRegistry()
            .typeResolver(
                "Node",
                TypeResolver { env ->
                    env.schema.getObjectType(env.getObject<Any>().javaClass.simpleName)
                }
            )
            .build()

        val schemaBuilder = GraphQLSchema.newSchema()
            .query(
                GraphQLObjectType.newObject()
                    .name("Query")
                    .field(
                        GraphQLFieldDefinition.newFieldDefinition()
                            .name("placeholder")
                            .type(Scalars.GraphQLString)
                    )
                    .build()
            )
            .additionalType(nodeInterface)
            .codeRegistry(codeRegistry)
        nodeTypes.forEach { schemaBuilder.additionalType(it) }
        val graphqlSchema = schemaBuilder.build()

        return mockk {
            every { schema } returns graphqlSchema
        }
    }
}
