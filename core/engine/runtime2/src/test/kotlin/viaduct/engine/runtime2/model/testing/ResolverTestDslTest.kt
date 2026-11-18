@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.testing

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.apache.commons.jexl3.JexlException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.testing.ResolverTestDslConformance
import viaduct.engine.runtime2.model.ArgumentResolutionError
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineOutputData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.FromArgument
import viaduct.engine.runtime2.model.registry.FromField
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.resolve
import viaduct.engine.runtime2.schema.SourceSchemaAdapter
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class ResolverTestDslTest {
    @Test
    fun `node object bodies retain their fields while explicit references invoke node resolvers`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              body: User @resolver(script: "{'id': 'body', 'score': 7, 'friend': {'id': 'friend', 'score': 8}}")
              reference: User @resolver(script: "ref('node', {'id': 'one'})")
              mixed: [Node] @resolver(script: "[{'__typename': 'User', 'id': 'body', 'score': 7}, ref('node', {'id': 'one'}), null]")
            }
            type User implements Node @nodeResolver(ids: ["one"], script: "{'score': 9}") {
              id: ID!
              score: Int
              friend: User
            }
            """.trimIndent(),
        )
        val root = world.resolveQuery("{ body { score friend { score } } reference { score } mixed { ... on User { score } } }")

        fun ObjectEngineResult.value(name: String) = getCell(keys.single { it.field.name == name }).value.get()
        val body = assertIs<ObjectEngineResult>(root.value("body"))
        assertEquals(7, body.value("score"))
        assertEquals(8, assertIs<ObjectEngineResult>(body.value("friend")).value("score"))
        assertEquals(9, assertIs<ObjectEngineResult>(root.value("reference")).value("score"))
        val mixed = assertIs<ListEngineResult>(root.value("mixed"))
        assertEquals(listOf(7, 9, null), mixed.map { cell -> (cell.value.get() as? ObjectEngineResult)?.value("score") })
    }

    @Test
    fun `malformed outputs conform to the shared DSL contract`() {
        ResolverTestDslConformance.rejectedOutputs.forEach { case ->
            val world = TestWorld.fromDSL(case.schemaSDL)
            assertThrows<IllegalArgumentException>(case.name) {
                world.apply(world.schema.requireObjectField("Query", "value"))
            }
        }
    }

    @Test
    fun `variable declarations conform to the shared DSL contract`() {
        ResolverTestDslConformance.rejectedDeclarations.forEach { (name, source) ->
            assertThrows<IllegalArgumentException>(name) { TestWorld.fromDSL(source) }
        }
        ResolverTestDslConformance.acceptedDeclarations.forEach { (_, source) -> TestWorld.fromDSL(source) }
    }

    @Test
    fun `checker declarations conform to the shared DSL contract`() {
        ResolverTestDslConformance.rejectedCheckerDeclarations.forEach { (name, source) ->
            assertThrows<IllegalArgumentException>(name) { TestWorld.fromDSL(source) }
        }
        ResolverTestDslConformance.acceptedCheckerDeclarations.forEach { case ->
            val world = TestWorld.fromDSL(case.schemaSDL)
            val result = world.resolveQuery(case.query)
            assertEquals(7, result.getCell(result.keys.single { it.field.name == "checked" }).value.get(), case.name)
        }
    }

    @Test
    fun `shared bindings reach field checker and provider scripts`() {
        val events = ConcurrentLinkedQueue<String>()
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item(seed: Int): Item @resolver(
                of: "source(value: ${'$'}computed)"
                vars: {names: ["computed"], script: "events.add('provider'); {'computed': settings.base}"}
                script: "events.add('field'); {'value': objectValue.source + seed + (optional == null ? 0 : 100)}"
              )
              source(value: Int): Int @resolver(script: "value")
            }
            type Item @checker(
              inputs: [{name: "policy", qf: "source(value: ${'$'}n)", vars: {names: ["n"], script: "events.add('typeProvider'); {'n': settings.base}"}}]
              script: "events.add('type'); inputs.policy.queryValue.source == settings.base ? allow : deny"
            ) {
              value: Int @checker(
                inputs: [{name: "policy", qf: "source(value: ${'$'}n)", vars: {names: ["n"], script: "events.add('fieldProvider'); {'n': settings.base}"}}]
                script: "events.add('checker'); inputs.policy.queryValue.source == settings.base ? allow : deny"
              )
            }
            """.trimIndent(),
            scriptBindings = mapOf("events" to events, "settings" to mapOf("base" to 7), "optional" to null),
        )
        val result = world.resolveQuery("{ item(seed: 2) { value } }")
        val item = assertIs<ObjectEngineResult>(result.getCell(result.keys.single { it.field.name == "item" }).value.get())
        assertEquals(9, item.getCell(item.keys.single()).value.get())
        assertEquals(setOf("provider", "field", "typeProvider", "type", "fieldProvider", "checker").associateWith { 1 }, events.groupingBy { it }.eachCount())
    }

    @Test
    fun `bound helpers retain state across node and field invocations without leaking between worlds`() {
        val helper = ScriptBindingHelper()
        val source = """
            extend type Query {
              viewer: User @resolver(script: "ref('node', {'id': 'one'})")
              next(step: Int): Int @resolver(script: "helper(step) + helper.offset")
            }
            type User implements Node @nodeResolver(ids: ["one"], script: "{'score': helper.call(1)}") { id: ID! score: Int }
        """.trimIndent()
        val world = TestWorld.fromDSL(source, scriptBindings = mapOf("helper" to helper))
        val root = world.resolveQuery("{ viewer { score } }")
        val user = assertIs<ObjectEngineResult>(root.getCell(root.keys.single()).value.get())
        assertEquals(1, user.getCell(user.keys.single()).value.get())
        val next = world.schema.requireObjectField("Query", "next")
        assertEquals(13, world.apply(next, arguments = mapOf("step" to 2)))
        assertEquals(16, world.apply(next, arguments = mapOf("step" to 3)))
        assertEquals(6, helper.count.get())
        val other = TestWorld.fromDSL(source, scriptBindings = mapOf("helper" to ScriptBindingHelper()))
        assertEquals(12, other.apply(other.schema.requireObjectField("Query", "next"), arguments = mapOf("step" to 2)))
        assertEquals(6, helper.count.get())
    }

    @Test
    fun `bindings cannot replace reserved names or field arguments`() {
        val source = "extend type Query { value(seed: Int): Int @resolver(script: \"seed\") }"
        for (name in listOf("query", "mutation", "objectValue", "queryValue", "error", "ref", "id", "inputs", "allow", "deny", "denyWithMessage", "seed")) {
            val failure = assertThrows<IllegalArgumentException> { TestWorld.fromDSL(source, scriptBindings = mapOf(name to null)) }
            kotlin.test.assertContains(failure.message.orEmpty(), "colliding")
        }
    }

    @Test
    fun `bound helpers retain class restrictions and script failure locations`() {
        for (script in listOf("helper.getClass(); 1", "helper.class; 1", "helper.fail()")) {
            val world = TestWorld.fromDSL(
                "extend type Query { value: Int @resolver(script: \"$script\") }",
                scriptBindings = mapOf("helper" to ScriptBindingHelper()),
            )
            val failure = assertThrows<JexlException> { world.apply(world.schema.requireObjectField("Query", "value")) }
            assertEquals("Query/value", failure.info.name)
            assertEquals(1, failure.info.line)
            if (script == "helper.fail()") {
                kotlin.test.assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it is IllegalStateException && it.message == "binding failed" })
            }
        }
    }

    class ScriptBindingHelper {
        val count = AtomicInteger()
        val offset: Int = 10

        fun call(step: Int): Int = count.addAndGet(step)

        fun fail(): Int = throw IllegalStateException("binding failed")
    }

    @Test
    fun `nested queries accept ordinary variable values and named fragment documents`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              outer(enabled: Boolean): String @resolver(script: "var result = query('fragment Main on Query { selected: read(id: ${'$'}id, ratio: ${'$'}ratio, mode: ${'$'}mode, data: ${'$'}data) @include(if: ${'$'}show) { ...Details } } fragment Details on Payload { label sum active }', {'id': 'user-1', 'ratio': 1.25d, 'mode': 'ON', 'show': enabled, 'data': {'numbers': [7L, null, 2H], 'label': 'nested', 'active': true}}); return enabled ? (result.selected.sum == 9 && result.selected.active ? result.selected.label : 'wrong') : (result.isEmpty() ? 'skipped' : 'wrong');")
              read(id: ID, ratio: Float, mode: Mode, data: Envelope): Payload @resolver(script: "{'label': id + ':' + mode + ':' + data.label + ':' + ratio, 'sum': data.numbers[0] + data.numbers[2], 'active': data.active}")
            }
            enum Mode { ON OFF }
            input Envelope { numbers: [Int] label: String active: Boolean }
            type Payload { label: String sum: Int active: Boolean }
            """.trimIndent(),
        )
        val result = world.resolveQuery("{ yes: outer(enabled: true) no: outer(enabled: false) }")
        val values = result.keys.associate { key ->
            assertIs<Boolean>(assertIs<Arguments.Resolved>(key.arguments).fieldValues.getValue("enabled")) to assertIs<String>(result.getCell(key).value.get())
        }
        assertEquals(mapOf(true to "user-1:ON:nested:1.25", false to "skipped"), values)
    }

    @Test
    fun `node scripts execute queries while preserving selective ID only lookup behavior`() {
        for (selective in listOf(true, false)) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  viewer: User @resolver(script: "ref('node', {'id': 'one'})")
                  score(id: ID): Int @resolver(script: "id == 'one' ? 7 : 0") @checker(script: "allow")
                }
                type User implements Node @nodeResolver(ids: ["one"], isSelective: $selective, script: "{'score': query('fragment Main on Query { value: score(id: ${'$'}key) }', {'key': id}).value}") {
                  id: ID!
                  score: Int
                }
                """.trimIndent(),
            )
            val invocations = ConcurrentLinkedQueue<String>()
            val observer = object : ResolutionObserver {
                override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                    invocations += observation.field.name
                }
            }
            val result = world.resolveQuery("{ viewer { id score } }", observer)
            val user = assertIs<ObjectEngineResult>(result.getCell(result.keys.single()).value.get())
            assertEquals(7, user.getCell(user.keys.single { it.field.name == "score" }).value.get())
            assertEquals(1, invocations.count { it == "score" })
            invocations.clear()
            world.resolveQuery("{ viewer { id } }", observer)
            assertEquals(if (selective) 0 else 1, invocations.count { it == "score" })
        }
    }

    @Test
    fun `nested mutations retain ordered alias occurrences and Query execution from Mutation`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result: [Int] @resolver(script: "var result = mutation('fragment Main on Mutation { first: update(value: ${'$'}n) { ...Values } second: update(value: ${'$'}n) { ...Values } third: update(value: 2) { ...Values } } fragment Values on Payload { value source }', {'n': 1L}); return [result.first.value, result.second.value, result.third.value, result.third.source];")
              source: Int @resolver(script: "9")
            }
            type Mutation { update(value: Int): Payload @resolver(script: "{'value': value, 'source': query('{ source }').source}") }
            type Payload { value: Int source: Int }
            """.trimIndent(),
        )
        val effects = ConcurrentLinkedQueue<Int>()
        val observer = object : ResolutionObserver {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                if (observation.field.containingDef.name == "Mutation") {
                    effects += assertIs<Int>(assertIs<Arguments.Resolved>(observation.arguments).fieldValues.getValue("value"))
                }
            }
        }
        val root = world.resolveQuery("{ result }", observer)
        val result = assertIs<ListEngineResult>(root.getCell(root.keys.single()).value.get())
        assertEquals(listOf(1, 1, 2, 9), result.map { it.value.get() })
        assertEquals(listOf(1, 1, 2), effects.toList())
    }

    @Test
    fun `explicit query and mutation result objects remain valid resolver outputs`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              fromQuery: Item @resolver(script: "query('{ source { value } }').source")
              fromMutation: Item @resolver(script: "mutation('{ update { value } }').update")
              source: Item @resolver(script: "{'value': 7}")
            }
            type Mutation { update: Item @resolver(script: "{'value': 8}") }
            type Item { value: Int }
            """.trimIndent(),
        )
        val result = world.resolveQuery("{ fromQuery { value } fromMutation { value } }")
        for ((field, expected) in listOf("fromQuery" to 7, "fromMutation" to 8)) {
            val item = assertIs<ObjectEngineResult>(result.getCell(result.keys.single { it.field.name == field }).value.get())
            assertEquals(expected, item.getCell(item.keys.single()).value.get())
        }
    }

    @Test
    fun `selective projection cannot discard an invalid RSS object from output`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item @resolver(of: "source { value }", script: "{'value': 1, 'child': objectValue.source}")
              source: Item @resolver(script: "{'value': 7}")
            }
            type Item { value: Int child: Item }
            """.trimIndent(),
        )
        val input = world.schema.objectOf("Query") { "source" setTo world.schema.objectOf("Item") { "value" setTo 7 } }
        val (_, demand) = world.selectionsFrom("fragment Demand on Item { value }")
        val failure = assertThrows<IllegalArgumentException> { world.apply(world.schema.requireObjectField("Query", "item"), input = input, selections = demand) }
        kotlin.test.assertContains(failure.message.orEmpty(), "Query/item.child contains **FROM_RSS**")
    }

    @Test
    fun `selective scripts project nested polymorphic list output using canonical field names`() {
        for ((metadata, selective) in listOf("" to true, ", isSelective: false" to false, ", isSelective: true" to true)) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  items: [Entry] @resolver(script: "[{'__typename': 'Item', 'value': 1, 'extra': 2, 'child': {'kept': 3, 'extra': 4}}, {'__typename': 'Other', 'value': 5, 'other': 6, 'child': null}, null, error, ref('target')]"$metadata)
                  target: Entry @resolver(script: "null")
                }
                interface Entry { value: Int child: Child }
                type Item implements Entry { value: Int child: Child extra: Int }
                type Other implements Entry { value: Int child: Child other: Int }
                type Child { kept: Int extra: Int }
                """.trimIndent(),
            )
            val (_, demand) = world.selectionsFrom("fragment Demand on Entry { alias: value child { kept } ... on Other { other } }")
            val result = assertIs<List<*>>(world.apply(world.schema.requireObjectField("Query", "items"), selections = demand))
            val item = assertIs<EngineObjectData.Sync>(result[0])
            assertEquals(if (selective) setOf("value", "child") else setOf("value", "child", "extra"), item.getSelections().toSet())
            assertEquals(1, item.outputValue("value"))
            val child = assertIs<EngineObjectData.Sync>(item.outputValue("child"))
            assertEquals(if (selective) setOf("kept") else setOf("kept", "extra"), child.getSelections().toSet())
            assertEquals(3, child.outputValue("kept"))
            val other = assertIs<EngineObjectData.Sync>(result[1])
            assertEquals(setOf("value", "child", "other"), other.getSelections().toSet())
            assertEquals(6, other.outputValue("other"))
            assertNull(other.outputValue("child"))
            assertNull(result[2])
            assertIs<EngineErrorData>(result[3])
            assertEquals(listOf("target"), assertIs<RootFieldReferenceData>(result[4]).path.map { it.name })
            if (selective) {
                val empty = assertIs<List<*>>(world.apply(world.schema.requireObjectField("Query", "items")))
                assertEquals(emptySet(), assertIs<EngineObjectData.Sync>(empty.first()).getSelections().toSet())
            }
        }
    }

    @Test
    fun `selective scalar scripts retain simple null error and reference results`() {
        for (script in listOf("7", "null", "error", "ref('target')")) {
            val world = TestWorld.fromDSL(
                "extend type Query { value: Int @resolver(isSelective: true, script: \"$script\") target: Int @resolver(script: \"1\") }",
            )
            val value = world.apply(world.schema.requireObjectField("Query", "value"))
            when (script) {
                "7" -> assertEquals(7, value)
                "null" -> assertNull(value)
                "error" -> assertIs<EngineErrorData>(value)
                else -> assertIs<RootFieldReferenceData>(value)
            }
        }
    }

    @Test
    fun `selective field scripts retain argument providers both RSS inputs and nested query context`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item(seed: Int): Item @resolver(
                isSelective: true
                of: "local: echo(value: ${'$'}provided)"
                qf: "remote: echo(value: ${'$'}provided)"
                vars: {names: ["provided"], script: "{'provided': seed * 2}"}
                script: "{'value': objectValue.local + queryValue.remote + query('{ echo(value: 3) }').echo, 'extra': seed}"
              )
              echo(value: Int): Int @resolver(script: "value")
            }
            type Item { value: Int extra: Int }
            """.trimIndent(),
        )
        val result = world.resolveQuery("{ item(seed: 5) { value } }")
        val item = assertIs<ObjectEngineResult>(result.getCell(result.keys.single { it.field.name == "item" }).value.get())
        assertEquals(setOf("value"), item.keys.map { it.field.name }.toSet())
        assertEquals(23, item.getCell(item.keys.single()).value.get())
    }

    @Test
    fun `only selective node registration skips an ID only lookup`() {
        for ((metadata, selective) in listOf("" to true, ", isSelective: false" to false, ", isSelective: true" to true)) {
            val world = TestWorld.fromDSL(
                """
                extend type Query { viewer(id: ID!): User @resolver(script: "ref('node', {'id': id})") }
                type User implements Node @nodeResolver(ids: ["one"], script: "error"$metadata) { id: ID! }
                """.trimIndent(),
            )
            val root = world.resolveQuery("{ viewer(id: \"one\") { id } }")
            val value = root.getCell(root.keys.single()).value.get()
            if (selective) {
                val user = assertIs<ObjectEngineResult>(value)
                assertEquals(viaduct.engine.runtime2.model.IDEngineResult.of("one"), user.getCell(user.keys.single()).value.get())
            } else {
                assertIs<ErrorEngineResult>(value)
            }
        }
    }

    @Test
    fun `selective node scripts project owned demand and restore authoritative IDs`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query { viewer(id: ID!): User @resolver(script: "ref('node', {'id': id})") }
            type User implements Node @nodeResolver(
              ids: ["one", "two"]
              isSelective: true
              script: "{'child': {'kept': id == 'one' ? 1 : 2, 'extra': 3}, 'seed': 5, 'extra': 4}"
            ) {
              id: ID!
              child: Child
              seed: Int
              computed: Int @resolver(of: "seed", script: "objectValue.seed + 1")
              extra: Int
            }
            type Child { kept: Int extra: Int }
            """.trimIndent(),
        )
        val result = world.resolveQuery("{ a: viewer(id: \"one\") { id computed child { kept } } b: viewer(id: \"two\") { id computed child { kept } } }")
        val users = result.keys.associate { key ->
            assertIs<Arguments.Resolved>(key.arguments).fieldValues.getValue("id") to assertIs<ObjectEngineResult>(result.getCell(key).value.get())
        }
        for ((id, expected) in listOf("one" to 1, "two" to 2)) {
            val user = users.getValue(id)
            assertEquals(setOf("id", "child", "seed", "computed"), user.keys.map { it.field.name }.toSet())
            assertEquals(viaduct.engine.runtime2.model.IDEngineResult.of(id), user.getCell(user.keys.single { it.field.name == "id" }).value.get())
            assertEquals(6, user.getCell(user.keys.single { it.field.name == "computed" }).value.get())
            val child = assertIs<ObjectEngineResult>(user.getCell(user.keys.single { it.field.name == "child" }).value.get())
            assertEquals(setOf("kept"), child.keys.map { it.field.name }.toSet())
            assertEquals(expected, child.getCell(child.keys.single()).value.get())
        }
    }

    @Test
    fun `selective node scripts retain null and error outputs for nonempty demand`() {
        for (script in listOf("null", "error")) {
            val world = TestWorld.fromDSL(
                """
                extend type Query { viewer: User @resolver(script: "ref('node', {'id': 'one'})") }
                type User implements Node @nodeResolver(ids: ["one"], isSelective: true, script: "$script") { id: ID! score: Int }
                """.trimIndent(),
            )
            val result = world.resolveQuery("{ viewer { id score } }")
            val value = result.getCell(result.keys.single()).value.get()
            if (script == "null") assertNull(value) else assertIs<ErrorEngineResult>(value)
        }
    }

    @Test
    fun `scripts construct references at scalar object and nested list positions`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result(seed: Int): Container @resolver(script: "{'number': ref('namespace.number', {'value': seed, 'options': {'weights': [seed, null]}}), 'items': [[ref('item', {'seed': 3}), null]]}")
              namespace: Namespace @resolver(script: "error")
              item(seed: Int): Item @resolver(script: "{'value': seed}")
            }
            type Namespace { number(value: Int, options: Options): Int @resolver(script: "value") }
            input Options { weights: [Int] }
            type Container { number: Int items: [[Item]] }
            type Item { value: Int }
            """.trimIndent()
        )
        val field = world.schema.requireObjectField("Query", "result")
        val (_, demand) = world.selectionsFrom("fragment Demand on Container { number items { value } }")
        val result = assertIs<EngineObjectData.Sync>(world.apply(field, arguments = mapOf("seed" to 7), selections = demand))
        val number = assertIs<RootFieldReferenceData>(result.outputValue("number"))
        assertEquals(listOf("namespace", "number"), number.path.map { it.name })
        assertEquals(mapOf("value" to 7, "options" to mapOf("weights" to listOf(7, null))), number.arguments.fieldValues)
        val items = assertIs<List<*>>(result.outputValue("items"))
        val nested = assertIs<List<*>>(items.single())
        assertEquals(mapOf("seed" to 3), assertIs<RootFieldReferenceData>(nested.first()).arguments.fieldValues)
        assertNull(nested.last())
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ result(seed: 7) { number items { value } } }"), dispatcher)
            val container = assertIs<ObjectEngineResult>(root.getCell(root.keys.single { it.field == field }).value.get())
            assertEquals(7, container.getCell(container.keys.single { it.field.name == "number" }).value.get())
        }
    }

    @Test
    fun `scripted Node lookups preserve the first identity through reference tails`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              viewer(id: ID!): Node @resolver(script: "ref('node', {'id': id})")
              redirect: User @resolver(isSelective: true, script: "ref('node', {'id': 'canonical'})")
            }
            type User implements Node @nodeResolver(ids: ["alias", "canonical"], isSelective: true, script: "id == 'alias' ? ref('redirect') : {'score': 7}") {
              id: ID!
              score: Int
            }
            """.trimIndent()
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ viewer(id: \"alias\") { id ... on User { score } } }"), dispatcher)
            val user = assertIs<ObjectEngineResult>(root.getCell(root.keys.single { it.field.name == "viewer" }).value.get())
            assertEquals(viaduct.engine.runtime2.model.IDEngineResult.of("alias"), user.getCell(user.keys.single { it.field.name == "id" }).value.get())
            assertEquals(7, user.getCell(user.keys.single { it.field.name == "score" }).value.get())
        }
    }

    @Test
    fun `unregistered Node IDs resolve to null alongside registered Nodes`() {
        for (isSelective in listOf(false, true)) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  viewer(id: ID!): User @resolver(script: "ref('node', {'id': id})")
                }
                type User implements Node @nodeResolver(ids: ["known"], isSelective: $isSelective, script: "{'score': 7}") {
                  id: ID!
                  score: Int
                }
                """.trimIndent()
            )
            val field = world.schema.requireObjectField("Query", "viewer")
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val root = SharedOperationContext.create(world.assumptions).resolve(
                    world.schemas.operationSelectionsFrom("{ known: viewer(id: \"known\") { id score } missing: viewer(id: \"missing\") { id score } }"),
                    dispatcher,
                )
                val users = root.keys.filter { it.field == field }.associate { key ->
                    assertIs<Arguments.Resolved>(key.arguments).fieldValues.getValue("id") to root.getCell(key).value.get()
                }
                assertNull(users.getValue("missing"))
                val known = assertIs<ObjectEngineResult>(users.getValue("known"))
                assertEquals(viaduct.engine.runtime2.model.IDEngineResult.of("known"), known.getCell(known.keys.single { it.field.name == "id" }).value.get())
                assertEquals(7, known.getCell(known.keys.single { it.field.name == "score" }).value.get())
            }
        }
    }

    @Test
    fun `reference scripts reject invalid paths arguments and error inputs`() {
        for (script in listOf("ref('')", "ref('missing')", "ref('items')", "ref('target', {'value': error})", "ref('target', {'unknown': 1})")) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int @resolver(script: "$script")
                  target(value: Int): Int @resolver(script: "value")
                  items: [Int] @resolver(script: "[]")
                }
                """.trimIndent()
            )
            assertThrows<JexlException>(script) { world.apply(world.schema.requireObjectField("Query", "result")) }
        }
    }

    @Test
    fun `reference argument errors retain the target and script location`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result: Int @resolver(script: ""${'"'}
                var supplied = 'wrong';
                return ref('namespace.target', {'value': supplied});
              ""${'"'})
              namespace: Namespace @resolver(script: "error")
            }
            type Namespace { target(value: Int): Int @resolver(script: "value") }
            """.trimIndent()
        )
        val failure = assertThrows<JexlException> { world.apply(world.schema.requireObjectField("Query", "result")) }
        assertEquals("Query/result", failure.info.name)
        assertEquals(2, failure.info.line)
        val arguments = assertIs<IllegalArgumentException>(failure.cause)
        assertEquals("Invalid reference arguments for namespace.target", arguments.message)
        assertIs<ClassCastException>(arguments.cause)
    }

    @Test
    fun `scripted providers bind both fragments alongside path variables`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result(seed: Int!): Int! @resolver(
                of: "source local: echo(value: ${'$'}provided)"
                qf: "remote: echo(value: ${'$'}provided) fromPath: echo(value: ${'$'}fromSource)"
                pathVars: [{name: "fromSource", path: ["source"]}]
                vars: {names: ["provided"], script: "{'provided': seed * 2}"}
                script: "objectValue.local + queryValue.remote + queryValue.fromPath"
              )
              source: Int! @resolver(script: "7")
              echo(value: Int!): Int! @resolver(script: "value")
            }
            """.trimIndent()
        )
        val field = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(field)
        assertIs<VariableDefinition.FromProvider>(resolver.declaredDependencies.variables.getValue(Arguments.Variable.of(field, "provided")))
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ result(seed: 5) }"), dispatcher)
            assertEquals(27, root.getCell(root.keys.single { it.field == field }).value.get())
        }
    }

    @Test
    fun `scripted provider errors fail all bindings without admitting error input values`() {
        for (script in listOf("error", "{'first': 1, 'second': error}", "{'first': 1, 'second': [error]}")) {
            val world = TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int @resolver(
                    of: "a: echo(value: ${'$'}first) b: echo(value: ${'$'}second)"
                    vars: {names: ["first", "second"], script: "$script"}
                    script: "1"
                  )
                  echo(value: Int): Int @resolver(script: "value")
                  healthy: Int @resolver(script: "9")
                }
                """.trimIndent()
            )
            val field = world.schema.requireObjectField("Query", "result")
            val operation = SharedOperationContext.create(world.assumptions)
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
                val root = operation.resolve(world.schemas.operationSelectionsFrom("{ result healthy }"), dispatcher)
                val key = root.keys.single { it.field == field }
                assertIs<ErrorEngineResult>(root.getCell(key).value.get())
                assertEquals(9, root.getCell(root.keys.single { it.field.name == "healthy" }).value.get())
                val definitions = world.resolverRegistry.resolver(field).declaredDependencies.instantiatedVariableDefinitions(ResolverOccurrenceId.at(root, listOf(key)))
                assertEquals(setOf("first", "second"), definitions.mapTo(linkedSetOf()) { it.variable.variableName })
                definitions.forEach { definition ->
                    assertEquals(VariableBinding.Error, operation.variableBindings.getBinding(checkNotNull(definition.variable.instanceId)))
                }
            }
        }
    }

    @Test
    fun `evaluates object Query and argument inputs in one result`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item! @resolver(script: "{'local': 2}")
              values: [Int]
            }
            type Item {
              local: Int!
              total(extra: Int!): Int!
                @resolver(of: "local", qf: "values", script: ${"\"\"\""}
                  var total = objectValue.local + extra;
                  for (var value : queryValue.values) {
                    if (value != null) total += value;
                  }
                  return total;
                ${"\"\"\""})
            }
            """.trimIndent(),
        )
        val (_, demand) = world.selectionsFrom("fragment Demand on Item { local }")
        val input = assertIs<EngineObjectData.Sync>(world.apply(world.schema.requireObjectField("Query", "item"), selections = demand))
        val query = world.schema.objectOf("Query") { "values" setTo listOf(3, null, 5) }
        val total = world.schema.requireObjectField("Item", "total")

        assertEquals(14, world.apply(total, input, mapOf("extra" to 4), queryValue = query))
    }

    @Test
    fun `Query scripts preserve null and error inputs`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              source: Int
              result: Int @resolver(qf: "source", script: "queryValue.source")
            }
            """.trimIndent(),
        )
        val result = world.schema.requireObjectField("Query", "result")
        val nullQuery = world.schema.objectOf("Query") { "source" setTo null }
        val failure = EngineErrorData.of()
        val errorQuery = world.schema.objectOf("Query") { "source" setTo failure }

        assertNull(world.apply(result, queryValue = nullQuery))
        assertEquals(failure, world.apply(result, queryValue = errorQuery))
    }

    @Test
    fun `scripts bind null for whitespace-only object and Query fragments`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result: Int @resolver(of: " ", qf: " ", script: "objectValue == null && queryValue == null ? 7 : 0")
            }
            """.trimIndent(),
        )

        assertEquals(7, world.apply(world.schema.requireObjectField("Query", "result")))
    }

    @Test
    fun `scripts read selections bound by path and provider variables`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              source: Int @resolver(script: "7")
              echo(value: Int): Int @resolver(script: "value")
              result(seed: Int): Int
                @resolver(
                  of: "source local: echo(value: ${'$'}seed)"
                  qf: "fromPath: echo(value: ${'$'}fromSource) fromProvider: echo(value: ${'$'}provided)"
                  pathVars: [{name: "fromSource", path: ["source"]}]
                  vars: {names: ["provided"], script: "{'provided': seed + seed}"}
                  script: "objectValue.local + queryValue.fromPath + queryValue.fromProvider"
                )
            }
            """.trimIndent(),
        )

        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ first: result(seed: 5) second: result(seed: 9) }"), dispatcher)
            val outputs = root.keys.filter { it.field.name == "result" }.associate { key ->
                assertIs<Int>(assertIs<Arguments.Resolved>(key.arguments).fieldValues.getValue("seed")) to assertIs<Int>(root.getCell(key).value.get())
            }
            assertEquals(mapOf(5 to 22, 9 to 34), outputs)
        }
    }

    @Test
    fun `compiles variables used across both fragments and only in the Query fragment`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              source: Int! @resolver(script: "7")
              echo(value: Int!): Int! @resolver(script: "value")
              result(seed: Int!): Int!
                @resolver(
                  of: "source echo(value: ${'$'}seed)"
                  qf: "byArgument: echo(value: ${'$'}seed) byPath: echo(value: ${'$'}fromSource) byProvider: echo(value: ${'$'}provided)"
                  pathVars: [{name: "fromSource", path: ["source"]}]
                  vars: {names: ["provided"], script: "{'provided': 3}"}
                  script: "(objectValue.echo == null ? 0 : objectValue.echo) + (queryValue.byArgument == null ? 0 : queryValue.byArgument) + (queryValue.byPath == null ? 0 : queryValue.byPath) + (queryValue.byProvider == null ? 0 : queryValue.byProvider)"
                )
            }
            """.trimIndent(),
        )
        val field = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(field)
        val seed = Arguments.Variable.of(field, "seed")
        val fromSource = Arguments.Variable.of(field, "fromSource")
        val provided = Arguments.Variable.of(field, "provided")

        assertEquals(setOf(seed, fromSource, provided), resolver.declaredDependencies.variables.keys)
        assertIs<VariableDefinition.FromArgument>(resolver.declaredDependencies.variables.getValue(seed))
        assertEquals(ProviderFragment.OBJECT, assertIs<VariableDefinition.FromField>(resolver.declaredDependencies.variables.getValue(fromSource)).providerFragment)
        assertIs<VariableDefinition.FromProvider>(resolver.declaredDependencies.variables.getValue(provided))
        assertEquals("Query", resolver.declaredDependencies.queryFragment.type.name)
    }

    @Test
    fun `rejects malformed Query fragment declarations`() {
        for (declaration in listOf("qf: 7", "qf: \"missing\"", "qf: \"echo(value: ${'$'}undefined)\"")) {
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver($declaration, script: "1")
                      echo(value: Int!): Int! @resolver(script: "value")
                    }
                    """.trimIndent(),
                )
            }
        }
    }

    @Test
    fun `evaluates scripted arguments and list inputs`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container!
                    @resolver(script: "{'values': [{'value': 2}, null, {'value': 3}]}")
                }

                type Container {
                  values: [Item]!
                  total(extra: Int!): Int!
                    @resolver(
                      of: "values { value }"
                      script: ${"\"\"\""}
                        var total = 1 + extra;
                        for (var item : objectValue.values) {
                          if (item != null) total += item.value;
                        }
                        return total;
                      ${"\"\"\""}
                    )
                }

                type Item {
                  value: Int!
                }
                """.trimIndent(),
            )
        val schema = world.schemas
        val containerField = schema.loweredSchema.requireObjectField("Query", "container")
        val (_, demand) = world.selectionsFrom("fragment Demand on Container { values { value } }")
        val container =
            assertIs<EngineObjectData.Sync>(
                world.apply(containerField, selections = demand),
            )
        val total = schema.loweredSchema.requireObjectField("Container", "total")

        val result = world.apply(total, container, mapOf("extra" to 4))

        assertEquals(10, result)
    }

    @Test
    fun `scalar object-field provider can supply a nested input-list location`() {
        TestWorld.fromDSL(
            schemaSDL =
                """
                extend type Query {
                  result: Int!
                    @resolver(
                      of: "source consume(value: ${'$'}value)"
                      pathVars: [{name: "value", path: ["source"]}]
                      script: "(objectValue.consume == null ? 0 : objectValue.consume)"
                    )
                  source: Int! @resolver(script: "7")
                  consume(value: [[Int!]!]!): Int!
                    @resolver(script: "14")
                }
                """.trimIndent(),
        )
    }

    @Test
    fun `infers argument variables and compiles explicit path variables`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "source byArg(value: ${'$'}seed) byPath(value: ${'$'}fromSource)"
                      pathVars: [{name: "fromSource", path: ["source"]}]
                      script: "(objectValue.byArg == null ? 0 : objectValue.byArg) + (objectValue.byPath == null ? 0 : objectValue.byPath)"
                    )
                  source: Int! @resolver(script: "7")
                  byArg(value: Int!): Int! @resolver(script: "value")
                  byPath(value: Int!): Int! @resolver(script: "value")
                }
                """.trimIndent(),
            )
        val result = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(result)
        val seed = Arguments.Variable.of(result, "seed")
        val fromSource = Arguments.Variable.of(result, "fromSource")

        assertIs<VariableDefinition.FromArgument>(resolver.declaredDependencies.variables.getValue(seed))
        assertEquals(
            ProviderFragment.OBJECT,
            assertIs<VariableDefinition.FromField>(resolver.declaredDependencies.variables.getValue(fromSource)).providerFragment,
        )
    }

    @Test
    fun `explicit sources retain argument paths and object Query provenance alongside inference and providers`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result(seed: Int!, input: Input!, fromQuery: Int!): Int! @resolver(
                of: "local: echo(value: ${'$'}seed) a: echo(value: ${'$'}renamed) b: echo(value: ${'$'}nested) c: echo(value: ${'$'}fromQuery)"
                qf: "remote: echo(value: 9) d: echo(value: ${'$'}fromObject) e: echo(value: ${'$'}provided)"
                variables: [
                  {name: "renamed", fromArgument: ["seed"]}
                  {name: "nested", fromArgument: ["input", "value"]}
                  {name: "fromObject", fromObjectField: ["local"]}
                  {name: "fromQuery", fromQueryField: ["remote"]}
                ]
                vars: {names: ["provided"], script: "{'provided': seed * 2}"}
                script: "objectValue.a + objectValue.b + objectValue.c + queryValue.d + queryValue.e"
              )
              echo(value: Int!): Int! @resolver(script: "value")
            }
            input Input { value: Int! }
            """.trimIndent(),
        )
        val field = world.schema.requireObjectField("Query", "result")
        val declarations = world.resolverRegistry.resolver(field).declaredDependencies.variables

        fun definition(name: String) = declarations.getValue(Arguments.Variable.of(field, name))
        assertEquals(setOf("seed", "renamed", "nested", "fromObject", "fromQuery", "provided"), declarations.keys.mapTo(linkedSetOf()) { it.variableName })
        assertEquals("seed", assertIs<VariableDefinition.FromArgument>(definition("seed")).argument.name)
        val renamed = assertIs<VariableDefinition.FromArgument>(definition("renamed"))
        assertEquals("seed", renamed.argument.name)
        assertEquals(emptyList(), renamed.inputPath)
        val nested = assertIs<VariableDefinition.FromArgument>(definition("nested"))
        assertEquals("input", nested.argument.name)
        assertEquals(listOf("value"), nested.inputPath.map { it.name })
        for ((name, fragment, responseKey) in listOf(
            Triple("fromObject", ProviderFragment.OBJECT, "local"),
            Triple("fromQuery", ProviderFragment.QUERY, "remote"),
        )) {
            val source = assertIs<VariableDefinition.FromField>(definition(name))
            assertEquals(fragment, source.providerFragment)
            assertEquals(listOf(responseKey), source.responsePath)
            assertEquals(listOf("echo"), source.path.map { it.field.name })
        }
        assertIs<VariableDefinition.FromProvider>(definition("provided"))
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val operation = SharedOperationContext.create(world.assumptions)
            val root = operation.resolve(world.schemas.operationSelectionsFrom("{ result(seed: 2, input: {value: 3}, fromQuery: 100) }"), dispatcher)
            val key = root.keys.single { it.field == field }
            assertEquals(20, root.getCell(key).value.get())
            val bindings = world.resolverRegistry.resolver(field).declaredDependencies.instantiatedVariableDefinitions(ResolverOccurrenceId.at(root, listOf(key)))
            val expected = mapOf("seed" to 2, "renamed" to 2, "nested" to 3, "fromObject" to 2, "fromQuery" to 9, "provided" to 4)
            bindings.forEach { binding ->
                assertEquals(VariableBinding.of(expected.getValue(binding.variable.variableName)), operation.variableBindings.getBinding(checkNotNull(binding.variable.instanceId)))
            }
        }
    }

    @Test
    fun `Query path sources preserve nested aliases and inclusion guards on a non Query parent`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item! @resolver(script: "{:}")
              source: Source! @resolver(script: "{'value': 7}")
            }
            type Item {
              result(show: Boolean!): Int! @resolver(
                of: "consume(value: ${'$'}provided) @include(if: ${'$'}show)"
                qf: "box: source @include(if: ${'$'}show) { selected: value }"
                variables: {name: "provided", fromQueryField: ["box", "selected"]}
                script: "show ? objectValue.consume : 0"
              )
              consume(value: Int!): Int! @resolver(script: "value")
            }
            type Source { value: Int! }
            """.trimIndent(),
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ item { yes: result(show: true) no: result(show: false) } }"), dispatcher)
            val item = assertIs<ObjectEngineResult>(root.getCell(root.keys.single { it.field.name == "item" }).value.get())
            val results = item.keys.filter { it.field.name == "result" }.associate { key ->
                assertIs<Boolean>(assertIs<Arguments.Resolved>(key.arguments).fieldValues.getValue("show")) to assertIs<Int>(item.getCell(key).value.get())
            }
            assertEquals(mapOf(true to 7, false to 0), results)
        }
    }

    @Test
    fun `compiles provider variables from resolver test DSL`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(seed: Int!): Int!
                    @resolver(
                      of: "consume(value: ${'$'}provided) consumeOther(value: ${'$'}constant)"
                      vars: {names: ["provided", "constant"], script: "{'provided': seed + seed, 'constant': 3}"}
                      script: "(objectValue.consume == null ? 0 : objectValue.consume) + (objectValue.consumeOther == null ? 0 : objectValue.consumeOther)"
                    )
                  consume(value: Int!): Int! @resolver(script: "value")
                  consumeOther(value: Int!): Int! @resolver(script: "value")
                }
                """.trimIndent(),
            )
        val result = world.schema.requireObjectField("Query", "result")
        val resolver = world.resolverRegistry.resolver(result)

        assertIs<VariableDefinition.FromProvider>(
            resolver.declaredDependencies.variables.getValue(Arguments.Variable.of(result, "provided")),
        )
        assertIs<VariableDefinition.FromProvider>(
            resolver.declaredDependencies.variables.getValue(Arguments.Variable.of(result, "constant")),
        )
        assertNotNull(resolver.declaredDependencies.variablesProvider)
    }

    @Test
    fun `rejects provider variables that redefine another source`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result(value: Int!): Int!
                        @resolver(
                          of: "consume(value: ${'$'}value)"
                          vars: {names: ["value"], script: "{'value': 3}"}
                          script: "(objectValue.consume == null ? 0 : objectValue.consume)"
                        )
                      consume(value: Int!): Int! @resolver(script: "value")
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(true, exception.message.orEmpty().contains("may not redefine"))
    }

    @Test
    fun `rejects unused provider variables`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result: Int! @resolver(vars: {names: ["unused"], script: "{'unused': 3}"}, script: "1")
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(true, exception.message.orEmpty().contains("Unused vars variables"))
    }

    @Test
    fun `supports explicit null and error results`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  nullable: Int @resolver(script: "null")
                  failed: Int! @resolver(script: "error")
                }
                """.trimIndent(),
            )
        val root = world.resolverRegistry.createRootQueryInput()
        val nullable = world.schema.requireObjectField("Query", "nullable")
        val failed = world.schema.requireObjectField("Query", "failed")

        assertNull(world.apply(nullable, root))
        assertIs<EngineErrorData>(world.apply(failed, root))
    }

    @Test
    fun `rejects invalid object-fragment arguments`() {
        assertThrows<IllegalArgumentException> {
            TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int!
                    @resolver(
                      of: "dependency(arg: null)"
                      script: "1"
                    )
                  dependency(arg: Int!): Int! @resolver(script: "2")
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `former sentinel strings are ordinary GraphQL and provider inputs`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              result: Int @resolver(
                of: "literal: echo(value: \"ERROR\") provided: echo(value: ${'$'}text)"
                vars: {names: ["text"], script: "{'text': 'ERROR'}"}
                script: "objectValue.literal + objectValue.provided"
              )
              echo(value: String!): Int @resolver(script: "value == 'ERROR' ? 7 : 0")
            }
            """.trimIndent(),
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ result }"), dispatcher)
            assertEquals(14, root.getCell(root.keys.single { it.field.name == "result" }).value.get())
        }
    }

    @Test
    fun `scripts preserve integer null and error values`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  echo(input: Int): Int
                    @resolver(script: "input")
                  echoPath: Int
                    @resolver(of: "source", script: "objectValue.source")
                  source: Int @resolver(script: "null")
                }
                """.trimIndent(),
            )
        val root = world.resolverRegistry.createRootQueryInput()
        val echo = world.schema.requireObjectField("Query", "echo")

        assertEquals(4, world.apply(echo, root, mapOf("input" to 4)))
        assertNull(world.apply(echo, root, mapOf("input" to null)))
        assertIs<EngineErrorData>(
            world.apply(echo, root, mapOf("input" to ArgumentResolutionError)),
        )

        val echoPath = world.schema.requireObjectField("Query", "echoPath")
        assertNull(
            world.apply(
                echoPath,
                world.schema.objectOf("Query") {
                    "source" setTo null
                },
            ),
        )
    }

    @Test
    fun `uses typename to select a scripted abstract result`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  subject: Subject!
                    @resolver(script: "{'__typename': 'Person', 'value': 9}")
                }

                interface Subject {
                  value: Int!
                }

                type Person implements Subject {
                  value: Int!
                }
                """.trimIndent(),
            )
        val subject = world.schema.requireObjectField("Query", "subject")
        val (_, demand) = world.selectionsFrom("fragment Demand on Subject { value }")

        val result =
            assertIs<EngineObjectData.Sync>(
                world.apply(subject, selections = demand),
            )

        assertEquals("Person", result.type.name)
        assertEquals(
            9,
            result.get("value"),
        )
    }

    @Test
    fun `resolves Node references using an ID argument`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  viewer(id: ID!): User!
                    @resolver(script: "ref('node', {'id': id})")
                }

                type User implements Node
                  @nodeResolver(ids: ["user-1", "user-2"], script: "id == 'user-1' ? {'score': 7} : {'score': 8}") {
                  id: ID!
                  score: Int!
                }
                """.trimIndent(),
            )
        val schema = world.schemas
        val viewer = schema.loweredSchema.requireObjectField("Query", "viewer")
        val reference =
            assertIs<RootFieldReferenceData>(
                world.apply(viewer, arguments = mapOf("id" to "user-2")),
            )
        val node = schema.loweredSchema.requireObjectField("Query", "node")
        val typedReference = assertIs<RootFieldReferenceData>(world.apply(node, arguments = reference.arguments.fieldValues))
        val user =
            assertIs<EngineObjectData.Sync>(
                world.apply(
                    node,
                    arguments = typedReference.arguments.fieldValues,
                    selections = schema.fragmentFrom("fragment UserResult on User { id score }").subselections,
                ),
            )

        assertEquals(
            "user-2",
            user.get("id"),
        )
        assertEquals(
            8,
            user.get("score"),
        )
        val sourceSchema = SourceSchemaAdapter(schema.loweredSchema)
        assertEquals(viewer, sourceSchema.field("Query", "viewer"))
    }

    @Test
    fun `ID results accept string arguments`() {
        val world = TestWorld.fromDSL("extend type Query { value(text: String!): ID! @resolver(script: \"text\") }")
        assertEquals("same", world.apply(world.schema.requireObjectField("Query", "value"), arguments = mapOf("text" to "same")))
    }

    @Test
    fun `requires a script even for nullable fields`() {
        val exception =
            assertThrows<IllegalArgumentException> {
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      value: Int @resolver
                    }
                    """.trimIndent(),
                )
            }

        assertEquals(true, exception.message.orEmpty().contains("requires script"))
    }

    @Test
    fun `preserves resolver-owned fields in object results`() {
        val world =
            TestWorld.fromDSL(
                """
                extend type Query {
                  item: Item! @resolver(script: "{'computed': 2}")
                }

                type Item {
                  computed: Int! @resolver(script: "1")
                }
                """.trimIndent(),
            )
        val item = world.schema.requireObjectField("Query", "item")
        val (_, demand) = world.selectionsFrom("fragment Demand on Item { computed }")

        val result = assertIs<EngineObjectData.Sync>(world.apply(item, selections = demand))

        assertEquals(2, result.outputValue("computed"))
    }

    @Test
    fun `scripts execute subqueries with variables and response aliases`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              outer(seed: Int!): Int! @resolver(script: ${"\"\"\""}
                if (objectValue != null || queryValue != null) return 0;
                return query('{ renamed: inner(value: ${'$'}value) }', {'value': seed + 0H}).renamed + 1;
              ${"\"\"\""})
              inner(value: Int!): Int! @resolver(qf: "base", script: "queryValue.base + value")
              base: Int! @resolver(script: "7")
            }
            """.trimIndent(),
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ outer(seed: 6) }"), dispatcher)
            assertEquals(14, root.getCell(root.keys.single()).value.get())
        }
    }

    @Test
    fun `requires explicit output projection for aliased subquery objects`() {
        val world = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item @resolver(script: "{'n': 7}")
              copy: Item @resolver(script: "query('{ item { absent: n } }').item")
              projected: Item @resolver(script: "{'n': query('{ item { absent: n } }').item.absent}")
              unaliased: Item @resolver(script: "query('{ item { __typename n } }').item")
            }
            type Item { n: Int absent: Int }
            """.trimIndent(),
        )
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val root = SharedOperationContext.create(world.assumptions).resolve(world.schemas.operationSelectionsFrom("{ copy { n } projected { n } unaliased { n } }"), dispatcher)
            val failure = assertIs<ErrorEngineResult>(root.getCell(root.keys.single { it.field.name == "copy" }).value.get())
            val cause = assertIs<IllegalArgumentException>(failure.errorData.cause)
            assertEquals(true, cause.message.orEmpty().contains("aliased selection 'absent' for field 'n'"))
            for (name in listOf("projected", "unaliased")) {
                val item = assertIs<ObjectEngineResult>(root.getCell(root.keys.single { it.field.name == name }).value.get())
                assertEquals(7, item.getCell(item.keys.single()).value.get())
            }
        }
    }
}

private fun TestWorld.resolveQuery(
    query: String,
    observer: ResolutionObserver = object : ResolutionObserver {},
): ObjectEngineResult =
    Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
        SharedOperationContext.create(assumptions, resolutionObserver = observer).resolve(schemas.operationSelectionsFrom(query), dispatcher)
    }

private fun TestWorld.apply(
    field: ViaductSchema.ObjectField,
    input: EngineObjectData.Sync = resolverRegistry.createRootQueryInput(),
    arguments: Map<String, Any?> = emptyMap(),
    selections: SelectionForest = selectionForestOf(),
    queryValue: EngineObjectData.Sync = resolverRegistry.createRootQueryInput(),
): EngineOutputData? =
    when (val grounded = Arguments.of(field, arguments)) {
        Arguments.Error -> EngineErrorData.of()
        is Arguments.Resolved ->
            runBlocking {
                resolverRegistry.resolver(field)(
                    input = input,
                    queryValue = queryValue,
                    arguments = grounded,
                    selections = selections,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            }
        else -> error("Direct resolver application requires ground arguments")
    }
