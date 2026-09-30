package semantics.resolvers.resolver21

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import model.ObjectEngineResult
import model.operationSelectionsFrom
import model.registry.TypeCheckerResolver
import model.requireQueryTypeDef
import model.requireType
import model.testing.TestWorld
import semantics.shared.SharedOperationContext
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/** Coverage retained for the T1 cross-slot requirement's independently rooted reference case. */
class RootReferenceTypeCheckerTest {
    @Test
    fun `root-field-reference result gets one concrete occurrence type check`() {
        val invocations = AtomicInteger()
        val worldFixture =
            TestWorld.fromDSL(
                schemaSDL =
                    """
                    extend type Query {
                      viewer: User! @resolver(result: {id: "user-1"})
                    }

                    type User implements Node
                      @nodeResolver(result: [{id: "user-1", result: {score: 7}}]) {
                      id: ID!
                      score: Int!
                    }
                    """.trimIndent(),
                selectiveResolvers = false,
                typeCheckers = { schema ->
                    val user = schema.loweredSchema.requireType("User") as ViaductSchema.Object
                    mapOf(
                        user to TypeCheckerResolver.of(user, schema.loweredSchema.requireQueryTypeDef()) { _, _ ->
                            invocations.incrementAndGet()
                            CheckerResult.Success
                        },
                    )
                },
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
