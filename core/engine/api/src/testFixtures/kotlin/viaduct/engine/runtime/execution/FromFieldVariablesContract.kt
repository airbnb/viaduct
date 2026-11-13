package viaduct.engine.runtime.execution

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.FromArgumentVariable
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.FromQueryFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.SelectionSetVariable
import viaduct.engine.api.VariableCycleException
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.mocks.EngineTestModule
import viaduct.engine.api.mocks.FieldUnbatchedResolverFn
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockTenantModuleDSL
import viaduct.engine.api.mocks.createEngineObjectData
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.getAs
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.graphql.test.assertJson

interface FromFieldVariablesContract : QueryExecutionContract {
    @Test
    fun `field variables use source response keys for typename through named fragments`() {
        fun selections(type: String) =
            """
            fragment Main on $type {
              ...Inputs
              plain: echo(value: ${'$'}typename)
              aliased: echo(value: ${'$'}alias)
            }
            fragment Inputs on $type { __typename kind: __typename }
            """.trimIndent()

        EngineTestModule(
            """
            type Obj { objectResult: String queryResult: String echo(value: String): String }
            extend type Query { obj: Obj echo(value: String): String }
            """.trimIndent(),
        ) {
            field("Query" to "obj") {
                resolver { fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Obj"), emptyMap()) } }
            }
            for (type in listOf("Obj", "Query")) {
                field(type to "echo") { resolver { fn { args, _, _, _, _ -> args["value"] } } }
            }
            fieldWithFromFieldVariables(
                coord = "Obj" to "objectResult",
                objectSelectionsText = selections("Obj"),
                variables = listOf(
                    FromObjectFieldVariable("typename", "__typename"),
                    FromObjectFieldVariable("alias", "kind"),
                ),
            ) { _, obj, _, _, _ ->
                "${obj.getAs<String>("plain")}/${obj.getAs<String>("aliased")}"
            }
            fieldWithFromFieldVariables(
                coord = "Obj" to "queryResult",
                querySelectionsText = selections("Query"),
                variables = listOf(
                    FromQueryFieldVariable("typename", "__typename"),
                    FromQueryFieldVariable("alias", "kind"),
                ),
            ) { _, _, query, _, _ ->
                "${query.getAs<String>("plain")}/${query.getAs<String>("aliased")}"
            }
        }.runContractTest {
            withTimeout("{ obj { objectResult queryResult } }", timeoutMillis = 5_000)
                .assertJson("{data: {obj: {objectResult: \"Obj/Obj\", queryResult: \"Query/Query\"}}}")
        }
    }

    @Test
    fun `field variables resolve aliases through nested named fragments`() {
        fun selections(
            left: String,
            right: String
        ) = """
            fragment Main on Query {
              ...Inputs @include(if: ${'$'}enabled)
              use(x: ${'$'}$left, y: ${'$'}$right)
            }
            fragment Inputs on Query { ...Source }
            fragment Source on Query { ${left}Source: source(x: 9) ${right}Source: source(x: 4) }
            """.trimIndent()

        EngineTestModule(
            "extend type Query { source(x: Int!): Int use(x: Int, y: Int): Int! result(enabled: Boolean!): Int! }",
        ) {
            field("Query" to "source") { resolver { fn { args, _, _, _, _ -> args.getValue("x") } } }
            field("Query" to "use") {
                resolver { fn { args, _, _, _, _ -> (args["x"] as Int? ?: -1) + (args["y"] as Int? ?: -1) } }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = selections("object", "otherObject"),
                querySelectionsText = selections("query", "otherQuery"),
                variables = listOf(
                    FromArgumentVariable("enabled", "enabled"),
                    FromObjectFieldVariable("object", "objectSource"),
                    FromObjectFieldVariable("otherObject", "otherObjectSource"),
                    FromQueryFieldVariable("query", "querySource"),
                    FromQueryFieldVariable("otherQuery", "otherQuerySource"),
                ),
            ) { _, obj, query, _, _ ->
                obj.getAs<Int>("use") + query.getAs<Int>("use")
            }
        }.runContractTest {
            withTimeout("{ enabled: result(enabled: true) disabled: result(enabled: false) }", timeoutMillis = 5_000)
                .assertJson("{data: {enabled: 26, disabled: -4}}")
        }
    }

    fun invalidVariableName(error: Throwable): String

    fun isRequiredSelectionsCycle(error: Throwable): Boolean

    fun assertIncompatibleVariableError(
        error: Throwable,
        variableName: String,
        providerPath: String
    ) {
        assertEquals(variableName, invalidVariableName(error.unwrap()))
    }

    fun assertRequiredSelectionsCycleError(error: Throwable) {
        assertTrue(isRequiredSelectionsCycle(error.unwrap()))
    }

    @Test
    fun `from object field -- simple`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }
    }

    @Test
    fun `from object field -- provider prerequisite shares a branch with variable use`() {
        EngineTestModule(
            """
                type Listing { value: Int consume(ids: Int): Int }
                extend type Query { listing: Listing ids: Int result: Int }
            """.trimIndent()
        ) {
            field("Query" to "listing") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Listing"), mapOf("value" to 9))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "ids",
                objectSelectionsText = "listing { value }",
            ) { _, obj, _, _, _ ->
                obj.fetchAs<EngineObjectData>("listing").fetchAs<Int>("value")
            }
            field("Listing" to "consume") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("ids") } }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "ids listing { consume(ids: \$ids) }",
                variables = listOf(FromObjectFieldVariable("ids", "ids")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<EngineObjectData>("listing").fetchAs<Int>("consume")
            }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 9}}")
        }
    }

    @Test
    fun `from object field -- shared branch waits for a suspended provider`() {
        val consumed = ConcurrentLinkedQueue<Int>()
        EngineTestModule(
            """
                type Branch { value: Int! consume(value: Int!): Int! }
                extend type Query { branch: Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "branch") {
                resolver { fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Branch"), emptyMap()) } }
            }
            field("Branch" to "value") {
                resolver {
                    fn { _, _, _, _, _ ->
                        yield()
                        9
                    }
                }
            }
            field("Branch" to "consume") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val value = args.getAs<Int>("value")
                        consumed.add(value)
                        value * 2
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "branch { consume(value: \$value) value }",
                variables = listOf(FromObjectFieldVariable("value", "branch.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("branch").fetchAs<Int>("consume") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 18}}")
        }
        assertEquals(listOf(9), consumed.toList())
    }

    @Test
    fun `from object field -- shared branch under a non-root object can be source supplied`() {
        EngineTestModule(
            """
                type Branch { value: Int! consume(value: Int!): Int! }
                type Parent { shared: Branch! result: Int! }
                extend type Query { parent: Parent! }
            """.trimIndent()
        ) {
            field("Query" to "parent") {
                resolver {
                    fn { _, _, _, _, _ ->
                        val branch = createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to 23))
                        createEngineObjectData(schema.schema.getObjectType("Parent"), mapOf("shared" to branch))
                    }
                }
            }
            field("Branch" to "consume") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("value") * 2 } }
            }
            fieldWithFromFieldVariables(
                coord = "Parent" to "result",
                objectSelectionsText = "shared { consume(value: \$value) value }",
                variables = listOf(FromObjectFieldVariable("value", "shared.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("shared").fetchAs<Int>("consume") }
        }.runContractTest {
            withTimeout("{ parent { result } }", timeoutMillis = 5_000).assertJson("{data: {parent: {result: 46}}}")
        }
    }

    @Test
    fun `from object field -- shared branch variables resolve through parent demand`() {
        val consumed = ConcurrentLinkedQueue<Pair<Int, Int>>()
        EngineTestModule(
            """
                type Shared { value: Int! consume(value: Int!): Int! }
                type Parent { shared: Shared! child: Child! bridge: Int! }
                type Child { parent: Parent @parent result: Int! }
                extend type Query { parents: [Parent!]! }
            """.trimIndent()
        ) {
            field("Query" to "parents") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(3, 7).map { value ->
                            val shared = createEngineObjectData(schema.schema.getObjectType("Shared"), mapOf("value" to value))
                            createEngineObjectData(schema.schema.getObjectType("Parent"), mapOf("shared" to shared))
                        }
                    }
                }
            }
            field("Parent" to "child") {
                resolver { fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Child"), emptyMap()) } }
            }
            fieldWithFromFieldVariables(
                coord = "Shared" to "consume",
                objectSelectionsText = "value",
            ) { args, obj, _, _, _ ->
                yield()
                val ownValue = obj.fetchAs<Int>("value")
                val argument = args.getAs<Int>("value")
                consumed.add(ownValue to argument)
                ownValue * 100 + argument
            }
            fieldWithFromFieldVariables(
                coord = "Parent" to "bridge",
                objectSelectionsText = "shared { consume(value: \$value) value }",
                variables = listOf(FromObjectFieldVariable("value", "shared.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("shared").fetchAs<Int>("consume") }
            fieldWithFromFieldVariables(
                coord = "Child" to "result",
                objectSelectionsText = "parent { bridge }",
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("bridge") }
        }.runContractTest {
            withTimeout("{ parents { child { result } } }", timeoutMillis = 5_000)
                .assertJson("{data: {parents: [{child: {result: 303}}, {child: {result: 707}}]}}")
        }
        assertEquals(mapOf((3 to 3) to 1, (7 to 7) to 1), consumed.groupingBy { it }.eachCount())
    }

    @Test
    fun `from object field -- shared branches can consume each other's independent providers`() {
        val consumed = ConcurrentLinkedQueue<Pair<Int, Int>>()
        EngineTestModule(
            """
                type Branch { value: Int! consume(value: Int!): Int! }
                extend type Query { a: Branch! b: Branch! result: String! }
            """.trimIndent()
        ) {
            for ((name, value) in listOf("a" to 3, "b" to 7)) {
                field("Query" to name) {
                    resolver {
                        fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to value)) }
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Branch" to "consume",
                objectSelectionsText = "value",
            ) { args, obj, _, _, _ ->
                val ownValue = obj.fetchAs<Int>("value")
                val argument = args.getAs<Int>("value")
                consumed.add(ownValue to argument)
                ownValue * 100 + argument
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "a { consume(value: \$right) value } b { consume(value: \$left) value }",
                variables = listOf(
                    FromObjectFieldVariable("left", "a.value"),
                    FromObjectFieldVariable("right", "b.value"),
                ),
            ) { _, obj, _, _, _ ->
                val a = obj.fetchAs<EngineObjectData>("a").fetchAs<Int>("consume")
                val b = obj.fetchAs<EngineObjectData>("b").fetchAs<Int>("consume")
                "$a,$b"
            }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: '307,703'}}")
        }
        assertEquals(mapOf((3 to 7) to 1, (7 to 3) to 1), consumed.groupingBy { it }.eachCount())
    }

    @Test
    fun `from object field -- shared branch keeps argument-distinct provider and consumer objects separate`() {
        EngineTestModule(
            """
                type Branch { value: Int! consume(value: Int!): Int! }
                extend type Query { child(id: Int!): Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "child") {
                resolver {
                    fn { args, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to args.getAs<Int>("id") * 10))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Branch" to "consume",
                objectSelectionsText = "value",
            ) { args, obj, _, _, _ -> obj.fetchAs<Int>("value") * 100 + args.getAs<Int>("value") }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "consumer: child(id: 2) { consume(value: \$value) } provider: child(id: 1) { value }",
                variables = listOf(FromObjectFieldVariable("value", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("consume") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 2010}}")
        }
    }

    @Test
    fun `from object field -- variable on the shared branch field waits for its provider`() {
        val requestedIds = ConcurrentLinkedQueue<Int>()
        EngineTestModule(
            """
                type Branch { value: Int! }
                extend type Query { child(id: Int!): Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "child") {
                resolver {
                    fn { args, _, _, _, _ ->
                        yield()
                        val id = args.getAs<Int>("id")
                        requestedIds.add(id)
                        createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to id * 10))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "consumer: child(id: \$id) { value } provider: child(id: 1) { value }",
                variables = listOf(FromObjectFieldVariable("id", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("value") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 100}}")
        }
        assertEquals(mapOf(1 to 1, 10 to 1), requestedIds.groupingBy { it }.eachCount())
    }

    @Test
    fun `from object field -- variable on the shared branch field can match the provider's argument values`() {
        val requestedIds = ConcurrentLinkedQueue<Int>()
        EngineTestModule(
            """
                type Branch { value: Int! }
                extend type Query { child(id: Int!): Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "child") {
                resolver {
                    fn { args, _, _, _, _ ->
                        yield()
                        val id = args.getAs<Int>("id")
                        requestedIds.add(id)
                        createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to id))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "consumer: child(id: \$id) { value } provider: child(id: 1) { value }",
                variables = listOf(FromObjectFieldVariable("id", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("value") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 1}}")
        }
        assertEquals(setOf(1), requestedIds.toSet())
    }

    @Test
    fun `from object field -- variable on the shared branch field is bound per list occurrence`() {
        val requested = ConcurrentLinkedQueue<Pair<Int, Int>>()
        EngineTestModule(
            """
                type Branch { value: Int! }
                type Parent { seed: Int! child(id: Int!): Branch! result: Int! }
                extend type Query { parents: [Parent!]! }
            """.trimIndent()
        ) {
            field("Query" to "parents") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(1, 2).map { seed ->
                            createEngineObjectData(schema.schema.getObjectType("Parent"), mapOf("seed" to seed))
                        }
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Parent" to "child",
                objectSelectionsText = "seed",
            ) { args, obj, _, _, _ ->
                yield()
                val seed = obj.fetchAs<Int>("seed")
                val id = args.getAs<Int>("id")
                requested.add(seed to id)
                createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to id * 10 + seed))
            }
            fieldWithFromFieldVariables(
                coord = "Parent" to "result",
                objectSelectionsText = "consumer: child(id: \$id) { value } provider: child(id: 1) { value }",
                variables = listOf(FromObjectFieldVariable("id", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("value") }
        }.runContractTest {
            withTimeout("{ parents { result } }", timeoutMillis = 5_000)
                .assertJson("{data: {parents: [{result: 111}, {result: 122}]}}")
        }
        assertEquals(
            mapOf((1 to 1) to 1, (1 to 11) to 1, (2 to 1) to 1, (2 to 12) to 1),
            requested.groupingBy { it }.eachCount()
        )
    }

    @Test
    fun `from object field -- variable on the shared branch field accepts a null provider value`() {
        EngineTestModule(
            """
                type Branch { value: Int }
                extend type Query { child(id: Int): Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "child") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val id = args["id"] as? Int
                        createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to if (id == 1) null else id ?: -1))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "consumer: child(id: \$id) { value } provider: child(id: 1) { value }",
                variables = listOf(FromObjectFieldVariable("id", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("value") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: -1}}")
        }
    }

    @Test
    fun `from object field -- variable on the shared branch field reports a failed provider instead of hanging`() {
        val requestedIds = ConcurrentLinkedQueue<Int>()
        EngineTestModule(
            """
                type Branch { value: Int! }
                extend type Query { child(id: Int!): Branch! result: Int }
            """.trimIndent()
        ) {
            field("Query" to "child") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val id = args.getAs<Int>("id")
                        requestedIds.add(id)
                        check(id != 1) { "provider failed" }
                        createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to id * 10))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "consumer: child(id: \$id) { value } provider: child(id: 1) { value }",
                variables = listOf(FromObjectFieldVariable("id", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("value") }
        }.runContractTest {
            val result = withTimeout("{ result }", timeoutMillis = 5_000)
            assertTrue(result.errors.isNotEmpty(), "A failed provider must surface as an error")
            assertEquals(null, result.getData<Map<String, Any?>>()["result"])
        }
        assertEquals(listOf(1), requestedIds.toList(), "Only the provider may run when its value fails")
    }

    @Test
    fun `from object field -- variable on the shared branch field resolves through parent demand`() {
        EngineTestModule(
            """
                type Branch { value: Int! }
                type Parent { seed: Int! lookup(id: Int!): Branch! bridge: Int! child: Child! }
                type Child { parent: Parent @parent result: Int! }
                extend type Query { parents: [Parent!]! }
            """.trimIndent()
        ) {
            field("Query" to "parents") {
                resolver {
                    fn { _, _, _, _, _ ->
                        listOf(3, 7).map { seed ->
                            createEngineObjectData(schema.schema.getObjectType("Parent"), mapOf("seed" to seed))
                        }
                    }
                }
            }
            field("Parent" to "child") {
                resolver { fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Child"), emptyMap()) } }
            }
            fieldWithFromFieldVariables(
                coord = "Parent" to "lookup",
                objectSelectionsText = "seed",
            ) { args, obj, _, _, _ ->
                yield()
                createEngineObjectData(
                    schema.schema.getObjectType("Branch"),
                    mapOf("value" to args.getAs<Int>("id") * 100 + obj.fetchAs<Int>("seed"))
                )
            }
            fieldWithFromFieldVariables(
                coord = "Parent" to "bridge",
                objectSelectionsText = "consumer: lookup(id: \$v) { value } provider: lookup(id: 1) { value }",
                variables = listOf(FromObjectFieldVariable("v", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("value") }
            fieldWithFromFieldVariables(
                coord = "Child" to "result",
                objectSelectionsText = "parent { bridge }",
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("parent").fetchAs<Int>("bridge") }
        }.runContractTest {
            withTimeout("{ parents { child { result } } }", timeoutMillis = 5_000)
                .assertJson("{data: {parents: [{child: {result: 10303}}, {child: {result: 10707}}]}}")
        }
    }

    @Test
    fun `from object field -- variable on the shared branch field resolves inside a nested input value`() {
        val requestedIds = ConcurrentLinkedQueue<Int>()
        EngineTestModule(
            """
                input Filter { id: Int! }
                type Branch { value: Int! }
                extend type Query { child(filter: Filter!): Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "child") {
                resolver {
                    fn { args, _, _, _, _ ->
                        yield()
                        val id = args.getAs<Map<String, Any>>("filter").getAs<Int>("id")
                        requestedIds.add(id)
                        createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to id * 10))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "consumer: child(filter: {id: \$id}) { value } provider: child(filter: {id: 1}) { value }",
                variables = listOf(FromObjectFieldVariable("id", "provider.value")),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("value") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 100}}")
        }
        assertEquals(mapOf(1 to 1, 10 to 1), requestedIds.groupingBy { it }.eachCount())
    }

    @Test
    fun `from object field -- shared branch field variable and a variable on its leaf resolve together`() {
        EngineTestModule(
            """
                type Branch { value: Int! consume(value: Int!): Int! }
                extend type Query { child(id: Int!): Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "child") {
                resolver {
                    fn { args, _, _, _, _ ->
                        yield()
                        createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to args.getAs<Int>("id") * 10))
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Branch" to "consume",
                objectSelectionsText = "value",
            ) { args, obj, _, _, _ -> obj.fetchAs<Int>("value") * 1000 + args.getAs<Int>("value") }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = "consumer: child(id: \$a) { consume(value: \$b) } first: child(id: 1) { value } second: child(id: 2) { value }",
                variables = listOf(
                    FromObjectFieldVariable("a", "first.value"),
                    FromObjectFieldVariable("b", "second.value"),
                ),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("consumer").fetchAs<Int>("consume") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 100020}}")
        }
    }

    @Test
    fun `from object field -- statically excluded variable use on the shared branch field does not execute`() {
        for (directive in listOf("@skip(if: true)", "@include(if: false)")) {
            val requestedIds = ConcurrentLinkedQueue<Int>()
            EngineTestModule(
                """
                    type Branch { value: Int! }
                    extend type Query { child(id: Int!): Branch! result: Int! }
                """.trimIndent()
            ) {
                field("Query" to "child") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            val id = args.getAs<Int>("id")
                            requestedIds.add(id)
                            createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to id * 10))
                        }
                    }
                }
                fieldWithFromFieldVariables(
                    coord = "Query" to "result",
                    objectSelectionsText = "consumer: child(id: \$id) $directive { value } provider: child(id: 1) { value }",
                    variables = listOf(FromObjectFieldVariable("id", "provider.value")),
                ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("provider").fetchAs<Int>("value") }
            }.runContractTest {
                withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 10}}")
            }
            assertEquals(listOf(1), requestedIds.toList())
        }
    }

    @Test
    fun `from object field -- shared branch resolves chained providers through nested input values`() {
        val consumed = ConcurrentLinkedQueue<List<Int>>()
        EngineTestModule(
            """
                input Config { values: [Int!]! }
                type Branch { value: Int! consume(config: Config!): Int! }
                extend type Query { branch: Branch! result: Int! }
            """.trimIndent()
        ) {
            field("Query" to "branch") {
                resolver {
                    fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to 9)) }
                }
            }
            field("Branch" to "consume") {
                resolver {
                    fn { args, _, _, _, _ ->
                        val values = args.getAs<Map<String, Any>>("config").getAs<List<Int>>("values")
                        consumed.add(values)
                        values.sum()
                    }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "result",
                objectSelectionsText = """
                    branch {
                      last: consume(config: {values: [2, ${'$'}second]})
                      middle: consume(config: {values: [1, ${'$'}first]})
                      value
                    }
                """.trimIndent(),
                variables = listOf(
                    FromObjectFieldVariable("first", "branch.value"),
                    FromObjectFieldVariable("second", "branch.middle"),
                ),
            ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("branch").fetchAs<Int>("last") }
        }.runContractTest {
            withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 12}}")
        }
        assertEquals(listOf(listOf(1, 9), listOf(2, 10)), consumed.toList())
    }

    @Test
    fun `from object field -- shared branch does not invoke statically excluded consumers`() {
        for (directive in listOf("@skip(if: true)", "@include(if: false)")) {
            val consumed = ConcurrentLinkedQueue<Int>()
            EngineTestModule(
                """
                    type Branch { value: Int! consume(value: Int!): Int! }
                    extend type Query { branch: Branch! result: Int! }
                """.trimIndent()
            ) {
                field("Query" to "branch") {
                    resolver {
                        fn { _, _, _, _, _ -> createEngineObjectData(schema.schema.getObjectType("Branch"), mapOf("value" to 9)) }
                    }
                }
                field("Branch" to "consume") {
                    resolver {
                        fn { args, _, _, _, _ ->
                            consumed.add(args.getAs<Int>("value"))
                            0
                        }
                    }
                }
                fieldWithFromFieldVariables(
                    coord = "Query" to "result",
                    objectSelectionsText = "branch { consume(value: \$value) $directive value }",
                    variables = listOf(FromObjectFieldVariable("value", "branch.value")),
                ) { _, obj, _, _, _ -> obj.fetchAs<EngineObjectData>("branch").fetchAs<Int>("value") }
            }.runContractTest {
                withTimeout("{ result }", timeoutMillis = 5_000).assertJson("{data: {result: 9}}")
            }
            assertTrue(consumed.isEmpty(), "Excluded consumer must not execute")
        }
    }

    @Test
    fun `from object field -- variables used by field on non-root object`() {
        EngineTestModule(
            """
                type Obj { x:Int, y(b:Int):Int, z:Int }
                extend type Query { obj:Obj }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Obj" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Obj" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Obj" to "z", 2)
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), emptyMap())
                    }
                }
            }
        }.runContractTest {
            runQuery("{ obj { x } }").assertJson("{data: {obj: {x: 30}}}")
        }
    }

    @Test
    fun `from object field -- selection is field with omitted arg and default value`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z(c:Int = 2):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("c") * 3 }
                }
            }
        }.runContractTest {
            runQuery("{ x }").assertJson("{data: {x: 210}}")
        }
    }

    @Test
    fun `from object field -- selection is field with arg`() {
        EngineTestModule("extend type Query { x:Int!, y(b:Int!):Int!, z(c:Int!):Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z(c:2)",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("c") * 3 }
                }
            }
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }
    }

    @Test
    fun `from object field -- selection is field with omitted argument value`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z(c:Int):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 3
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 2 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> (args["c"] as? Int) ?: -1 }
                }
            }
        }.runContractTest {
            runQuery("{ x }").assertJson("{data: {x: -6}}")
        }
    }

    @Test
    fun `from object field -- selection is aliased`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), myz:z",
                variables = listOf(FromObjectFieldVariable("b", "myz")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }
    }

    @Test
    fun `from object field -- selection is list-valued`() {
        EngineTestModule("extend type Query { x:Int, y(b:[Int]):Int, z:[Int] }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<List<Int>>("b").fold(1) { acc, i -> acc * i }
                    }
                }
            }
            fieldWithValue("Query" to "z", listOf(2, 3, 5))
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }
    }

    @Test
    fun `from object field -- single-field-multiple-variable -- multiple variables on required selection`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int, c:Int):Int, z:Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b, c:\$c), z, w",
                variables = listOf(
                    FromObjectFieldVariable("b", "z"),
                    FromObjectFieldVariable("c", "w"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("b") * args.getAs<Int>("c") * 5
                    }
                }
            }
            fieldWithValue("Query" to "z", 3)
            fieldWithValue("Query" to "w", 2)
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }
    }

    @Test
    fun `from object field -- single-field-multiple-variable -- multiple required selections with variables`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z(c:Int):Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z(c:\$c), w",
                variables = listOf(
                    FromObjectFieldVariable("b", "z"),
                    FromObjectFieldVariable("c", "w"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("c") * 3 }
                }
            }
            fieldWithValue("Query" to "w", 2)
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }
    }

    @Test
    fun `from object field -- selection traverses through object`() {
        EngineTestModule(
            """
                extend type Query { x:Int, y(b:Int):Int, z:Obj }
                type Obj { w:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), mapOf("w" to 2))
                    }
                }
            }
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }
    }

    @Test
    fun `from object field -- selection traverses through null object`() {
        EngineTestModule(
            """
                extend type Query { x:Int, y(b:Int):Int!, z:Obj }
                type Obj { w:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z { w }",
                variables = listOf(FromObjectFieldVariable("b", "z.w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> ((args["b"] as? Int) ?: -1) * 3 }
                }
            }
            fieldWithValue("Query" to "z", null)
        }.runContractTest {
            runQuery("{ x }").assertJson("{data: {x: -15}}")
        }
    }

    @Test
    fun `from object field -- selection traverses through union`() {
        val err = assertThrows<Throwable> {
            EngineTestModule(
                """
                    type Foo { value: String }
                    union Union = Foo
                    extend type Query { string1:String, hasArgs2(x:String):String, iface:Union }
                """.trimIndent()
            ) {
                fieldWithFromFieldVariables(
                    coord = "Query" to "string1",
                    objectSelectionsText = "hasArgs2(x:\$x), iface { ... on Foo { value } }",
                    variables = listOf(FromObjectFieldVariable("x", "iface.value")),
                ) { _, obj, _, _, _ ->
                    obj.fetchAs<String>("hasArgs2") + "."
                }
                field("Query" to "hasArgs2") {
                    resolver {
                        fn { args, _, _, _, _ -> args.getAs<String>("x") + "." }
                    }
                }
                field("Query" to "iface") {
                    resolver {
                        fn { _, _, _, _, _ ->
                            createEngineObjectData(schema.schema.getObjectType("Foo"), mapOf("value" to "FOO"))
                        }
                    }
                }
            }.runContractTest { }
        }.unwrap()

        assertEquals("x", invalidVariableName(err))
    }

    @Test
    fun `invalid from object field -- selection output type is not compatible with variable input type -- nullability mismatch`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x:Int, y(b:Int!):Int!, z:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "y(b:\$b), z",
                    variables = listOf(FromObjectFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> 0 }
                fieldWithValue("Query" to "y", 0)
                fieldWithValue("Query" to "z", null)
            }.runContractTest { }
        }

        assertIncompatibleVariableError(err, "b", "z")
    }

    @Test
    fun `invalid from object field -- selection output type is not compatible with variable input type -- type mismatch`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x:Int, y(b:Int!):Int!, z:String! }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "y(b:\$b), z",
                    variables = listOf(FromObjectFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> 0 }
                fieldWithValue("Query" to "y", 0)
                fieldWithValue("Query" to "z", "")
            }.runContractTest { }
        }

        assertIncompatibleVariableError(err, "b", "z")
    }

    @Test
    fun `from object field - same variable name used in operation variable and annotation variable`() {
        EngineTestModule("extend type Query { x(a:Int):Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(b:\$b), z",
                variables = listOf(FromObjectFieldVariable("b", "z")),
            ) { args, obj, _, _, _ ->
                args.getAs<Int>("a") * obj.fetchAs<Int>("y")
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 5 }
                }
            }
            fieldWithValue("Query" to "z", 3)
        }.runContractTest {
            runQuery(
                "query Q(\$vara:Int!) {x(a:\$vara)}",
                mapOf("vara" to 2),
            ).assertJson("{data: {x: 30}}")
        }
    }

    @Test
    fun `from object field - same variable name used in multiple selection sets`() {
        EngineTestModule("extend type Query { x:Int, y:Int, z(c:Int):Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "z(c:\$var), w",
                variables = listOf(FromObjectFieldVariable("var", "w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("z") * 5
            }
            fieldWithFromFieldVariables(
                coord = "Query" to "y",
                objectSelectionsText = "z(c:\$var), w",
                variables = listOf(FromObjectFieldVariable("var", "w")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("z") * 3
            }
            fieldWithValue("Query" to "z", 2)
        }.runContractTest {
            runQuery("{x, y}").assertJson("{data: {x: 10, y: 6}}")
        }
    }

    @Test
    fun `invalid from object field -- variable used in conditional directive`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x:String, y:Boolean, z:Boolean! }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "y @skip(if:\$z), z",
                    variables = listOf(FromObjectFieldVariable("z", "z")),
                ) { _, _, _, _, _ -> error("should not execute") }
                fieldWithValue("Query" to "y", true)
                fieldWithValue("Query" to "z", true)
            }.runContractTest { }
        }.unwrap()

        assertEquals("z", invalidVariableName(err))
    }

    @Test
    fun `invalid from object field -- variable depends on a field in its own subselections`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x(a:Int):Query, y:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "x(a:\$a) { y } ",
                    variables = listOf(FromObjectFieldVariable("a", "x.y")),
                ) { _, _, _, _, _ -> error("should not execute") }
                field("Query" to "y") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
            }.runContractTest { }
        }.unwrap()

        assertTrue(err is VariableCycleException)
    }

    @Test
    fun `invalid from object field -- variable selects a field that uses it`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x(a:Int):Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "x(a:\$a)",
                    variables = listOf(FromObjectFieldVariable("a", "x")),
                ) { _, _, _, _, _ -> error("should not execute") }
            }.runContractTest { }
        }.unwrap()

        assertTrue(err is VariableCycleException)
    }

    @Test
    fun `invalid from object field -- deadlock between 2 variables -- same selection set`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x(a:Int):Int, y(b:Int):Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "x(a:\$a), y(b:\$b)",
                    variables = listOf(
                        FromObjectFieldVariable("a", "y"),
                        FromObjectFieldVariable("b", "x"),
                    ),
                ) { _, _, _, _, _ -> error("should not execute") }
                field("Query" to "y") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
            }.runContractTest { }
        }.unwrap()

        assertTrue(err is VariableCycleException)
    }

    @Test
    fun `invalid from object field -- deadlock between 2 variables -- diff selection sets`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x(a:Int):Int, y(b:Int):Int, z:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    objectSelectionsText = "y(b:\$b), z",
                    variables = listOf(FromObjectFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> error("should not execute") }
                fieldWithFromFieldVariables(
                    coord = "Query" to "y",
                    objectSelectionsText = "x(a:\$a), z",
                    variables = listOf(FromObjectFieldVariable("a", "z")),
                ) { _, _, _, _, _ -> error("should not execute") }
            }.runContractTest { }
        }

        assertRequiredSelectionsCycleError(err)
    }

    @Test
    fun `invalid from query field -- path refers to missing selection`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x:Int, y(b:Int):Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    querySelectionsText = "y(b:\$b)",
                    variables = listOf(FromQueryFieldVariable("b", "invalidField")),
                ) { _, _, _, _, _ -> error("should not execute") }
                fieldWithValue("Query" to "y", 2)
            }.runContractTest { }
        }.unwrapAs<IllegalArgumentException>()

        assertTrue(checkNotNull(err.message).contains("No selections found for path"), err.message.orEmpty())
    }

    @Test
    fun `invalid from query field -- path ends on object`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z:Query, w:Int }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "x",
                    querySelectionsText = "y(b:\$b), z { w }",
                    variables = listOf(FromQueryFieldVariable("b", "z")),
                ) { _, _, _, _, _ -> error("should not execute") }
                field("Query" to "y") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
                field("Query" to "z") {
                    resolver {
                        fn { _, _, _, _, _ -> error("should not execute") }
                    }
                }
            }.runContractTest { }
        }.unwrap()

        assertEquals("b", invalidVariableName(err))
    }

    @Test
    fun `from query field -- simple`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                querySelectionsText = "y(b:\$b), z",
                variables = listOf(FromQueryFieldVariable("b", "z")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }
    }

    @Test
    fun `from query field -- variables used by field on non-root object`() {
        EngineTestModule(
            """
                type Obj { x:Int }
                extend type Query { obj:Obj y(b:Int):Int, z:Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Obj" to "x",
                querySelectionsText = "y(b:\$b), z",
                variables = listOf(FromQueryFieldVariable("b", "z")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("b") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), emptyMap())
                    }
                }
            }
        }.runContractTest {
            runQuery("{ obj { x } }").assertJson("{data: {obj: {x: 30}}}")
        }
    }

    @Test
    fun `from query field -- binds variable to query field with different name`() {
        EngineTestModule("extend type Query { x:Int, y(a:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                querySelectionsText = "y(a:\$varz), z",
                variables = listOf(FromQueryFieldVariable("varz", "z")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 5
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("a") * 3 }
                }
            }
            fieldWithValue("Query" to "z", 2)
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 30}}")
        }
    }

    @Test
    fun `from query field -- returns null value`() {
        EngineTestModule("extend type Query { x:Int!, y(a:Int):Int!, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(a:\$z)",
                querySelectionsText = "z",
                variables = listOf(FromQueryFieldVariable("z", "z")),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 2
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ -> (args["a"] as? Int) ?: -1 }
                }
            }
            fieldWithValue("Query" to "z", null)
        }.runContractTest {
            runQuery("{x}").assertJson("{data:{x:-2}}")
        }
    }

    @Test
    fun `from query field -- single-field-multiple-variable -- multiple variables on required selection`() {
        EngineTestModule("extend type Query { x:Int, y(b:Int, c:Int):Int, z:Int, w:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                querySelectionsText = "y(b:\$b, c:\$c), z, w",
                variables = listOf(
                    FromQueryFieldVariable("b", "z"),
                    FromQueryFieldVariable("c", "w"),
                ),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("b") * args.getAs<Int>("c") * 5
                    }
                }
            }
            fieldWithValue("Query" to "z", 3)
            fieldWithValue("Query" to "w", 2)
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 210}}")
        }
    }

    @Test
    fun `invalid from query field -- variable name overlaps with object field variable`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { foo: String!, bar(x: String!): String! }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "bar",
                    objectSelectionsText = "fragment _ on Query { foo }",
                    querySelectionsText = "fragment _ on Query { foo }",
                    variables = listOf(
                        FromObjectFieldVariable("name", "foo"),
                        FromQueryFieldVariable("name", "foo"),
                    ),
                ) { _, _, _, _, _ -> "result" }
                fieldWithValue("Query" to "foo", "test")
            }.runContractTest { }
        }.unwrap()

        val msg = checkNotNull(err.message)
        assertTrue(msg.contains("name"), msg)
        assertTrue(
            msg.contains("unused variables") || msg.contains("duplicate bindings"),
            msg,
        )
    }

    @Test
    fun `invalid from query field -- variable name overlaps with argument variable`() {
        val err = assertThrows<Throwable> {
            EngineTestModule("extend type Query { foo: String!, bar(name: String!): String! }") {
                fieldWithFromFieldVariables(
                    coord = "Query" to "bar",
                    querySelectionsText = "fragment _ on Query { foo }",
                    variables = listOf(
                        FromArgumentVariable("name", "name"),
                        FromQueryFieldVariable("name", "foo"),
                    ),
                ) { _, _, _, _, _ -> "result" }
                fieldWithValue("Query" to "foo", "test")
            }.runContractTest { }
        }.unwrap()

        val msg = checkNotNull(err.message)
        assertTrue(msg.contains("name"), msg)
        assertTrue(
            msg.contains("unused variables") || msg.contains("duplicate bindings"),
            msg,
        )
    }

    @Test
    fun `mixed variables -- from query field and from argument`() {
        EngineTestModule("extend type Query { x(a:Int):Int, y(a:Int, b:Int):Int, z:Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(a:\$vara, b:\$varb)",
                querySelectionsText = "z",
                variables = listOf(
                    FromArgumentVariable("vara", "a"),
                    FromQueryFieldVariable("varb", "z"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 7
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("a") * args.getAs<Int>("b") * 5
                    }
                }
            }
            fieldWithValue("Query" to "z", 3)
        }.runContractTest {
            runQuery("{x(a:2)}").assertJson("{data: {x: 210}}")
        }
    }

    @Test
    fun `mixed variables -- from query field and from object field`() {
        EngineTestModule("extend type Query { x:Int, y(a:Int, b:Int):Int, z(w:Int):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "x",
                objectSelectionsText = "y(a:\$a, b:\$b), z1:z(w:7)",
                querySelectionsText = "z2:z(w:5)",
                variables = listOf(
                    FromObjectFieldVariable("a", "z1"),
                    FromQueryFieldVariable("b", "z2"),
                ),
            ) { _, obj, _, _, _ ->
                obj.fetchAs<Int>("y") * 11
            }
            field("Query" to "y") {
                resolver {
                    fn { args, _, _, _, _ ->
                        args.getAs<Int>("a") * args.getAs<Int>("b") * 3
                    }
                }
            }
            field("Query" to "z") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("w") * 2 }
                }
            }
        }.runContractTest {
            runQuery("{x}").assertJson("{data: {x: 4620}}")
        }
    }

    @Test
    fun `from arg -- simple`() {
        EngineTestModule("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$y)",
                variables = listOf(FromArgumentVariable("y", "y")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runContractTest {
            runQuery("{foo(y:2)}").assertJson("{data: {foo: 30}}")
        }
    }

    @Test
    fun `from arg -- omitted nullable argument binds null`() {
        EngineTestModule("extend type Query { foo(x:Int):Int, bar(x:Int=17):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int?>("bar") }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args["x"] } }
            }
        }.runContractTest {
            runQuery("{ omitted:foo explicitNull:foo(x:null) supplied:foo(x:3) }")
                .assertJson("{data: {omitted: null, explicitNull: null, supplied: 3}}")
        }
    }

    @Test
    fun `from arg -- binds argument to variable with a different name`() {
        EngineTestModule("extend type Query { foo(y: Int!): Int!, bar(x:Int!): Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$vary)",
                variables = listOf(FromArgumentVariable("vary", "y")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runContractTest {
            runQuery("{foo(y:2)}").assertJson("{data: {foo: 30}}")
        }
    }

    @Test
    fun `from arg -- path traverses nested input`() {
        EngineTestModule(
            """
                input Inp { x:Int! }
                extend type Query { foo(inp:Inp!): Int!, bar(x:Int!):Int! }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "inp.x")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runContractTest {
            runQuery("{foo(inp:{x:2})}").assertJson("{data: {foo: 30}}")
        }
    }

    @Test
    fun `from arg -- path traverses through null object`() {
        EngineTestModule(
            """
                input Inp { x:Int!=2 }
                extend type Query { foo(inp:Inp):Int, bar(x:Int):Int }
            """.trimIndent()
        ) {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "inp.x")),
            ) { _, obj, _, _, _ -> (obj.fetchAs<Int?>("bar")?.let { it * 5 }) ?: 7 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> (args["x"] as? Int)?.let { it * 3 } } }
            }
        }.runContractTest {
            runQuery("{foo}").assertJson("{data: {foo: 7}}")
            runQuery("{foo(inp:null)}").assertJson("{data: {foo: 7}}")
        }
    }

    @Test
    fun `from arg -- arg has default value`() {
        EngineTestModule("extend type Query { foo(x:Int!=2):Int!, bar(x:Int):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runContractTest {
            runQuery("{foo}").assertJson("{data: {foo: 30}}")
            runQuery("{foo(x:3)}").assertJson("{data: {foo: 45}}")
        }
    }

    @Test
    fun `from arg -- arg from operation variable`() {
        EngineTestModule("extend type Query { foo(y:Int!):Int!, bar(x:Int!): Int! }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "bar(x:\$y)",
                variables = listOf(FromArgumentVariable("y", "y")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") * 5 }
            field("Query" to "bar") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 3 } }
            }
        }.runContractTest {
            runQuery("query Q(\$vary:Int!) {foo(y:\$vary)}", mapOf("vary" to 2)).assertJson("{data: {foo: 30}}")
        }
    }

    @Test
    fun `from arg -- same variable name used in same argument with different values`() {
        EngineTestModule("extend type Query { foo(x:Int):Int, bar(x:Int):Int, baz(x:Int):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "baz(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 11 }
            fieldWithFromFieldVariables(
                coord = "Query" to "bar",
                objectSelectionsText = "baz(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 7 }
            field("Query" to "baz") {
                resolver { fn { args, _, _, _, _ -> args.getAs<Int>("x") * 5 } }
            }
        }.runContractTest {
            runQuery("{foo(x:2) bar(x:3)}").assertJson("{data:{foo:110, bar:105}}")
        }
    }

    @Test
    fun `from arg -- uses an arg variable with the same name as an unbound argument`() {
        EngineTestModule("extend type Query { foo(x:Int):Int, bar(x:Int):Int, baz(x:Int):Int }") {
            fieldWithFromFieldVariables(
                coord = "Query" to "foo",
                objectSelectionsText = "baz(x:\$x)",
                variables = listOf(FromArgumentVariable("x", "x")),
            ) { _, obj, _, _, _ -> obj.fetchAs<Int>("baz") * 11 }
            fieldWithFromFieldVariables(
                coord = "Query" to "bar",
                variables = listOf(),
            ) { _, _, _, _, _ -> 7 }
            field("Query" to "baz") {
                resolver { fn { args, _, _, _, _ -> (args["x"] as? Int)?.let { it * 5 } ?: 0 } }
            }
        }.runContractTest {
            runQuery("{foo(x:2) bar(x:3)}").assertJson("{data:{foo:110, bar:7}}")
        }
    }

    @Test
    fun `mixed variables -- non-root resolver uses fromObjectField in queryFragment`() {
        EngineTestModule(
            """
                extend type Query { x(a:Int):Int, obj:Obj }
                type Obj { x:Int, y:Int }
            """.trimIndent()
        ) {
            field("Query" to "obj") {
                resolver {
                    fn { _, _, _, _, _ ->
                        createEngineObjectData(schema.schema.getObjectType("Obj"), emptyMap())
                    }
                }
            }
            field("Query" to "x") {
                resolver {
                    fn { args, _, _, _, _ -> args.getAs<Int>("a") * 5 }
                }
            }
            fieldWithFromFieldVariables(
                coord = "Obj" to "x",
                objectSelectionsText = "y",
                querySelectionsText = "x(a:\$a)",
                variables = listOf(FromObjectFieldVariable("a", "y")),
            ) { _, _, qry, _, _ ->
                qry.fetchAs<Int>("x") * 3
            }
            fieldWithValue("Obj" to "y", 2)
        }.runContractTest {
            runQuery("{obj{x}}").assertJson("{data: {obj: {x: 30}}}")
        }
    }
}

fun MockTenantModuleDSL<Unit>.fieldWithFromFieldVariables(
    coord: Pair<String, String>,
    objectSelectionsText: String? = null,
    querySelectionsText: String? = null,
    variables: List<SelectionSetVariable> = emptyList(),
    resolveFn: FieldUnbatchedResolverFn,
) {
    val objectSelections = objectSelectionsText?.let { SelectionsParser.parse(coord.first, it) }
    val querySelections = querySelectionsText?.let { SelectionsParser.parse(queryType.name, it) }
    val variablesResolvers = VariablesResolver.fromSelectionSetVariables(
        objectSelections = objectSelections,
        querySelections = querySelections,
        variables = variables,
        forChecker = false,
    )

    field(coord) {
        resolverExecutor {
            MockFieldUnbatchedResolverExecutor(
                objectSelectionSet = objectSelections?.let {
                    RequiredSelectionSet(it, variablesResolvers, forChecker = false)
                },
                querySelectionSet = querySelections?.let {
                    RequiredSelectionSet(it, variablesResolvers, forChecker = false)
                },
                argumentVariables = VariableFromArgumentDefinitions(
                    variables.filterIsInstance<FromArgumentVariable>().associate { it.name to it.valueFromPath },
                ),
                objectFieldVariables = VariableFromFieldDefinitions(
                    variables.filterIsInstance<FromObjectFieldVariable>().associate { it.name to it.valueFromPath },
                ),
                queryFieldVariables = VariableFromFieldDefinitions(
                    variables.filterIsInstance<FromQueryFieldVariable>().associate { it.name to it.valueFromPath },
                ),
                resolverId = resolverId,
                unbatchedResolveFn = resolveFn,
            )
        }
    }
}

@Suppress("NO_TAIL_CALLS_FOUND", "NON_TAIL_RECURSIVE_CALL") // recursive call is wrapped in takeIf/?.
private tailrec fun Throwable.unwrap(): Throwable = cause?.takeIf { it !== this }?.unwrap() ?: this

private inline fun <reified T : Throwable> Throwable.unwrapAs(): T =
    unwrap() as? T
        ?: throw IllegalStateException("Expected ${T::class.simpleName} but got ${unwrap()::class.simpleName}")
