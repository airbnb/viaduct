@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.contract

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/** Fragment-free field- and type-checker capability boundary. */
interface FragmentFreeCheckerProfileContract {
    val coroutineResolverSubject: CoroutineResolverTestSubject

    @Test
    fun `type checker runs once for every demanded object occurrence`() {
        val invocations = AtomicInteger()
        val recorder = CheckerApplicationRecorder()
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      singular: Item! @resolver(result: {id: 1})
                      repeated: [Item!]! @resolver(result: [{id: 2}, {id: 2}])
                      nested: [[Item!]!]! @resolver(result: [[{id: 3}], [{id: 4}]])
                      missing: Item @resolver(result: null)
                      failed: Item @resolver(result: "ERROR")
                      scalar: Int! @resolver(result: 5)
                    }

                    type Item {
                      id: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = coroutineResolverSubject.selectiveResolvers,
                typeCheckers = { schema ->
                    val item = schema.loweredSchema.requireType("Item") as ViaductSchema.Object
                    mapOf(
                        item to
                            TypeCheckerResolver.of(item, schema.loweredSchema.requireQueryTypeDef()) { inputs, _ ->
                                assertTrue(inputs.isEmpty())
                                invocations.incrementAndGet()
                                CheckerResult.Success
                            },
                    )
                },
            )
        val world = worldFixture.assumptions

        val result =
            coroutineResolverSubject.resolve(
                SharedOperationContext.create(world, checkerObserver = recorder),
                worldFixture.schemas.operationSelectionsFrom(
                    "{ singular { id } repeated { id } nested { id } missing { id } failed { id } scalar }",
                ),
            )
        val singular = result.profileObjectValue("singular")
        val repeated = assertIs<ListEngineResult>(result.profileRawValue("repeated"))
        val repeatedFirst = assertIs<ObjectEngineResult>(repeated[0].value.get())
        val repeatedSecond = assertIs<ObjectEngineResult>(repeated[1].value.get())
        val nested = assertIs<ListEngineResult>(result.profileRawValue("nested"))
        val nestedFirst = assertIs<ListEngineResult>(nested[0].value.get())
        val nestedSecond = assertIs<ListEngineResult>(nested[1].value.get())
        val objects =
            listOf(
                singular,
                repeatedFirst,
                repeatedSecond,
                assertIs<ObjectEngineResult>(nestedFirst[0].value.get()),
                assertIs<ObjectEngineResult>(nestedSecond[0].value.get()),
            )

        assertEquals(5, invocations.get())
        assertTrue(result.typeCheckerResult.isCompleted)
        assertNull(result.typeCheckerResult.get())
        assertNotSame(repeatedFirst, repeatedSecond)
        objects.forEach { occurrence ->
            assertSame(CheckerResult.Success, occurrence.typeCheckerResult.get())
        }
        val observations = recorder.checkerApplications()
        assertEquals(5, observations.size)
        assertTrue(observations.all { it.checkerKind == CheckerKind.TYPE })
        assertTrue(observations.all { it.arguments == null && it.checkedType.name == "Item" })
        assertEquals(5, observations.map { it.occurrencePath }.distinct().size)
    }
}

private fun ObjectEngineResult.profileRawValue(fieldName: String): Any? {
    val field = type.fields.single { it.name == fieldName }
    return getCell(ObjectEngineResult.GroundKey.of(field, emptyMap())).value.get()
}

private fun ObjectEngineResult.profileObjectValue(fieldName: String): ObjectEngineResult = assertIs<ObjectEngineResult>(profileRawValue(fieldName))
