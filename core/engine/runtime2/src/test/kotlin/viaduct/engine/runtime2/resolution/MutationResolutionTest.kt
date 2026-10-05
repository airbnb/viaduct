@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.MutationObjectEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolvers.resolver01.resolve as resolve01
import viaduct.engine.runtime2.resolvers.resolver02.resolve as resolve02
import viaduct.engine.runtime2.resolvers.resolver03.resolve as resolve03
import viaduct.engine.runtime2.resolvers.resolver06.resolve as resolve06
import viaduct.engine.runtime2.resolvers.resolver07.resolve as resolve07
import viaduct.engine.runtime2.resolvers.resolver08.resolve as resolve08
import viaduct.engine.runtime2.resolvers.resolver21.resolve as resolve21
import viaduct.engine.runtime2.resolvers.resolver22.resolve as resolve22
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

class MutationResolutionTest : ResolutionDispatcherResource {
    @Test
    fun `inactive payload dependencies do not prevent the next mutation`() {
        val fixture = TestWorld.fromDSL(
            """
            type Mutation { update: Payload @resolver(result: {}) }
            type Payload {
                value: Int @resolver(of: "dependency @include(if: ${'$'}enabled)", providerVars: {enabled: false}, result: 7)
                dependency: Int @resolver(result: 99)
            }
            """.trimIndent(),
        )
        val result = SharedOperationContext.create(fixture.assumptions).resolveWithTestDispatcher(
            fixture.schemas.operationSelectionsFrom("mutation { first: update { value } second: update { value } }"),
        )
        assertEquals(2, result.keys.size)
        result.keys.forEach { key ->
            val payload = assertInstanceOf(ObjectEngineResult::class.java, result.getCell(key).value.get())
            val valueKey = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Payload", "value"), emptyMap())
            assertEquals(7, payload.getCell(valueKey).value.get())
        }
    }

    private data class Family(
        val name: String,
        val selective: Boolean,
        val resolve: (SharedOperationContext<*>, SelectionForest) -> ObjectEngineResult,
    )

    private val families = listOf(
        Family("01", false) { op, selections -> op.resolve01(selections) },
        Family("02", false) { op, selections -> op.resolve02(selections) },
        Family("03", true) { op, selections -> op.resolve03(selections) },
        Family("06", false) { op, selections -> op.resolve06(selections) },
        Family("07", false) { op, selections -> op.resolve07(selections) },
        Family("08", true) { op, selections -> op.resolve08(selections) },
        Family("21", false) { op, selections -> op.resolve21(selections) },
        Family("22", false) { op, selections -> op.resolve22(selections) },
        Family("23", true) { op, selections -> op.resolve23(selections) },
        Family("Resolution", true) { op, selections -> op.resolveWithTestDispatcher(selections) },
    )

    @TestFactory
    fun `mutations traverse namespaces depth first and preserve separate alias results`(): List<DynamicTest> =
        families.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val count = AtomicInteger()
                val events = mutableListOf<String>()
                val fixture = TestWorld.fromSDL(
                    """
                    directive @resolver on FIELD_DEFINITION
                    directive @namespaceType on OBJECT
                    type Query { idle: Int }
                    type Mutation { group: Group, update: Payload @resolver }
                    type Group @namespaceType { nested: Nested, update: Payload @resolver }
                    type Nested @namespaceType { update: Payload @resolver }
                    type Payload { value: Int, observed: Int @resolver }
                    """.trimIndent(),
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        buildMap {
                            put(schema.requireObjectField("Query", "idle"), fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 0 })
                            for (type in listOf("Mutation", "Group", "Nested")) {
                                put(
                                    schema.requireObjectField(type, "update"),
                                    fieldResolverOf(schema.emptyFragmentOf(type), schema.emptyFragmentOf("Query")) { input, queryValue, _ ->
                                        assertEquals(type, input.type.name)
                                        assertTrue(input.getSelections().none())
                                        assertEquals("Query", queryValue.type.name)
                                        assertTrue(queryValue.getSelections().none())
                                        val value = count.incrementAndGet()
                                        events += "start:$value"
                                        yield()
                                        events += "end:$value"
                                        engineObjectDataOf(schema.requireType("Payload") as ViaductSchema.Object, mapOf("value" to value))
                                    }
                                )
                            }
                            put(
                                schema.requireObjectField("Payload", "observed"),
                                fieldResolverOf(schema.emptyFragmentOf("Payload")) { _, _ ->
                                    yield()
                                    count.get()
                                }
                            )
                        }
                    },
                    selectiveResolvers = family.selective,
                )
                val selections = fixture.schemas.operationSelectionsFrom(
                    """
                    mutation {
                        group { a: update { value observed } nested { update { value observed } } b: update { value observed } }
                        first: update { value observed }
                        second: update { value observed }
                        first: update { observed }
                    }
                    """.trimIndent(),
                )
                val result = family.resolve(SharedOperationContext.create(fixture.assumptions), selections)
                assertInstanceOf(MutationObjectEngineResult::class.java, result)
                assertEquals((1..5).flatMap { listOf("start:$it", "end:$it") }, events)
                assertEquals(
                    mapOf("group" to mapOf("a" to payload(1), "nested" to mapOf("update" to payload(2)), "b" to payload(3)), "first" to payload(4), "second" to payload(5)),
                    result.completedData()
                )
            }
        }

    @TestFactory
    fun `mutation payload Query fragments observe each mutation before the next effect`(): List<DynamicTest> =
        families.map { family ->
            DynamicTest.dynamicTest(family.name) {
                val count = AtomicInteger()
                val observed = mutableListOf<Int>()
                val fixture = TestWorld.fromSDL(
                    """
                    type Query { current: Int }
                    type Mutation { update: Payload }
                    type Payload { observed: Int }
                    """.trimIndent(),
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        mapOf(
                            schema.requireObjectField("Query", "current") to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                count.get().also { observed += it }
                            },
                            schema.requireObjectField("Mutation", "update") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _ ->
                                count.incrementAndGet()
                                engineObjectDataOf(schema.requireType("Payload") as ViaductSchema.Object)
                            },
                            schema.requireObjectField("Payload", "observed") to fieldResolverOf(
                                schema.emptyFragmentOf("Payload"),
                                schemas.fragmentFrom("fragment PayloadQuery on Query { current }"),
                            ) { _, queryValue, _ -> queryValue.get("current") },
                        )
                    },
                    selectiveResolvers = family.selective,
                )
                val result = family.resolve(
                    SharedOperationContext.create(fixture.assumptions),
                    fixture.schemas.operationSelectionsFrom("mutation { first: update { observed } second: update { observed } }"),
                )
                assertEquals(listOf(1, 2), observed)
                assertEquals(mapOf("first" to mapOf("observed" to 1), "second" to mapOf("observed" to 2)), result.completedData())
            }
        }

    @TestFactory
    fun `nonnull mutation failures preserve all namespace effects across every family`(): List<DynamicTest> =
        families.flatMap { family ->
            listOf(false, true).map { nonNullNamespace ->
                DynamicTest.dynamicTest("${family.name}: nonnull namespace=$nonNullNamespace") {
                    val calls = AtomicInteger()
                    val namespaceType = if (nonNullNamespace) "Group!" else "Group"
                    val fixture = TestWorld.fromSDL(
                        """
                        directive @namespaceType on OBJECT
                        type Query { idle: Int }
                        type Mutation { group: $namespaceType, update: Int }
                        type Group @namespaceType { update: Int! }
                        """.trimIndent(),
                        fieldResolvers = { schemas ->
                            val schema = schemas.loweredSchema
                            mapOf(
                                schema.requireObjectField("Mutation", "update") to fieldResolverOf(schema.emptyFragmentOf("Mutation")) { _, _ -> calls.incrementAndGet() },
                                schema.requireObjectField("Group", "update") to fieldResolverOf(schema.emptyFragmentOf("Group")) { _, _ ->
                                    if (calls.incrementAndGet() == 1) EngineErrorData.of(IllegalStateException("mutation failed")) else calls.get()
                                },
                            )
                        },
                        selectiveResolvers = family.selective,
                    )
                    val result = family.resolve(
                        SharedOperationContext.create(fixture.assumptions),
                        fixture.schemas.operationSelectionsFrom("mutation { group { first: update second: update } last: update }"),
                    )
                    assertEquals(3, calls.get())
                    val groupKey = result.keys.single { (it as ObjectEngineResult.MutationKey).responseKey == "group" }
                    val group = result.getCell(groupKey).value.get() as ObjectEngineResult
                    val secondKey = group.keys.single { (it as ObjectEngineResult.MutationKey).responseKey == "second" }
                    assertTrue(runBlocking { group.getCell(secondKey).fetchActivated() })
                    assertEquals(2, group.getCell(secondKey).value.get())
                    val lastKey = result.keys.single { (it as ObjectEngineResult.MutationKey).responseKey == "last" }
                    assertTrue(runBlocking { result.getCell(lastKey).fetchActivated() })
                    assertEquals(3, result.getCell(lastKey).value.get())
                }
            }
        }

    private fun payload(value: Int) = mapOf("value" to value, "observed" to value)

    private fun ObjectEngineResult.completedData(): Map<String, Any?> =
        keys.associate { key ->
            assertTrue(isCompleted, "Incomplete mutation or payload object: ${type.name}")
            val name = (key as? ObjectEngineResult.MutationKey)?.responseKey ?: key.field.name
            val value = getCell(key).value.get()
            name to if (value is ObjectEngineResult) value.completedData() else value
        }
}
