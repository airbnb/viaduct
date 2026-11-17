@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.testing

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.apache.commons.jexl3.JexlException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.registry.CheckerInput
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.SharedOrchestrationTask
import viaduct.engine.runtime2.resolution.resolve
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class ResolverTestDslCheckerTest {
    @Test
    fun `custom field and type denial messages reach checked consumers`() {
        for (typeChecker in listOf(false, true)) {
            val message = if (typeChecker) "type blocked" else "field blocked"
            val checker = "@checker(script: \"denyWithMessage('$message')\")"
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  item: Item @resolver(script: "{'value': 7}") ${if (typeChecker) "" else checker}
                  consume: Int @resolver(of: "item { value }", script: "objectValue.item.value")
                }
                type Item ${if (typeChecker) checker else ""} { value: Int }
                """.trimIndent(),
            )
            val result = resolve(world, "{ consume }")
            val item = assertIs<ObjectEngineResult>(result.value("item"))
            val denial = assertIs<CheckerResult.Error>(if (typeChecker) item.typeCheckerResult.get() else result.cell("item").fieldCheckerResult.get())
            assertEquals(message, assertIs<IllegalStateException>(denial.error).message)
            assertSame(denial.error, assertIs<ErrorEngineResult>(result.value("consume")).errorData.cause)
            assertTrue(denial.isErrorForResolver(CheckerResultContext()))
            assertSame(denial, denial.combine(denial))
        }
    }

    @Test
    fun `denial messages can use arguments and remain local to each invocation`(): Unit =
        runBlocking {
            val world = TestWorld.fromDSL("extend type Query { checked(reason: String): Int }")
            val field = world.schema.requireObjectField("Query", "checked")
            val function = createJEXLFieldCheckerFunction(world.schemas, field, "denyWithMessage(reason)")
            for (message in listOf("first denial", "second denial", "")) {
                val result = function(Arguments.Resolved.of(field, mapOf("reason" to message)), emptyMap(), ResolutionExecutionContext.Unsupported)
                assertEquals(message, assertIs<CheckerResult.Error>(result).error.message)
            }
            val default = createJEXLFieldCheckerFunction(world.schemas, field, "deny")
            val result = default(Arguments.Resolved.of(field, emptyMap()), emptyMap(), ResolutionExecutionContext.Unsupported)
            assertEquals("Query/checked.checker denied access", assertIs<CheckerResult.Error>(result).error.message)
        }

    @Test
    fun `denial message validation retains script locations and rejects argument collisions`(): Unit =
        runBlocking {
            val world = TestWorld.fromDSL("extend type Query { checked: Int }")
            val field = world.schema.requireObjectField("Query", "checked")
            for (message in listOf("null", "7")) {
                val function = createJEXLFieldCheckerFunction(world.schemas, field, "var ignored = 1;\nreturn denyWithMessage($message);")
                val failure = assertThrows<JexlException> { function(Arguments.Resolved.of(field, emptyMap()), emptyMap(), ResolutionExecutionContext.Unsupported) }
                assertEquals("Query/checked.checker", failure.info.name)
                assertEquals(2, failure.info.line)
                assertEquals("denyWithMessage requires a String message", assertIs<IllegalArgumentException>(failure.cause).message)
            }
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL("extend type Query { checked(denyWithMessage: String): Int @checker(script: \"allow\") }")
            }
        }

    @Test
    fun `fragment free field checks enforce active passive and list inputs`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item @resolver(script: "{'passive': 11, 'list': [1, 2]}")
            }
            type Item {
              active: Int @resolver(script: "7") @checker(script: "allow")
              passive: Int @checker(script: "deny")
              list: [Int] @checker(script: "deny")
              allowed: Int @resolver(of: "active", script: "objectValue.active")
              denied: Int @resolver(of: "passive", script: "objectValue.passive")
              deniedList: Int @resolver(of: "list", script: "objectValue.list[0]")
            }
            """.trimIndent(),
        )
        val item = assertIs<ObjectEngineResult>(resolve(world, "{ item { allowed denied deniedList } }").value("item"))
        assertEquals(7, item.value("allowed"))
        for ((source, consumer) in listOf("passive" to "denied", "list" to "deniedList")) {
            val denial = assertIs<CheckerResult.Error>(item.cell(source).fieldCheckerResult.get())
            assertSame(denial.error, assertIs<ErrorEngineResult>(item.value(consumer)).errorData.cause)
        }
        assertSame(CheckerResult.Success, item.cell("active").fieldCheckerResult.get())
    }

    @Test
    fun `type and Query checkers support zero inputs and explicit empty named pairs`() {
        for (outcome in listOf("allow", "deny")) {
            val world = TestWorld.fromDSL(
                """
                extend type Query @checker(script: "inputs.isEmpty() ? allow : deny") {
                  item: Item @resolver(script: "{'value': 3}")
                  consume: Int @resolver(of: "item { value }", script: "objectValue.item.value")
                }
                type Item @checker(inputs: [{name: "empty"}], script: "inputs['empty'].objectValue.size() == 1 && inputs['empty'].objectValue['**FROM_RSS**'] && inputs['empty'].queryValue.size() == 1 && inputs['empty'].queryValue['**FROM_RSS**'] ? $outcome : deny") {
                  value: Int
                }
                """.trimIndent(),
            )
            val result = resolve(world, "{ consume }")
            assertSame(CheckerResult.Success, result.typeCheckerResult.get())
            val item = assertIs<ObjectEngineResult>(result.value("item"))
            if (outcome == "allow") {
                assertSame(CheckerResult.Success, item.typeCheckerResult.get())
                assertEquals(3, result.value("consume"))
            } else {
                val denial = assertIs<CheckerResult.Error>(item.typeCheckerResult.get())
                assertSame(denial.error, assertIs<ErrorEngineResult>(result.value("consume")).errorData.cause)
                assertTrue(denial.isErrorForResolver(CheckerResultContext()))
                assertSame(denial, denial.combine(denial))
            }
        }
    }

    @Test
    fun `named field pairs keep argument path field path and provider variables independent`(): Unit =
        runBlocking {
            val world = TestWorld.fromDSL(
                """
            extend type Query {
              item: Item @resolver(isSelective: true, script: "{'source': 5}")
              remote: Int @resolver(script: "11")
              echo(value: Int): Int @resolver(script: "value")
            }
            input Envelope { nested: Int }
            type Item {
              source: Int
              echo(value: Int): Int @resolver(script: "value")
              checked(seed: Int, input: Envelope): Int @resolver(script: "1") @checker(
                inputs: [
                  {name: "argument", of: "local: echo(value: ${'$'}v)", qf: "remote: echo(value: ${'$'}v)", variables: [{name: "v", fromArgument: ["input", "nested"]}]}
                  {name: "object", of: "alias: source local: echo(value: ${'$'}v)", qf: "remote: echo(value: ${'$'}v)", variables: [{name: "v", fromObjectField: ["alias"]}]}
                  {name: "query", of: "local: echo(value: ${'$'}v)", qf: "alias: remote remote: echo(value: ${'$'}v)", variables: [{name: "v", fromQueryField: ["alias"]}]}
                  {name: "provider", of: "local: echo(value: ${'$'}v)", qf: "remote: echo(value: ${'$'}v)", vars: {names: ["v"], script: "{'v': seed * 2}"}}
                  {name: "otherProvider", qf: "remote: echo(value: ${'$'}v)", vars: {names: ["v"], script: "{'v': seed * 3}"}}
                ]
                script: "seed == 2 && inputs.argument.objectValue.local == 7 && inputs.argument.queryValue.remote == 7 && inputs.object.queryValue.remote == 5 && inputs.query.objectValue.local == 11 && inputs.provider.objectValue.local == 4 && inputs.otherProvider.queryValue.remote == 6 ? allow : deny"
              )
            }
                """.trimIndent(),
            )
            val field = world.schema.requireObjectField("Item", "checked")
            val checker = assertNotNullFieldChecker(world, field)
            val target = ResolverTarget.FieldCheckerTarget(field)
            val variables = checker.fragmentTemplates.getValue("argument").variables
            val argument = assertIs<VariableDefinition.FromArgument>(variables.getValue(Arguments.Variable.of(target, "v")))
            assertEquals("input", argument.argument.name)
            assertEquals(listOf("nested"), argument.inputPath.map { it.name })
            for ((name, root) in listOf("object" to ProviderFragment.OBJECT, "query" to ProviderFragment.QUERY)) {
                val definition = assertIs<VariableDefinition.FromField>(checker.fragmentTemplates.getValue(name).variables.getValue(Arguments.Variable.of(target, "v")))
                assertEquals(root, definition.providerFragment)
                assertEquals(listOf("alias"), definition.responsePath)
                assertEquals(if (name == "object") "source" else "remote", definition.path.single().field.name)
            }
            assertEquals(setOf("argument:v", "object:v", "query:v", "provider:v", "otherProvider:v"), checker.variables.keys.mapTo(linkedSetOf()) { it.variableName })
            assertEquals(mapOf("provider:v" to 4, "otherProvider:v" to 6), checker.provideVariables(Arguments.Resolved.of(field, mapOf("seed" to 2))))
            val seen = mutableListOf<Map<String, CheckerInput>>()
            val observer = object : ResolutionObserver {
                override val onCheckerInvocation = { _: CheckerInvocationObservation, inputs: Map<String, CheckerInput> ->
                    seen += inputs
                    Unit
                }
            }
            val result = resolve(world, "{ item { checked(seed: 2, input: {nested: 7}) } }", observer)
            val item = assertIs<ObjectEngineResult>(result.value("item"))
            assertSame(CheckerResult.Success, item.cell("checked").fieldCheckerResult.get())
            assertEquals(1, seen.size)
            assertEquals(setOf("alias", "local"), seen.single().getValue("object").objectValue.getSelections().toSet())
            assertEquals(11, seen.single().getValue("query").queryValue.get("alias"))
            assertTrue(seen.single().getValue("otherProvider").objectValue.getSelections().none())
            assertNull(world.schemas.graphQLSchema.getObjectType("Item").getFieldDefinition("checked").getDirective("checker"))
        }

    @Test
    fun `pair local guards retain independent argument and provider bindings`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              source: Int @resolver(script: "3")
              checked(enabled: Boolean!): Int @resolver(script: "1") @checker(
                inputs: [
                  {name: "first", of: "source @include(if: ${'$'}v)", variables: [{name: "v", fromArgument: ["enabled"]}]}
                  {name: "second", qf: "source @include(if: ${'$'}v)", vars: {names: ["v"], script: "{'v': !enabled}"}}
                ]
                script: "enabled ? (inputs.first.objectValue.source == 3 && inputs.second.queryValue.size() == 1 ? allow : deny) : (inputs.first.objectValue.size() == 1 && inputs.second.queryValue.source == 3 ? allow : deny)"
              )
            }
            """.trimIndent(),
        )
        val result = resolve(world, "{ included: checked(enabled: true) excluded: checked(enabled: false) }")
        val cells = result.keys.filter { it.field.name == "checked" }.map { result.getCell(it) }
        assertEquals(2, cells.size)
        cells.forEach { assertSame(CheckerResult.Success, it.fieldCheckerResult.get()) }
    }

    @Test
    fun `type pairs use explicit paths and pair local providers without field arguments`(): Unit =
        runBlocking {
            val world = TestWorld.fromDSL(
                """
            extend type Query {
              item: Item @resolver(script: "{'source': 5}")
              remote: Int @resolver(script: "9")
              echo(value: Int): Int @resolver(script: "value")
            }
            type Item @checker(
              inputs: [
                {name: "object", of: "source", qf: "value: echo(value: ${'$'}n)", variables: [{name: "n", fromObjectField: ["source"]}]}
                {name: "query", qf: "remote value: echo(value: ${'$'}n)", variables: [{name: "n", fromQueryField: ["remote"]}]}
                {name: "computed", qf: "value: echo(value: ${'$'}n)", vars: {names: ["n"], script: "{'n': 4L}"}}
              ]
              script: "inputs.object.queryValue.value == 5 && inputs.query.queryValue.value == 9 && inputs.computed.queryValue.value == 4 ? allow : deny"
            ) { source: Int }
                """.trimIndent(),
            )
            val type = world.schema.requireType("Item") as ViaductSchema.Object
            assertNull(world.schemas.graphQLSchema.getObjectType("Item").getDirective("checker"))
            val checker = checkNotNull(world.resolverRegistry.typeChecker(type))
            assertEquals(mapOf("computed:n" to 4), checker.provideVariables())
            val result = resolve(world, "{ item { source } }")
            assertSame(CheckerResult.Success, assertIs<ObjectEngineResult>(result.value("item")).typeCheckerResult.get())
        }

    @Test
    fun `checker reads bypass field and type denials while ordinary consumers enforce them`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item @resolver(script: "{'value': 7}") @checker(script: "deny")
              trigger: Int @resolver(script: "1") @checker(inputs: [{name: "raw", of: "item { value }"}], script: "inputs.raw.objectValue.item.value == 7 ? allow : deny")
              consume: Int @resolver(of: "item { value }", script: "objectValue.item.value")
            }
            type Item @checker(script: "deny") { value: Int }
            """.trimIndent(),
        )
        val result = resolve(world, "{ trigger consume }")
        assertSame(CheckerResult.Success, result.cell("trigger").fieldCheckerResult.get())
        assertEquals(1, result.value("trigger"))
        val item = assertIs<ObjectEngineResult>(result.value("item"))
        val typeDenial = assertIs<CheckerResult.Error>(item.typeCheckerResult.get())
        val fieldDenial = assertIs<CheckerResult.Error>(result.cell("item").fieldCheckerResult.get())
        val cause = assertIs<ErrorEngineResult>(result.value("consume")).errorData.cause
        assertTrue(cause === fieldDenial.error || cause === typeDenial.error, "A checked read may fail before both denials complete")
        assertSame(typeDenial, typeDenial.combine(fieldDenial))
    }

    @Test
    fun `checker only raw demand restores checks at an active value resolver boundary`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              protected: Int @resolver(script: "3") @checker(script: "deny")
              raw: Int @resolver(script: "4") @checker(script: "deny")
              derived: Int @resolver(of: "protected", script: "objectValue.protected") @checker(script: "deny")
              checked: Int @resolver(script: "1") @checker(inputs: [{name: "input", of: "raw derived"}], script: "inputs.input.objectValue.raw == 4 ? allow : deny")
            }
            """.trimIndent(),
        )
        val result = resolve(world, "{ checked }")
        assertSame(CheckerResult.Success, result.cell("checked").fieldCheckerResult.get())
        assertNull(result.cell("raw").fieldCheckerResult.get())
        assertNull(result.cell("derived").fieldCheckerResult.get())
        val denial = assertIs<CheckerResult.Error>(result.cell("protected").fieldCheckerResult.get())
        assertSame(denial.error, assertIs<ErrorEngineResult>(result.value("derived")).errorData.cause)
    }

    @Test
    fun `checker queries retain raw reads and checked value resolver boundaries`() {
        for (typeChecker in listOf(false, true)) {
            val script = "var values = query('{ raw item { value } derived }'); return values.raw == 4 && values.item.value == 7 ? allow : deny;"
            val checker = "@checker(script: \"$script\")"
            val world = TestWorld.fromDSL(
                """
                extend type Query ${if (typeChecker) checker else ""} {
                  checked: Int @resolver(script: "1") ${if (typeChecker) "" else checker}
                  raw: Int @resolver(script: "4") @checker(script: "deny")
                  item: Item @resolver(script: "{'value': 7}") @checker(script: "deny")
                  protected: Int @resolver(script: "3") @checker(script: "deny")
                  derived: Int @resolver(of: "protected", script: "objectValue.protected") @checker(script: "deny")
                }
                type Item @checker(script: "deny") { value: Int }
                """.trimIndent(),
            )
            val nested = ConcurrentLinkedQueue<ObjectEngineResult>()
            val observer = object : ResolutionObserver {
                override val onQueryOERPrepared: (SharedOrchestrationTask<*>, Int?) -> Unit = { task, _ ->
                    if (task.creator != null && task.objectOER.occurrence.target.type.name == "Query") nested += task.objectOER.occurrence.target
                }
            }
            val result = resolve(world, "{ checked }", observer)
            assertSame(CheckerResult.Success, if (typeChecker) result.typeCheckerResult.get() else result.cell("checked").fieldCheckerResult.get())
            val child = nested.single()
            assertNull(child.cell("raw").fieldCheckerResult.get())
            assertNull(child.cell("item").fieldCheckerResult.get())
            assertNull(assertIs<ObjectEngineResult>(child.value("item")).typeCheckerResult.get())
            assertNull(child.cell("derived").fieldCheckerResult.get())
            val denial = assertIs<CheckerResult.Error>(child.cell("protected").fieldCheckerResult.get())
            assertSame(denial.error, assertIs<ErrorEngineResult>(child.value("derived")).errorData.cause)
        }
    }

    @Test
    fun `native field and type checkers reject nested mutations`() {
        for (typeChecker in listOf(false, true)) {
            val checker = "@checker(script: \"mutation('{ update }'); allow\")"
            val world = TestWorld.fromDSL(
                """
                extend type Query ${if (typeChecker) checker else ""} {
                  checked: Int @resolver(script: "1") ${if (typeChecker) "" else checker}
                }
                type Mutation { update: Int @resolver(script: "7") }
                """.trimIndent(),
            )
            val invocations = ConcurrentLinkedQueue<String>()
            val observer = object : ResolutionObserver {
                override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { invocation ->
                    invocations += invocation.field.containingDef.name
                }
            }
            val result = resolve(world, "{ checked }", observer)
            val failure = assertThrows<JexlException> {
                if (typeChecker) result.typeCheckerResult.get() else result.cell("checked").fieldCheckerResult.get()
            }
            assertIs<UnsupportedOperationException>(failure.cause)
            assertTrue("Mutation" !in invocations)
        }
    }

    @Test
    fun `provider failures fail checker bindings without producing error valued variables`() {
        for (script in listOf("error", "{'n': error}", "{'n': {'nested': [error]}}")) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  dependency(value: Int): Int @resolver(script: "value")
                  checked: Int @resolver(script: "1") @checker(inputs: [{name: "input", of: "dependency(value: ${'$'}n)", vars: {names: ["n"], script: "$script"}}], script: "allow")
                  consume: Int @resolver(of: "checked", script: "objectValue.checked")
                }
                """.trimIndent(),
            )
            val result = resolve(world, "{ consume }")
            val failure = assertThrows<IllegalArgumentException> { result.cell("checked").fieldCheckerResult.get() }
            assertTrue(failure.message.orEmpty().contains("Query/checked.checker.inputs.input.vars"), failure.message)
            assertIs<ErrorEngineResult>(result.value("consume"))
        }
    }

    @Test
    fun `checker outcomes reject ordinary values and preserve runtime locations`(): Unit =
        runBlocking {
            val world = TestWorld.fromDSL("extend type Query { checked(seed: Int): Int }")
            val field = world.schema.requireObjectField("Query", "checked")
            for (source in listOf("true", "false", "null", "error", "1", "'allow'", "{:}")) {
                val function = createJEXLFieldCheckerFunction(world.schemas, field, source)
                val failure = assertThrows<IllegalArgumentException>(source) { function(Arguments.Resolved.of(field, emptyMap()), emptyMap(), ResolutionExecutionContext.Unsupported) }
                assertEquals("Query/checked.checker must return allow, deny, or denyWithMessage(...)", failure.message)
            }
            val syntax = assertThrows<JexlException> { createJEXLFieldCheckerFunction(world.schemas, field, "var n = 1;\nvar broken = ;") }
            assertEquals("Query/checked.checker", syntax.info.name)
            assertEquals(2, syntax.info.line)
            val function = createJEXLFieldCheckerFunction(world.schemas, field, "var n = seed;\nreturn missing;")
            val runtime = assertThrows<JexlException> { function(Arguments.Resolved.of(field, mapOf("seed" to 2)), emptyMap(), ResolutionExecutionContext.Unsupported) }
            assertEquals("Query/checked.checker", runtime.info.name)
            assertEquals(2, runtime.info.line)
            val type = world.schema.requireQueryTypeDef()
            val typeFailure = assertThrows<JexlException> { createJEXLTypeCheckerFunction(world.schemas, type, "missing")(emptyMap(), ResolutionExecutionContext.Unsupported) }
            assertEquals("Query.checker", typeFailure.info.name)
        }

    @Test
    fun `runtime failures remain exceptional checker results rather than explicit denials`() {
        for (typeChecker in listOf(false, true)) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  item: Item @resolver(script: "{'value': 7}") ${if (typeChecker) "" else "@checker(script: \"missing\")"}
                  consume: Int @resolver(of: "item { value }", script: "objectValue.item.value")
                }
                type Item ${if (typeChecker) "@checker(script: \"missing\")" else ""} { value: Int }
                """.trimIndent(),
            )
            val result = resolve(world, "{ consume }")
            val failure = assertThrows<JexlException> {
                if (typeChecker) {
                    assertIs<ObjectEngineResult>(result.value("item")).typeCheckerResult.get()
                } else {
                    result.cell("item").fieldCheckerResult.get()
                }
            }
            assertEquals(if (typeChecker) "Item.checker" else "Query/item.checker", failure.info.name)
            assertIs<ErrorEngineResult>(result.value("consume"))
        }
    }

    @Test
    fun `checker bindings and execution callables are read only`(): Unit =
        runBlocking {
            val world = TestWorld.fromDSL("extend type Query { checked(seed: Int): Int }")
            val field = world.schema.requireObjectField("Query", "checked")
            for (source in listOf(
                "var copy = inputs; copy.raw = 1; allow",
                "query.getClass()",
                "mutation.getClass()",
                "denyWithMessage.getClass()",
                "denyWithMessage.class",
                "denyWithMessage('blocked').error",
                "deny.error",
                "inputs.getClass()"
            )) {
                val function = createJEXLFieldCheckerFunction(world.schemas, field, source)
                assertThrows<JexlException>(source) { function(Arguments.Resolved.of(field, emptyMap()), emptyMap(), ResolutionExecutionContext.Unsupported) }
            }
        }

    @Test
    fun `Kotlin checkers coexist with DSL checkers and duplicate declarations are rejected`() {
        val source = "extend type Query { scripted: Int @checker(script: \"allow\") host: Int }"
        val world = TestWorld.fromDSL(source, fieldCheckers = { schemas ->
            val field = schemas.loweredSchema.requireObjectField("Query", "host")
            mapOf(field to FieldCheckerResolver.of(field, schemas.loweredSchema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success })
        })
        assertNotNullFieldChecker(world, world.schema.requireObjectField("Query", "scripted"))
        assertNotNullFieldChecker(world, world.schema.requireObjectField("Query", "host"))
        assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(source, fieldCheckers = { schemas ->
                val field = schemas.loweredSchema.requireObjectField("Query", "scripted")
                mapOf(field to FieldCheckerResolver.of(field, schemas.loweredSchema.requireQueryTypeDef()) { _, _, _ -> CheckerResult.Success })
            })
        }
        assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL("extend type Query @checker(script: \"allow\") { value: Int }", typeCheckers = { schemas ->
                val type = schemas.loweredSchema.requireQueryTypeDef()
                mapOf(type to TypeCheckerResolver.of(type, type) { _, _ -> CheckerResult.Success })
            })
        }
    }

    private fun assertNotNullFieldChecker(
        world: TestWorld,
        field: ViaductSchema.ObjectField
    ): FieldCheckerResolver = checkNotNull(world.resolverRegistry.fieldChecker(field))

    private fun resolve(
        world: TestWorld,
        document: String,
        observer: ResolutionObserver = object : ResolutionObserver {}
    ): ObjectEngineResult =
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            SharedOperationContext.create(world.assumptions, resolutionObserver = observer).resolve(world.schemas.operationSelectionsFrom(document), dispatcher)
        }

    private fun ObjectEngineResult.cell(name: String) = getCell(keys.single { it.field.name == name })

    private fun ObjectEngineResult.value(name: String) = cell(name).value.get()
}
