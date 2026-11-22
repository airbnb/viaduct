package viaduct.engine.runtime2.resolvers.resolver21

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.schema.operationSelectionsFrom

/** Coverage retained for the T1 cross-slot requirement's independently rooted reference case. */
class RootReferenceTypeCheckerTest {
    @Test
    fun `root-field-reference result gets one concrete occurrence type check`() {
        val invocations = AtomicInteger()
        val worldFixture =
            TestWorld.fromDSL(
                """
                extend type Query { viewer: User! @resolver(script: "ref('node', {'id': 'user-1'})") }
                type User implements Node
                  @nodeResolver(ids: ["user-1"], script: "{'score': 7}")
                  @checker(script: "invocations.incrementAndGet(); allow") {
                  id: ID!
                  score: Int!
                }
                """.trimIndent(),
                scriptBindings = mapOf("invocations" to invocations),
            )
        val world = worldFixture.assumptions

        val result =
            SharedOperationContext.create(world).resolve(
                worldFixture.schemas.operationSelectionsFrom("{ viewer { score } }"),
            )
        val viewerField = result.type.fields.single { it.name == "viewer" }
        val viewer =
            result
                .getCell(ObjectEngineResult.GroundKey.of(viewerField, emptyMap()))
                .value
                .get() as ObjectEngineResult

        assertEquals(1, invocations.get())
        assertSame(CheckerResult.Success, viewer.typeCheckerResult.get())
    }
}
