@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.testing

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.commons.jexl3.JexlException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineErrorDataReadException
import viaduct.engine.runtime2.model.EngineObjectDataEntry
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.MaterializeSelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.NonselectiveFieldResolverFunction
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.resolve
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class JEXLNonselectiveFieldResolverFunctionTest {
    private val world = TestWorld.fromSDL(
        """
        type Query { result(arg_x: Int, arg_y: Int): Int item: Item items: [[Item]] abstract: Value numbers: [Int] }
        interface Value { n: Int }
        type Item implements Value { n: Int child: Item numbers: [Int] absent: Int }
        type Other implements Value { n: Int }
        """.trimIndent(),
    )

    @Test
    fun `binds arguments and null for absent RSSes`(): Unit =
        runBlocking {
            val function = function("objectValue == null && queryValue == null ? arg_x + arg_y : 0")
            assertEquals(12, invoke(function, arguments = mapOf("arg_x" to 5, "arg_y" to 7)))
            assertNull(invoke(function("arg_x")))
        }

    @Test
    fun `reads nested response keys and lists without exposing engine properties`(): Unit =
        runBlocking {
            val input = engineObjectDataOf(
                world.schema.requireObjectField("Query", "item").type.baseTypeDef as ViaductSchema.Object,
                listOf(
                    EngineObjectDataEntry.of("type", world.schema.requireObjectField("Item", "n"), 3),
                    EngineObjectDataEntry.of("child", world.schema.requireObjectField("Item", "child"), world.schema.objectOf("Item") { "numbers" setTo listOf(4, null, 6) }),
                ),
            )
            val queryValue = world.schema.objectOf("Query") { "numbers" setTo listOf(8) }
            assertEquals(17, invoke(function("objectValue.type + objectValue.child.numbers[2] + queryValue.numbers[0]", objectRss = true, queryRss = true), input, queryValue))
            assertNull(invoke(function("objectValue.child.numbers[1]", objectRss = true), input))
            assertThrows<JexlException> { invoke(function("objectValue.absent", objectRss = true), input) }
            assertThrows<JexlException> { invoke(function("objectValue.type = 99", objectRss = true), input) }
            assertEquals(3, input.get("type"))
        }

    @Test
    fun `converts partial nested objects primitive arrays and nulls`(): Unit =
        runBlocking {
            val output = assertIs<List<*>>(invoke(function("[[{'n': 2, 'child': {'n': 3}, 'numbers': [4, 5]}], [null]]", "items")))
            val item = assertIs<EngineObjectData.Sync>(assertIs<List<*>>(output[0]).single())
            assertEquals(2, item.get("n"))
            assertEquals(3, assertIs<EngineObjectData.Sync>(item.get("child")).get("n"))
            assertEquals(listOf(4, 5), item.get("numbers"))
            assertTrue(!item.isPresent("absent"))
            assertEquals(listOf(null), output[1])
        }

    @Test
    fun `returns explicit errors at scalar object list and nested output positions`(): Unit =
        runBlocking {
            for (field in listOf("result", "item", "numbers", "abstract")) {
                assertIs<EngineErrorData>(invoke(function("error", field)))
            }
            val output = assertIs<EngineObjectData.Sync>(invoke(function("{'n': error, 'child': error, 'numbers': [1, error, null]}", "item")))
            assertIs<EngineErrorData>(output.outputValue("n"))
            assertIs<EngineErrorData>(output.outputValue("child"))
            val numbers = assertIs<List<*>>(output.outputValue("numbers"))
            assertEquals(1, numbers[0])
            assertIs<EngineErrorData>(numbers[1])
            assertNull(numbers[2])
            val conditional = function("arg_x < 0 ? error : arg_x")
            val error = assertIs<EngineErrorData>(invoke(conditional, arguments = mapOf("arg_x" to -1)))
            assertSame(error, invoke(conditional, arguments = mapOf("arg_x" to -1)))
            assertEquals(7, invoke(conditional, arguments = mapOf("arg_x" to 7)))
        }

    @Test
    fun `rejects explicit errors as subquery input values`(): Unit =
        runBlocking {
            val exception = assertThrows<JexlException> { invoke(function("query('{ numbers }', {'n': error})")) }
            val cause = assertIs<IllegalArgumentException>(exception.cause)
            assertTrue(cause.message.orEmpty().contains("query variable 'n' requires an integer"), cause.message)
        }

    @Test
    fun `uses typename for abstract results and retains type when returning an input object`(): Unit =
        runBlocking {
            val output = assertIs<EngineObjectData.Sync>(invoke(function("{'__typename': 'Item', 'n': 9}", "abstract")))
            assertEquals("Item", output.type.name)
            assertEquals(9, output.get("n"))
            val input = world.schema.objectOf("Other") { "n" setTo 11 }
            val returned = assertIs<EngineObjectData.Sync>(invoke(function("objectValue", "abstract", objectRss = true), input))
            assertEquals("Other", returned.type.name)
            assertEquals(11, returned.get("n"))
            assertThrows<IllegalArgumentException> { invoke(function("{'n': 1}", "abstract")) }
            assertThrows<IllegalArgumentException> { invoke(function("{'__typename': 'Query'}", "abstract")) }
            assertThrows<IllegalArgumentException> { invoke(function("{'__typename': 'Other'}", "item")) }
        }

    @Test
    fun `rejects aliased objects on return even when the alias names another schema field`(): Unit =
        runBlocking {
            val input = engineObjectDataOf(
                world.schema.requireObjectField("Query", "item").type.baseTypeDef as ViaductSchema.Object,
                listOf(EngineObjectDataEntry.of("absent", world.schema.requireObjectField("Item", "n"), 7)),
            )
            val context = object : ResolutionExecutionContext {
                override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync = world.schema.objectOf("Query") { "item" setTo input }
            }
            for ((script, path) in listOf(
                "objectValue" to "Query/item",
                "{'child': objectValue}" to "Query/item.child",
                "query('{ item { absent: n } }').item" to "Query/item",
            )) {
                val exception = assertThrows<IllegalArgumentException> { invoke(function(script, "item", objectRss = true), input, context = context) }
                assertTrue(exception.message.orEmpty().contains(path), exception.message)
                assertTrue(exception.message.orEmpty().contains("aliased selection 'absent' for field 'n'"), exception.message)
                assertTrue(exception.message.orEmpty().contains("construct an output map"), exception.message)
            }
            val projected = assertIs<EngineObjectData.Sync>(invoke(function("{'n': objectValue.absent}", "item", objectRss = true), input))
            assertEquals(7, projected.get("n"))
        }

    @Test
    fun `normalizes integer results and subquery variables within GraphQL Int range`() {
        val engineWorld = TestWorld.fromSDL(
            "type Query { result: [Int] echo(value: Int): Int }",
            fieldResolvers = { schemas ->
                mapOf(
                    "result" to """
                        var low = query('{ echo(value: ${'$'}n) }', {'n': -2147483648L}).echo;
                        var high = query('{ echo(value: ${'$'}n) }', {'n': 2147483647H}).echo;
                        var absent = query('{ echo(value: ${'$'}n) }', {'n': null}).echo;
                        return [low, high, absent];
                    """.trimIndent(),
                    "echo" to "value",
                ).map { (name, script) ->
                    val field = schemas.loweredSchema.requireObjectField("Query", name)
                    field to FieldResolverDefinition.of(
                        objectFragment = schemas.loweredSchema.emptyFragmentOf("Query"),
                        queryFragment = null,
                        function = createJEXLNonselectiveFieldResolverFunction(schemas, field, script),
                    )
                }.toMap()
            },
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(engineWorld.assumptions).resolve(engineWorld.schemas.operationSelectionsFrom("{ result }"), dispatcher)
            val result = assertIs<ListEngineResult>(root.getCell(root.keys.single()).value.get())
            assertEquals(listOf(Int.MIN_VALUE, Int.MAX_VALUE, null), result.map { it.value.get() })
        }
        runBlocking {
            assertEquals(Int.MIN_VALUE, invoke(function("-2147483648H")))
            assertEquals(Int.MAX_VALUE, invoke(function("2147483647L")))
        }
    }

    @Test
    fun `rejects noninteger and overflowing subquery variables with their names`(): Unit =
        runBlocking {
            for (value in listOf("2147483648L", "-2147483649H", "1.5", "'7'", "[7]")) {
                val exception = assertThrows<JexlException> { invoke(function("query('{ numbers }', {'bad': $value})")) }
                val cause = assertIs<IllegalArgumentException>(exception.cause)
                assertTrue(cause.message.orEmpty().contains("query variable 'bad'"), cause.message)
            }
        }

    @Test
    fun `rejects malformed outputs with field paths`(): Unit =
        runBlocking {
            for ((script, field, path) in listOf(
                Triple("2147483648L", "result", "Query/result"),
                Triple("1.5", "result", "Query/result"),
                Triple("'hello'", "result", "Query/result"),
                Triple("[1, 'hello']", "numbers", "Query/numbers[1]"),
                Triple("{'child': {'n': 'hello'}}", "item", "Query/item.child.n"),
            )) {
                val exception = assertThrows<IllegalArgumentException> { invoke(function(script, field)) }
                assertTrue(exception.message.orEmpty().contains(path), exception.message)
            }
            assertThrows<IllegalArgumentException> { invoke(function("{'unknown': 1}", "item")) }
            assertThrows<IllegalArgumentException> { invoke(function("1", "items")) }
        }

    @Test
    fun `reports syntax and runtime source locations`(): Unit =
        runBlocking {
            val syntax = assertThrows<JexlException> { function("var x = 1;\nreturn +;") }
            assertEquals("Query/result", syntax.info.name)
            assertEquals(2, syntax.info.line)
            val runtime = assertThrows<JexlException> { invoke(function("var x = 1;\nreturn missing.value;")) }
            assertEquals("Query/result", runtime.info.name)
            assertEquals(2, runtime.info.line)
        }

    @Test
    fun `rejects object construction pragmas and global assignments`() {
        for (script in listOf(
            "new('java.lang.String', 'value'); return 1;",
            "#pragma jexl.options '+safe'\nreturn 1;",
            "arg_x = 7; return arg_x;",
        )) {
            assertThrows<JexlException.Feature>(script) { function(script) }
        }
    }

    @Test
    fun `denies class access through bound objects and literal values`(): Unit =
        runBlocking {
            val input = world.schema.objectOf("Item") { "n" setTo 3 }
            val method = function("objectValue.getClass(); return 1;", objectRss = true)
            val deniedMethod = assertThrows<JexlException> { invoke(method, input) }
            assertEquals("Query/result", deniedMethod.info.name)
            assertTrue(deniedMethod.message.orEmpty().contains("getClass"), deniedMethod.message)
            val property = function("''.class; return 1;")
            assertThrows<JexlException.Property> { invoke(property) }
            assertEquals(3, invoke(function("objectValue.n", objectRss = true), input))
        }

    @Test
    fun `preserves error-valued input reads`(): Unit =
        runBlocking {
            val error = EngineErrorData.of(IllegalStateException("input failed"))
            val input = world.schema.objectOf("Item") { "n" setTo error }
            val exception = assertThrows<EngineErrorDataReadException> { invoke(function("objectValue.n", objectRss = true), input) }
            assertSame(error, exception.errorData)
            assertEquals(3, invoke(function("3", objectRss = true), input))
        }

    @Test
    fun `retains script locations and causes for subquery failures but preserves engine read errors`(): Unit =
        runBlocking {
            val failure = IllegalStateException("subquery failed")
            val failing = object : ResolutionExecutionContext {
                override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync = throw failure
            }
            val exception = assertThrows<JexlException> { invoke(function("var n = 1;\nreturn query('{ numbers }').numbers[0];"), context = failing) }
            assertEquals("Query/result", exception.info.name)
            assertEquals(2, exception.info.line)
            assertSame(failure, exception.cause)

            val error = EngineErrorData.of(failure)
            val errorValue = object : ResolutionExecutionContext {
                override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync = world.schema.objectOf("Query") { "numbers" setTo listOf(error) }
            }
            val read = assertThrows<EngineErrorDataReadException> { invoke(function("query('{ numbers }').numbers[0]"), context = errorValue) }
            assertSame(error, read.errorData)
        }

    @Test
    fun `reuses the script with isolated invocation contexts`(): Unit =
        runBlocking {
            val function = function("var total = 0; for (var n : [arg_x, arg_y]) { total += n; } return total;")
            val outputs = (1..20).map { n -> async { invoke(function, arguments = mapOf("arg_x" to n, "arg_y" to n)) } }.awaitAll()
            assertEquals((1..20).map { it * 2 }, outputs)
        }

    @Test
    fun `concurrent queries can exceed the default IO pool without starving subqueries`(): Unit =
        runBlocking {
            withTimeout(10_000) {
                val invocations = 80
                val entered = AtomicInteger()
                val allEntered = CompletableDeferred<Unit>()
                val context = object : ResolutionExecutionContext {
                    override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync {
                        if (entered.incrementAndGet() == invocations) allEntered.complete(Unit)
                        allEntered.await()
                        return world.schema.objectOf("Query") { "numbers" setTo listOf(7) }
                    }
                }
                val function = function("query('{ numbers }').numbers[0]")
                val outputs = (1..invocations).map { async { invoke(function, context = context) } }.awaitAll()
                assertEquals(List(invocations) { 7 }, outputs)
            }
        }

    @Test
    fun `executes nested subqueries with integer variables aliases and a single engine thread`() {
        val scripts = mapOf(
            "outer" to "query('{ alias: middle(value: \$value) }', {'value': 6}).alias + 1",
            "middle" to "query('{ seed }').seed + value",
            "seed" to "7",
        )
        val engineWorld = TestWorld.fromSDL(
            "type Query { outer: Int middle(value: Int): Int seed: Int }",
            fieldResolvers = { schemas ->
                scripts.map { (name, script) ->
                    val field = schemas.loweredSchema.requireObjectField("Query", name)
                    field to FieldResolverDefinition.of(
                        objectFragment = schemas.loweredSchema.emptyFragmentOf("Query"),
                        queryFragment = null,
                        function = createJEXLNonselectiveFieldResolverFunction(schemas, field, script),
                    )
                }.toMap()
            },
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(engineWorld.assumptions).resolve(engineWorld.schemas.operationSelectionsFrom("{ outer }"), dispatcher)
            assertEquals(14, root.getCell(root.keys.single()).value.get())
        }
    }

    @Test
    fun `concurrent real subqueries retain arguments and execute each nested resolver once`() {
        for (engineThreads in listOf(1, 4)) {
            val invocations = 16
            val entered = AtomicInteger()
            val allEntered = CompletableDeferred<Unit>()
            val engineWorld = TestWorld.fromSDL(
                "type Query { outer(value: Int): Int middle(value: Int): Int seed(value: Int): Int }",
                fieldResolvers = { schemas ->
                    listOf("outer", "middle", "seed").associate { name ->
                        val field = schemas.loweredSchema.requireObjectField("Query", name)
                        val function: NonselectiveFieldResolverFunction = if (name == "seed") {
                            { _, _, arguments, _, _ ->
                                if (entered.incrementAndGet() == invocations) allEntered.complete(Unit)
                                allEntered.await()
                                arguments.fieldValues["value"]
                            }
                        } else {
                            val script = if (name == "outer") {
                                "query('{ middle(value: ${'$'}n) }', {'n': value}).middle + 1"
                            } else {
                                "query('{ seed(value: ${'$'}n) }', {'n': value}).seed + value"
                            }
                            createJEXLNonselectiveFieldResolverFunction(schemas, field, script)
                        }
                        field to FieldResolverDefinition.of(
                            objectFragment = schemas.loweredSchema.emptyFragmentOf("Query"),
                            queryFragment = null,
                            function = function,
                        )
                    }
                },
            )
            Executors.newFixedThreadPool(engineThreads).asCoroutineDispatcher().use { dispatcher ->
                val query = (1..invocations).joinToString(" ", "{ ", " }") { "r$it: outer(value: $it)" }
                val root = SharedOperationContext.create(engineWorld.assumptions).resolve(engineWorld.schemas.operationSelectionsFrom(query), dispatcher)
                val outputs = root.keys.associate { key ->
                    assertIs<Int>(assertIs<Arguments.Resolved>(key.arguments).fieldValues.getValue("value")) to root.getCell(key).value.get()
                }
                assertEquals((1..invocations).associateWith { it * 2 + 1 }, outputs)
                assertEquals(invocations, root.keys.size)
                assertEquals(invocations, entered.get())
            }
        }
    }

    @Test
    fun `cancels blocked subqueries and active scripts`(): Unit =
        runBlocking {
            withTimeout(5_000) {
                val started = CompletableDeferred<Unit>()
                val finished = CompletableDeferred<Unit>()
                val context = object : ResolutionExecutionContext {
                    override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync {
                        started.complete(Unit)
                        try {
                            awaitCancellation()
                        } finally {
                            finished.complete(Unit)
                        }
                    }
                }
                val blocked = launch { invoke(function("query('{ numbers }').numbers[0]"), context = context) }
                started.await()
                blocked.cancelAndJoin()
                finished.await()
                val spinningStarted = CompletableDeferred<Unit>()
                val signal = object : ResolutionExecutionContext {
                    override suspend fun resolveSelectionSet(selections: MaterializeSelectionForest): EngineObjectData.Sync {
                        spinningStarted.complete(Unit)
                        return world.schema.objectOf("Query")
                    }
                }
                val spinning = async { invoke(function("query('{ numbers }'); while (true) {} return 1;"), context = signal) }
                spinningStarted.await()
                spinning.cancelAndJoin()
                assertThrows<CancellationException> { spinning.await() }
            }
        }

    @Test
    fun `rejects reserved bindings`() {
        val invalidWorld = TestWorld.fromSDL("type Query { collision(query: Int): Int errorCollision(error: Int): Int refCollision(ref: Int): Int }")
        for (name in listOf("collision", "errorCollision", "refCollision")) {
            assertThrows<IllegalArgumentException> {
                createJEXLNonselectiveFieldResolverFunction(invalidWorld.schemas, invalidWorld.schema.requireObjectField("Query", name), "1")
            }
        }
    }

    @Test
    fun `binds typed input values and returns IDs`(): Unit =
        runBlocking {
            val inputs = TestWorld.fromSDL("type Query { result(id: ID, enabled: Boolean, input: Input): ID } input Input { values: [Int] }")
            val field = inputs.schema.requireObjectField("Query", "result")
            val function = createJEXLNonselectiveFieldResolverFunction(inputs.schemas, field, "enabled && input.values[0] == 7 ? id : null")
            val arguments = Arguments.Resolved.of(field, mapOf("id" to "item-1", "enabled" to true, "input" to mapOf("values" to listOf(7))))
            val empty = inputs.schema.objectOf("Query")
            assertEquals("item-1", function(empty, empty, arguments, null, ResolutionExecutionContext.Unsupported))
            val number = createJEXLNonselectiveFieldResolverFunction(inputs.schemas, field, "42L")
            assertEquals("42", number(empty, empty, arguments, null, ResolutionExecutionContext.Unsupported))
            val invalid = createJEXLNonselectiveFieldResolverFunction(inputs.schemas, field, "true")
            assertThrows<IllegalArgumentException> { invalid(empty, empty, arguments, null, ResolutionExecutionContext.Unsupported) }
        }

    @Test
    fun `Node scripts preserve IDs and distinguish missing bodies from errors`(): Unit =
        runBlocking {
            val nodes = TestWorld.fromDSL("type User implements Node @nodeResolver(ids: [\"known\"], script: \"{'n': 7}\") { id: ID! n: Int }")
            val type = nodes.schema.requireType("User") as ViaductSchema.Object

            suspend fun evaluate(script: String): Any? = createJEXLNodeResolverFunction(nodes.schemas, type, script)("known", selectionForestOf(), ResolutionExecutionContext.Unsupported)
            val body = assertIs<EngineObjectData.Sync>(evaluate("{'n': 7}"))
            assertEquals("known", body.get("id"))
            assertEquals(7, body.get("n"))
            assertNull(evaluate("null"))
            assertIs<EngineErrorData>(evaluate("error"))
            for (script in listOf("{'id': 'other'}", "7", "{'__typename': 'Query'}")) {
                assertThrows<IllegalArgumentException>(script) { evaluate(script) }
            }
            assertThrows<JexlException> { evaluate("query('{}')") }
        }

    @Test
    fun `variable scripts reuse isolated argument bindings and normalize integer inputs`(): Unit =
        runBlocking {
            val field = world.schema.requireObjectField("Query", "result")
            val provider = createJEXLVariablesProviderFunction(
                field,
                setOf("double", "bounds", "absent"),
                "var n = arg_x * 2L; return {'double': n, 'bounds': [-2147483648L, 2147483647H], 'absent': arg_y};"
            )
            val outputs = (1..20).map { n ->
                async { provider(Arguments.Resolved.of(field, mapOf("arg_x" to n))) }
            }.awaitAll()
            assertEquals((1..20).map { n -> mapOf("double" to n * 2, "bounds" to listOf(Int.MIN_VALUE, Int.MAX_VALUE), "absent" to null) }, outputs)
        }

    @Test
    fun `variable scripts read and return nested input data without mutating arguments`(): Unit =
        runBlocking {
            val inputs = TestWorld.fromSDL(
                """
                type Query { result(input: Input, optional: Int): Int }
                input Input { count: Int flags: [Boolean] weights: [Float] label: String id: ID kind: Kind }
                enum Kind { FIRST SECOND }
                """.trimIndent()
            )
            val field = inputs.schema.requireObjectField("Query", "result")
            val input = mutableMapOf("count" to 4, "flags" to mutableListOf(true, false), "weights" to listOf(1.5, 2.5), "label" to "label", "id" to "id-1", "kind" to "FIRST")
            val arguments = Arguments.Resolved.of(field, mapOf("input" to input))
            val provider = createJEXLVariablesProviderFunction(
                field,
                setOf("copied", "values", "flags", "weights", "absent"),
                "{'copied': input, 'values': [input.count + 1, null], 'flags': [true, false], 'weights': [1.5d, 2.5d], 'absent': optional}"
            )
            assertEquals(mapOf("copied" to input, "values" to listOf(5, null), "flags" to listOf(true, false), "weights" to listOf(1.5, 2.5), "absent" to null), provider(arguments))
            for (mutation in listOf("local.count = 99", "local.flags[0] = false")) {
                val mutating = createJEXLVariablesProviderFunction(field, setOf("value"), "var local = input; $mutation; return {'value': input};")
                assertThrows<JexlException> { mutating(arguments) }
            }
            assertEquals(4, input["count"])
            assertEquals(listOf(true, false), input["flags"])
        }

    @Test
    fun `variable scripts require exactly the declared names and error free input values`(): Unit =
        runBlocking {
            val field = world.schema.requireObjectField("Query", "result")
            val arguments = Arguments.Resolved.of(field, emptyMap())
            for ((script, diagnostic) in listOf(
                "error" to "returned error",
                "null" to "requires a variable map",
                "[1, 2]" to "requires a variable map",
                "{'other': 1}" to "Missing keys: [value]. Extra keys: [other]",
                "{'value': 1, 'other': 2}" to "Extra keys: [other]",
                "{'value': error}" to ".value requires an input value",
                "{'value': {'nested': [1, error]}}" to ".value.nested[1] requires an input value",
                "{'value': 2147483648L}" to "outside GraphQL Int range",
                "{'value': [-2147483649L]}" to "outside GraphQL Int range",
                "{'value': {1: 2}}" to "input-object keys must be strings",
            )) {
                val provider = createJEXLVariablesProviderFunction(field, setOf("value"), script)
                val failure = assertThrows<IllegalArgumentException>(script) { provider(arguments) }
                assertTrue(failure.message.orEmpty().contains("Query/result.vars"), failure.message)
                assertTrue(failure.message.orEmpty().contains(diagnostic), failure.message)
            }
        }

    @Test
    fun `variable scripts expose neither fragment inputs nor a query callable`(): Unit =
        runBlocking {
            val field = world.schema.requireObjectField("Query", "result")
            for (script in listOf("objectValue", "queryValue", "query('{}')")) {
                val provider = createJEXLVariablesProviderFunction(field, setOf("value"), script)
                assertThrows<JexlException> { provider(Arguments.Resolved.of(field, emptyMap())) }
            }
            assertThrows<IllegalArgumentException> { createJEXLVariablesProviderFunction(field, emptySet(), "{:}") }
            val collision = TestWorld.fromSDL("type Query { result(error: Int): Int }").schema.requireObjectField("Query", "result")
            assertThrows<IllegalArgumentException> { createJEXLVariablesProviderFunction(collision, setOf("value"), "{'value': 1}") }
        }

    @Test
    fun `variable script syntax and runtime failures retain coordinates and source lines`(): Unit =
        runBlocking {
            val field = world.schema.requireObjectField("Query", "result")
            val syntax = assertThrows<JexlException> { createJEXLVariablesProviderFunction(field, setOf("value"), "var n = 1;\nvar x = ;") }
            assertEquals("Query/result.vars", syntax.info.name)
            assertEquals(2, syntax.info.line)
            val provider = createJEXLVariablesProviderFunction(field, setOf("value"), "var n = 1;\nreturn {'value': missing};")
            val runtime = assertThrows<JexlException> { provider(Arguments.Resolved.of(field, emptyMap())) }
            assertEquals("Query/result.vars", runtime.info.name)
            assertEquals(2, runtime.info.line)
            for (script in listOf("new('java.lang.String', 'value')", "#pragma jexl.strict false\n{'value': 1}", "arg_x = 2; {'value': arg_x}")) {
                assertThrows<JexlException> { createJEXLVariablesProviderFunction(field, setOf("value"), script) }
            }
        }

    private fun function(
        script: String,
        fieldName: String = "result",
        objectRss: Boolean = false,
        queryRss: Boolean = false,
    ): NonselectiveFieldResolverFunction = createJEXLNonselectiveFieldResolverFunction(world.schemas, world.schema.requireObjectField("Query", fieldName), script, objectRss, queryRss)

    private suspend fun invoke(
        function: NonselectiveFieldResolverFunction,
        input: EngineObjectData.Sync = world.schema.objectOf("Item"),
        queryValue: EngineObjectData.Sync = world.schema.objectOf("Query"),
        arguments: Map<String, Any?> = emptyMap(),
        context: ResolutionExecutionContext = ResolutionExecutionContext.Unsupported,
    ): Any? = function(input, queryValue, Arguments.Resolved.of(world.schema.requireObjectField("Query", "result"), arguments), null, context)
}
