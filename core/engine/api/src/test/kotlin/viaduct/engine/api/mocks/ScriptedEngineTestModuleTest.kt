@file:Suppress("ForbiddenImport")

package viaduct.engine.api.mocks

import graphql.schema.GraphQLInterfaceType
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolveSelectionSetOptions
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.api.spi.FieldResolverExecutor
import viaduct.engine.api.spi.NodeResolverExecutor
import viaduct.engine.api.spi.VariableFromArgumentDefinitions
import viaduct.engine.api.spi.VariableFromFieldDefinitions
import viaduct.engine.api.testing.ResolverTestDslConformance
import viaduct.engine.api.testing.executors.fromDSL
import viaduct.engine.runtime.mocks.ContextMocks

class ScriptedEngineTestModuleTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `malformed outputs conform to the shared DSL contract`(engine2: Boolean) {
        ResolverTestDslConformance.rejectedOutputs.forEach { case ->
            EngineTestModule.fromDSL(case.schemaSDL).runStandardViaductFeatureTest(engine2Enabled = engine2) {
                val result = runQuery(case.query)
                assertEquals(mapOf("value" to null), result.getData(), case.name)
                val failure = result.errors.single()
                assertEquals(listOf("value"), failure.path, case.name)
                assertTrue(failure.message.contains(case.diagnostic), "${case.name}: ${failure.message}")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `abstract outputs resolve concrete types before selective projection`(engine2: Boolean) {
        EngineTestModule.fromDSL(
            """
            extend type Query {
                iface: Value @resolver(script: "{'__typename': 'Item', 'x': 7}")
                union: Choice @resolver(script: "{'__typename': 'Item', 'x': 9}")
            }
            interface Value { x: Int }
            type Item implements Value { x: Int }
            type Other { name: String }
            union Choice = Item | Other
            """
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            runQuery("{ iface { __typename alias: x } union { __typename ... on Item { x } } }")
                .assertJson("{data: {iface: {__typename: 'Item', alias: 7}, union: {__typename: 'Item', x: 9}}}")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `node scripts cannot supply their own identity`(engine2: Boolean) {
        EngineTestModule.fromDSL(
            """
            extend type Query { item: Item @resolver(script: "ref('node', {'id': 'one'})") }
            type Item implements Node @nodeResolver(ids: ["one"], script: "{'id': 'other', 'x': 9}") { id: ID!, x: Int }
            """
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            val result = runQuery("{ item { id x } }")
            assertEquals(mapOf("item" to null), result.getData())
            assertTrue(result.errors.single().message.contains("supplies id from the Node identity"))
        }
    }

    @Test
    fun `variable declarations conform to the shared DSL contract`() {
        ResolverTestDslConformance.rejectedDeclarations.forEach { (name, source) ->
            assertThrows(IllegalArgumentException::class.java, { EngineTestModule.fromDSL(source) }, name)
        }
        ResolverTestDslConformance.acceptedDeclarations.forEach { (_, source) -> EngineTestModule.fromDSL(source) }
    }

    @Test
    fun `checker declarations conform to the shared DSL contract`() {
        ResolverTestDslConformance.rejectedCheckerDeclarations.forEach { (name, source) ->
            assertThrows(IllegalArgumentException::class.java, { EngineTestModule.fromDSL(source) }, name)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `accepted checker declarations execute with pair local variables`(engine2: Boolean) {
        ResolverTestDslConformance.acceptedCheckerDeclarations.forEach { case ->
            EngineTestModule.fromDSL(case.schemaSDL).runStandardViaductFeatureTest(engine2Enabled = engine2) {
                runQuery(case.query).assertJson("{data: {checked: 7}}")
            }
        }
    }

    @Test
    fun `path variables cannot shadow field arguments`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule.fromDSL(
                """
                extend type Query {
                    seed: Int @resolver(script: "3")
                    echo(value: Int): Int @resolver(script: "value")
                    value(v: Int): Int @resolver(of: "seed echo(value: ${'$'}v)", pathVars: [{name: "v", path: ["seed"]}], script: "objectValue.echo")
                }
                """
            )
        }
        assertTrue(failure.message!!.contains("pathVars may not redefine field arguments"), failure.message)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `explicit throws fail resolvers checkers and providers with source diagnostics`(engine2: Boolean) {
        EngineTestModule.fromDSL(
            """
            extend type Query {
                result: Int @resolver(script: "throw 'resolver failed';")
                checked: Int @resolver(script: "1") @checker(script: "throw 'checker failed';")
                provided: Int @resolver(
                    of: "echo(value: ${'$'}seed)"
                    vars: {names: ["seed"], script: "throw 'provider failed';"}
                    script: "objectValue.echo"
                )
                echo(value: Int): Int @resolver(script: "value")
            }
            """
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            val result = runQuery("{ result checked provided }")
            assertEquals(mapOf("result" to null, "checked" to null, "provided" to null), result.getData())
            assertEquals(3, result.errors.size)
            for ((field, diagnostic) in mapOf(
                "result" to "Query/result@1:1: resolver failed",
                "checked" to "Query/checked.checker@1:1: checker failed",
                "provided" to "Query/provided.vars@1:1: provider failed",
            )) {
                val error = result.errors.single { it.path == listOf(field) }
                assertTrue(error.message.contains(diagnostic), error.message)
            }
        }
    }

    @Test
    fun `scripted modules retain the feature harness schema defaults`() {
        val schema = EngineTestModule.fromDSL(
            """extend type Query { x: Int @resolver(script: "1") } type Item implements Node { id: ID! }"""
        ).fullSchema.schema
        val nativeSchema = createSchemaWithWiring("extend type Query { x: Int } type Item implements Node { id: ID! }").schema
        assertEquals(nativeSchema.queryType.fieldDefinitions.map { it.name }, schema.queryType.fieldDefinitions.map { it.name })
        val nodeScope = schema.getTypeAs<GraphQLInterfaceType>("Node").getAppliedDirective("scope")
        assertTrue(nodeScope != null)
        assertEquals(
            nativeSchema.getTypeAs<GraphQLInterfaceType>("Node").getAppliedDirective("scope").getArgument("to").getValue<List<String>>(),
            nodeScope.getArgument("to").getValue<List<String>>(),
        )
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `scripted and native executors share one module`(engine2: Boolean) {
        val checks = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val module = EngineTestModule.fromDSL(
            """
            extend type Query {
                scripted: Int @resolver(script: "3")
                native: Int @checker(script: "allow")
                scriptedNode: Scripted @resolver(script: "ref('node', {'id': 'scripted'})")
                nativeNode: Native
            }
            type Scripted implements Node @nodeResolver(ids: ["scripted"], script: "{'x': 5}") { id: ID!, x: Int }
            type Native implements Node @checker(script: "allow") { id: ID!, x: Int }
            """
        ) {
            field("Query" to "scripted") {
                checker { fn { _, _ -> checks.add("field") } }
            }
            fieldWithValue("Query" to "native", 7)
            field("Query" to "nativeNode") {
                resolver {
                    fn { _, _, _, _, ctx -> ctx.createNodeReference("native", schema.schema.getObjectType("Native")) }
                }
            }
            type("Scripted") {
                checker { fn { _, _ -> checks.add("type") } }
            }
            type("Native") {
                nodeUnbatchedExecutor { id, _, _ -> createEngineObjectData(objectType, mapOf("id" to id, "x" to 11)) }
            }
        }
        module.runStandardViaductFeatureTest(engine2Enabled = engine2) {
            runQuery("{ scripted native scriptedNode { x } nativeNode { x } }")
                .assertJson("{data: {scripted: 3, native: 7, scriptedNode: {x: 5}, nativeNode: {x: 11}}}")
        }
        assertEquals(setOf("field", "type"), checks.toSet())
    }

    @Test
    fun `scripted and native declarations cannot replace each other`() {
        val schemaSDL = """
            extend type Query { x: Int @resolver(script: "1") @checker(script: "allow") }
            type Item implements Node @nodeResolver(ids: ["one"], script: "{'x': 2}") @checker(script: "allow") { id: ID!, x: Int }
        """
        val fieldResolver = assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule.fromDSL(schemaSDL) { fieldWithValue("Query" to "x", 9) }
        }
        assertTrue(fieldResolver.message!!.contains("Duplicate scripted/native field resolver"))
        val nodeResolver = assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule.fromDSL(schemaSDL) {
                type("Item") { nodeUnbatchedExecutor { id, _, _ -> createEngineObjectData(objectType, mapOf("id" to id)) } }
            }
        }
        assertTrue(nodeResolver.message!!.contains("Duplicate scripted/native node resolver"))
        val fieldChecker = assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule.fromDSL(schemaSDL) { field("Query" to "x") { checker { fn { _, _ -> } } } }
        }
        assertTrue(fieldChecker.message!!.contains("Duplicate scripted/native field checker"))
        val typeChecker = assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule.fromDSL(schemaSDL) { type("Item") { checker { fn { _, _ -> } } } }
        }
        assertTrue(typeChecker.message!!.contains("Duplicate scripted/native type checker"))
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `scripted executors run through module bootstrap in both engines`(engine2: Boolean) {
        val module = EngineTestModule.fromDSL(
            """
            extend type Query {
                item(seed: Int): Item @resolver(script: "{'name': 'item-' + seed, 'count': seed * 2, 'kind': 'SMALL', 'values': [1, 2]}")
                summary(seed: Int): String @resolver(of: "item(seed: ${'$'}seed) { name count }", script: "objectValue.item.name + ':' + objectValue.item.count")
            }
            enum Kind { SMALL LARGE }
            type Item { name: String, count: Int, kind: Kind, values: [Int] }
            """
        )
        assertTrue(module.fieldResolverExecutors.all { it.second.isSelective })
        module.runStandardViaductFeatureTest(engine2Enabled = engine2) {
            runQuery("{ summary(seed: 3) item(seed: 4) { alias: name kind values } }")
                .assertJson("""{data: {summary: "item-3:6", item: {alias: "item-4", kind: "SMALL", values: [1, 2]}}}""")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `explicit argument and field sources retain provenance`(engine2: Boolean) {
        val module = EngineTestModule.fromDSL(
            """
            input Input { nested: Int }
            extend type Query {
                seed: Int @resolver(script: "3")
                echo(value: Int): Int @resolver(script: "value")
                total(input: Input): Int @resolver(
                    of: "seed echo(value: ${'$'}source)"
                    qf: "echo(value: ${'$'}renamed)"
                    variables: [
                        {name: "source", fromObjectField: ["seed"]},
                        {name: "renamed", fromArgument: ["input", "nested"]}
                    ]
                    script: "objectValue.echo + queryValue.echo"
                )
            }
            """
        )
        val executor = module.fieldResolverExecutors.single { it.first.second == "total" }.second
        assertEquals(VariableFromArgumentDefinitions(mapOf("renamed" to "input.nested")), executor.argumentVariables)
        assertEquals(VariableFromFieldDefinitions(mapOf("source" to "seed")), executor.objectFieldVariables)
        module.runStandardViaductFeatureTest(engine2Enabled = engine2) {
            runQuery("{ total(input: {nested: 7}) }").assertJson("{data: {total: 10}}")
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `checker pairs have independent variables and raw reads`(engine2: Boolean) {
        EngineTestModule.fromDSL(
            """
            extend type Query {
                echo(value: Int): Int @resolver(script: "value") @checker(script: "denyWithMessage('client denied')")
                item: Item @resolver(script: "{'key': 2}")
            }
            type Item @checker(
                inputs: [
                    {name: "first", of: "key", qf: "echo(value: ${'$'}n)", vars: {names: ["n"], script: "{'n': 2}"}},
                    {name: "second", qf: "echo(value: ${'$'}n)", vars: {names: ["n"], script: "{'n': 3}"}}
                ]
                script: "inputs.first.objectValue.key == inputs.first.queryValue.echo && inputs.second.queryValue.echo == 3 ? allow : deny"
            ) { key: Int }
            """
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            runQuery("{ item { key } }").assertJson("{data: {item: {key: 2}}}")
            val denied = runQuery("{ echo(value: 2) }")
            assertEquals(mapOf("echo" to null), denied.getData())
            assertEquals(listOf("echo"), denied.errors.single().path)
            assertTrue(denied.errors.single().message.contains("client denied"))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `nested queries support variables fragments and direct object returns`(engine2: Boolean) {
        EngineTestModule.fromDSL(
            """
            extend type Query {
                item(value: Int): Item @resolver(script: "{'x': value}")
                copy(seed: Int): Item @resolver(script: "query('fragment Main on Query { item(value: ${'$'}n) { ...Fields } } fragment Fields on Item { x }', {'n': seed}).item")
            }
            type Item { x: Int }
            """
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            runQuery("{ copy(seed: 7) { x } }").assertJson("{data: {copy: {x: 7}}}")
        }
    }

    @Test
    fun `field and node outputs preserve deferred subquery read failures`(): Unit =
        runBlocking {
            val failure = IllegalStateException("deferred child failed")
            for (script in listOf(
                "query('{ item { value } }').item",
                "{'child': query('{ item { value } }').item}",
                "{'items': [query('{ item { value } }').item]}",
            )) {
                val module = EngineTestModule.fromDSL(
                    """
                    extend type Query {
                        item: Item
                        copy: Item @resolver(script: "$script")
                    }
                    type Item implements Node @nodeResolver(ids: ["one"], script: "$script") {
                        id: ID!, value: Int, child: Item, items: [Item]
                    }
                    """
                )
                val itemType = module.fullSchema.schema.getObjectType("Item")
                val item = object : EngineObjectData.Sync by ResolvedEngineObjectData(itemType, mapOf("value" to null)) {
                    override fun get(selection: String): Any? = throw failure
                }
                val root = ResolvedEngineObjectData(module.fullSchema.schema.queryType, mapOf("item" to item))
                val context = object : EngineExecutionContext by ContextMocks(module.fullSchema).engineExecutionContext {
                    override suspend fun resolveSelectionSet(
                        selectionSet: EngineSelectionSet,
                        options: ResolveSelectionSetOptions
                    ): EngineObjectData.Sync = root
                }
                val field = module.fieldResolverExecutors.single().second
                val selector = FieldResolverExecutor.Selector(emptyMap(), null, { root }, { root })
                val fieldFailure = field.batchResolve(listOf(selector), context).getValue(selector).exceptionOrNull()
                val node = module.nodeResolverExecutors.single().second
                val selections = createEngineSelectionSet(SelectionsParser.parse("Item", "value"), module.fullSchema, emptyMap())
                val nodeSelector = NodeResolverExecutor.Selector("one", selections)
                val nodeFailure = node.resolve(listOf(nodeSelector), context).getValue(nodeSelector).exceptionOrNull()
                for (actual in listOf(fieldFailure, nodeFailure)) {
                    assertEquals(failure.javaClass, actual?.javaClass, script)
                    assertSame(failure, generateSequence(actual) { it.cause }.last(), script)
                }
            }
        }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `node and root references use native executor boundaries`(engine2: Boolean) {
        for (isSelective in listOf(false, true)) {
            val module = EngineTestModule.fromDSL(
                """
                extend type Query {
                    item: Item @resolver(script: "ref('node', {'id': 'one'})")
                    aliasItem: Item @resolver(script: "ref('item')")
                }
                type Item implements Node @nodeResolver(ids: ["one"], isSelective: $isSelective, script: "{'x': 9}") { id: ID!, x: Int }
                """
            )
            assertEquals(isSelective, module.nodeResolverExecutors.single().second.isSelective)
            module.runStandardViaductFeatureTest(engine2Enabled = engine2) {
                runQuery("{ item { id x } aliasItem { id x } }")
                    .assertJson("{data: {item: {id: 'one', x: 9}, aliasItem: {id: 'one', x: 9}}}")
                runQuery("{ aliasItem { identity: id x } }")
                    .assertJson("{data: {aliasItem: {identity: 'one', x: 9}}}")
                runQuery("{ item { id } aliasItem { identity: id } }")
                    .assertJson("{data: {item: {id: 'one'}, aliasItem: {identity: 'one'}}}")
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `native references to unregistered scripted Node IDs fail descriptively`(engine2: Boolean) {
        for (isSelective in listOf(false, true)) {
            EngineTestModule.fromDSL(
                """
                extend type Query { known: Item, missing: Item }
                type Item implements Node @nodeResolver(ids: ["one"], isSelective: $isSelective, script: "{'value': 7}") { id: ID!, value: Int }
                """
            ) {
                for ((fieldName, id) in listOf("known" to "one", "missing" to "absent")) {
                    field("Query" to fieldName) {
                        resolver {
                            fn { _, _, _, _, context -> context.createNodeReference(id, schema.schema.getObjectType("Item")) }
                        }
                    }
                }
            }.runStandardViaductFeatureTest(engine2Enabled = engine2) {
                val result = runQuery("{ known { value } missing { value } }")
                assertEquals(mapOf("known" to mapOf("value" to 7), "missing" to null), result.getData())
                val error = result.errors.single()
                assertEquals(listOf("missing"), error.path)
                assertTrue(error.message.contains("Item/@nodeResolver has no fixture for id 'absent'"), error.message)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `reject RSS reuse and aliased subquery returns before projection`(engine2: Boolean) {
        EngineTestModule.fromDSL(
            """
            extend type Query {
                item: Item @resolver(script: "{'x': 1, 'other': 2}")
                rss: Item @resolver(of: "item { x }", script: "objectValue.item")
                copy: Item @resolver(script: "query('{ item { other: x } }').item")
            }
            type Item { x: Int, other: Int }
            """
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            val result = runQuery("{ rss { x } copy { other } }")
            assertEquals(mapOf("rss" to null, "copy" to null), result.getData())
            assertEquals(setOf(listOf("rss"), listOf("copy")), result.errors.map { it.path }.toSet())
            assertTrue(result.errors.any { it.message.contains("**FROM_RSS**") })
            assertTrue(result.errors.any { it.message.contains("aliased selection") })
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `nested mutations and argument providers keep execution semantics`(engine2: Boolean) {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        EngineTestModule.fromDSL(
            """
            extend type Query {
                echo(value: Int): Int @resolver(script: "value")
                total(seed: Int): Int @resolver(
                    qf: "echo(value: ${'$'}n)"
                    vars: {names: ["n"], script: "{'n': seed * 2}"}
                    script: "queryValue.echo + mutation('{ increment }').increment"
                )
            }
            extend type Mutation {
                increment: Int @resolver(isSelective: false, script: "counter.incrementAndGet()")
            }
            """,
            scriptBindings = mapOf("counter" to calls),
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            runQuery("{ total(seed: 3) }").assertJson("{data: {total: 7}}")
            runQuery("mutation { increment }").assertJson("{data: {increment: 2}}")
        }
        assertEquals(2, calls.get())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `invalid undemanded output is rejected and explicit errors become field failures`(engine2: Boolean) {
        EngineTestModule.fromDSL(
            """
            extend type Query {
                invalid: Item @resolver(script: "{'x': 1, 'flag': 'wrong'}")
                failure: Int @resolver(script: "error")
            }
            type Item { x: Int, flag: Boolean }
            """
        ).runStandardViaductFeatureTest(engine2Enabled = engine2) {
            val result = runQuery("{ invalid { x } failure }")
            assertEquals(mapOf("invalid" to null, "failure" to null), result.getData())
            assertEquals(setOf(listOf("invalid"), listOf("failure")), result.errors.map { it.path }.toSet())
            assertTrue(result.errors.any { it.message.contains("requires a Boolean") })
            assertTrue(result.errors.any { it.message.contains("returned error") })
        }
    }

    @Test
    fun `script preparation rejects missing sources cycles and binding conflicts`() {
        for (declarations in listOf("", "variables: [{name: \"v\", fromObjectField: [\"echo\"]}],")) {
            assertThrows(IllegalArgumentException::class.java) {
                EngineTestModule.fromDSL(
                    """extend type Query { echo(value: Int): Int @resolver(of: "echo(value: ${'$'}v)", $declarations script: "1") }"""
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            EngineTestModule.fromDSL("""extend type Query { x: Int @resolver(script: "1") }""", mapOf("query" to 1))
        }
        val module = EngineTestModule.fromDSL("""extend type Query { x: Int @resolver(isSelective: false, script: "1") }""")
        assertFalse(module.fieldResolverExecutors.single().second.isSelective)
    }
}
