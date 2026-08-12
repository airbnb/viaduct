@file:Suppress("ForbiddenImport")

package viaduct.arbitrary.graphql

import graphql.schema.GraphQLObjectType
import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.CompoundingWeight.Companion.Never
import viaduct.arbitrary.common.CompoundingWeight.Companion.Once
import viaduct.arbitrary.common.Config
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.RootFieldReference
import viaduct.engine.api.VariablesResolver as EngineVariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.service.api.ExecutionInput

class ViaductDescriptorTest {
    @Test
    fun `RequiredSelectionSet descriptor includes variables resolver required selection set`() {
        val nestedRss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "b"),
            variablesResolvers = emptyList(),
            forChecker = false,
        )
        val variablesResolver = object : EngineVariablesResolver {
            override val variableNames: Set<String> = setOf("id")
            override val requiredSelectionSet: RequiredSelectionSet = nestedRss

            override suspend fun resolve(
                ctx: EngineVariablesResolver.ResolveCtx,
                context: EngineExecutionContext,
            ): Map<String, Any?> = emptyMap()
        }
        val rss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "a(id: \$id)"),
            variablesResolvers = listOf(variablesResolver),
            forChecker = false,
        )

        val dump = rss.describe()

        assertEquals("Query", dump.typeName)
        assertEquals(listOf("id"), dump.variablesResolvers.single().variableNames.sorted())
        assertEquals("Query", dump.variablesResolvers.single().requiredSelectionSet?.typeName)
        val rendered = dump.toString()
        assertTrue(rendered.contains("type: Query"))
        assertTrue(rendered.contains("variablesResolvers:"))
        assertTrue(rendered.contains("- variables: [id]"))
        assertTrue(rendered.contains("requiredSelectionSet:"))
    }

    @Test
    fun `VariablesResolver descriptor includes nested required selection set`() {
        val nestedRss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "b"),
            variablesResolvers = emptyList(),
            forChecker = false,
        )
        val variablesResolver = object : EngineVariablesResolver {
            override val variableNames: Set<String> = setOf("value")
            override val requiredSelectionSet: RequiredSelectionSet = nestedRss

            override suspend fun resolve(
                ctx: EngineVariablesResolver.ResolveCtx,
                context: EngineExecutionContext,
            ): Map<String, Any?> = emptyMap()
        }

        val dump = variablesResolver.describe()

        assertEquals(setOf("value"), dump.variableNames)
        assertEquals("Query", dump.requiredSelectionSet?.typeName)
        val rendered = dump.toString()
        assertTrue(rendered.contains("- variables: [value]"))
        assertTrue(rendered.contains("class:"))
        assertTrue(rendered.contains("requiredSelectionSet:"))
    }

    @Test
    fun `descriptor includes root field reference parameters without resolving it`(): Unit =
        runBlocking {
            val schema = """
                type Foo { x: Int }
                extend type Query { factory: Foo }
            """.asViaductSchema
            val reference = TestRootFieldReference(
                rootFieldPath = listOf("_factories", "foo", "create"),
                type = schema.schema.getObjectType("Foo"),
                args = mapOf(
                    "input" to mapOf(
                        "enabled" to true,
                        "limit" to 3,
                    )
                ),
            )
            val variablesResolver = VariablesResolver.Instrumented(
                object : EngineVariablesResolver {
                    override val variableNames: Set<String> = setOf("reference")

                    override suspend fun resolve(
                        ctx: EngineVariablesResolver.ResolveCtx,
                        context: EngineExecutionContext,
                    ): Map<String, Any?> = mapOf("reference" to reference)
                }
            )
            variablesResolver.resolve(
                EngineVariablesResolver.ResolveCtx(
                    objectData = syncObjectData(schema.schema.queryType, emptyMap()),
                    arguments = emptyMap(),
                ),
                fakeEngineExecutionContext(),
            )

            val rendered = variablesResolver.describe().toString()

            assertTrue(rendered.contains("TestRootFieldReference {"))
            assertTrue(rendered.contains("rootFieldPath:"))
            assertTrue(rendered.contains("\"_factories\""))
            assertTrue(rendered.contains("\"foo\""))
            assertTrue(rendered.contains("\"create\""))
            assertTrue(rendered.contains("type: Foo"))
            assertTrue(rendered.contains("args:"))
            assertTrue(rendered.contains("\"input\":"))
            assertTrue(rendered.contains("\"enabled\": true"))
            assertTrue(rendered.contains("\"limit\": 3"))
            assertFalse(rendered.contains("<async>"))
        }

    @Test
    fun `RequiredSelectionSet descriptor includes variables resolver calls when resolver is instrumented`(): Unit =
        runBlocking {
            val schema = "extend type Query { source: String }".asViaductSchema
            val variablesResolver = VariablesResolver.Instrumented(
                object : EngineVariablesResolver {
                    override val variableNames: Set<String> = setOf("id")

                    override suspend fun resolve(
                        ctx: EngineVariablesResolver.ResolveCtx,
                        context: EngineExecutionContext,
                    ): Map<String, Any?> = mapOf("id" to "resolved-${ctx.arguments["id"]}")
                }
            )
            variablesResolver.resolve(
                EngineVariablesResolver.ResolveCtx(
                    objectData = syncObjectData(
                        schema.schema.queryType,
                        mapOf("source" to "root")
                    ),
                    arguments = mapOf("id" to 7),
                ),
                fakeEngineExecutionContext(),
            )
            val rss = RequiredSelectionSet(
                selections = SelectionsParser.parse("Query", "source"),
                variablesResolvers = listOf(variablesResolver),
                forChecker = false,
            )

            val rendered = rss.describe().toString()

            assertTrue(rendered.contains("- variables: [id]"))
            assertTrue(rendered.contains("calls:"))
            assertTrue(rendered.contains("- #1"))
            assertTrue(rendered.contains("request:"))
            assertTrue(rendered.contains("arguments:"))
            assertTrue(rendered.contains("\"id\": 7"))
            assertTrue(rendered.contains("objectData:"))
            assertTrue(rendered.contains("source: \"root\""))
            assertTrue(rendered.contains("response:"))
            assertTrue(rendered.contains("success:"))
            assertTrue(rendered.contains("\"id\": \"resolved-7\""))
            assertTrue(rendered.contains("time:"))
        }

    @Test
    fun `required selection set descriptor stops at cycles`() {
        lateinit var rss: RequiredSelectionSet
        val variablesResolver = object : EngineVariablesResolver {
            override val variableNames: Set<String> = setOf("id")
            override val requiredSelectionSet: RequiredSelectionSet
                get() = rss

            override suspend fun resolve(
                ctx: EngineVariablesResolver.ResolveCtx,
                context: EngineExecutionContext,
            ): Map<String, Any?> = emptyMap()
        }
        rss = RequiredSelectionSet(
            selections = SelectionsParser.parse("Query", "a(id: \$id)"),
            variablesResolvers = listOf(variablesResolver),
            forChecker = false,
        )

        val rendered = rss.describe().toString()

        assertTrue(rendered.contains("selections: <already described>"))
    }

    @Test
    fun `generated viaduct descriptor includes schema and resolver configuration`(): Unit =
        runBlocking {
            val schema = ViaductSchema(
                """
                directive @resolver(isSelective: Boolean! = false) on FIELD_DEFINITION | OBJECT
                type Foo { x: Int, y: Int }
                type Query { foo: Foo @resolver(isSelective: true) }
                """.asSchema
            )
            val cfg = Config.default +
                (RequiredSelectionSetWeight to Once) +
                (FieldCheckerWeight to 0.0) +
                (TypeCheckerWeight to 0.0) +
                (FieldResolverExceptionWeight to 0.0) +
                (NodeResolverExceptionWeight to 0.0) +
                (VariablesResolverExceptionWeight to 0.0)

            val viaduct = Arb.viaduct(schema, cfg).next(RandomSource.seeded(0))
            val rendered = viaduct.dump()

            assertTrue(rendered.contains("Viaduct Descriptor"))
            assertTrue(rendered.contains("schemas:"))
            assertTrue(rendered.contains("type Foo"))
            assertTrue(rendered.contains("field resolvers:"))
            assertTrue(rendered.contains("Query.foo"))
            assertTrue(rendered.contains("isSelective: true"))
            assertTrue(rendered.contains("objectSelectionSet:"))
            assertTrue(rendered.contains("querySelectionSet:"))
            assertFalse(rendered.contains("calls:"))
        }

    @Test
    fun `generated viaduct descriptor includes field resolver calls when factory is instrumented`(): Unit =
        runBlocking {
            val schema = """
                type Foo { x: Int!, y: Int }
                extend type Query { foo(arg: Int): Foo! @resolver }
            """.asViaductSchema
            val fieldResolverFactory = FieldResolver.Factory.Instrumented()
            val cfg = dumpCallConfig + (FieldResolverFactory to fieldResolverFactory)
            val viaduct = Arb.viaduct(schema, cfg).next(RandomSource.seeded(0))

            val beforeExecution = viaduct.dump()
            assertTrue(beforeExecution.contains("calls: <none>"))

            val result = viaduct.execute(
                ExecutionInput.create(
                    operationText = "{ foo(arg: 7) { x } }",
                    requestContext = Any(),
                )
            )

            assertTrue(result.errors.isEmpty(), result.toSpecification().toString())
            val rendered = viaduct.dump()
            assertTrue(rendered.contains("calls:"))
            assertTrue(rendered.contains("- #1"))
            assertTrue(rendered.contains("request:"))
            assertTrue(rendered.contains("arguments:"))
            assertTrue(rendered.contains("\"arg\": 7"))
            assertTrue(rendered.contains("selections:"))
            assertTrue(rendered.contains("x"))
            assertTrue(rendered.contains("response:"))
            assertTrue(rendered.contains("success:"))
            assertTrue(rendered.contains("Foo {"))
            assertTrue(rendered.contains("time:"))
        }

    @Test
    fun `generated viaduct descriptor includes field resolver failures when factory is instrumented`(): Unit =
        runBlocking {
            val schema = "extend type Query { value: Int @resolver }".asViaductSchema
            val fieldResolverFactory = FieldResolver.Factory.Instrumented()
            val cfg = dumpCallConfig +
                (FieldResolverFactory to fieldResolverFactory) +
                (FieldResolverExceptionWeight to 1.0)
            val viaduct = Arb.viaduct(schema, cfg).next(RandomSource.seeded(0))

            viaduct.execute(ExecutionInput.create(operationText = "{ value }", requestContext = Any()))

            val rendered = viaduct.dump()
            assertTrue(rendered.contains("calls:"))
            assertTrue(rendered.contains("failure:"))
            assertTrue(rendered.contains("class: viaduct.arbitrary.graphql.ResolverException"))
            assertTrue(rendered.contains("message: This is a synthetic ResolverException"))
        }

    @Test
    fun `generated viaduct descriptor includes checker calls when factory is instrumented`(): Unit =
        runBlocking {
            val schema = "extend type Query { value: Int @resolver }".asViaductSchema
            val checkerExecutorFactory = CheckerExecutor.Factory.Instrumented()
            val cfg = dumpCallConfig +
                (FieldCheckerWeight to 1.0) +
                (CheckerExecutorFactory to checkerExecutorFactory)
            val viaduct = Arb.viaduct(schema, cfg).next(RandomSource.seeded(0))

            val beforeExecution = viaduct.dump()
            assertTrue(beforeExecution.contains("calls: <none>"))

            val result = viaduct.execute(
                ExecutionInput.create(
                    operationText = "{ value }",
                    requestContext = Any(),
                )
            )

            assertTrue(result.errors.isEmpty(), result.toSpecification().toString())
            val rendered = viaduct.dump()
            assertTrue(rendered.contains("checkers:"))
            assertTrue(rendered.contains("field Query.value"))
            assertTrue(rendered.contains("calls:"))
            assertTrue(rendered.contains("- #1"))
            assertTrue(rendered.contains("checkerType: FIELD"))
            assertTrue(rendered.contains("arguments:"))
            assertTrue(rendered.contains("objectDataMap:"))
            assertTrue(rendered.contains("response:"))
            assertTrue(rendered.contains("success:"))
            assertTrue(rendered.contains("Success"))
            assertTrue(rendered.contains("time:"))
        }

    private val dumpCallConfig = Config.default +
        (RequiredSelectionSetWeight to Never) +
        (ExplicitNullValueWeight to 0.0) +
        (FieldCheckerWeight to 0.0) +
        (TypeCheckerWeight to 0.0) +
        (FieldResolverExceptionWeight to 0.0) +
        (NodeResolverExceptionWeight to 0.0) +
        (VariablesResolverExceptionWeight to 0.0) +
        (CheckerExceptionWeight to 0.0) +
        (CheckerErrorWeight to 0.0)

    private class TestRootFieldReference(
        override val rootFieldPath: List<String>,
        override val type: GraphQLObjectType,
        override val args: Map<String, Any?>,
    ) : RootFieldReference, EngineObjectData {
        override suspend fun fetch(selection: String): Any? = error("Reference must not be resolved")

        override suspend fun fetchOrNull(selection: String): Any? = error("Reference must not be resolved")

        override suspend fun fetchSelections(): Iterable<String> = error("Reference must not be resolved")
    }

    private fun syncObjectData(
        objectType: GraphQLObjectType,
        data: Map<String, Any?>,
    ): EngineObjectData.Sync =
        object : EngineObjectData.Sync {
            override val type: GraphQLObjectType = objectType

            override suspend fun fetch(selection: String): Any? = get(selection)

            override suspend fun fetchOrNull(selection: String): Any? = getOrNull(selection)

            override suspend fun fetchSelections(): Iterable<String> = getSelections()

            override fun get(selection: String): Any? {
                check(isPresent(selection)) { "Unset field $selection" }
                return data[selection]
            }

            override fun getOrNull(selection: String): Any? = if (isPresent(selection)) data[selection] else null

            override fun isPresent(selection: String): Boolean = data.containsKey(selection)

            override fun getSelections(): Iterable<String> = data.keys
        }

    private fun fakeEngineExecutionContext(): EngineExecutionContext =
        Proxy.newProxyInstance(
            EngineExecutionContext::class.java.classLoader,
            arrayOf(EngineExecutionContext::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "fakeEngineExecutionContext"
                "hashCode" -> 0
                "equals" -> proxy === args?.firstOrNull()
                else -> error("Unexpected EngineExecutionContext access: ${method.name}")
            }
        } as EngineExecutionContext
}
