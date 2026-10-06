package viaduct.engine.runtime2.model.registry

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.graphql.schema.ViaductSchema

class RootFieldReferenceSnipToDemandTest {
    @Test
    fun `projection preserves direct and nested root-field references`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schemas
        val reference =
            RootFieldReferenceData.of(
                path =
                    listOf(
                        schema.loweredSchema.requireObjectField("Query", "factory"),
                        schema.loweredSchema.requireObjectField("ProductFactory", "create"),
                    ),
                arguments =
                    Arguments.Resolved.of(
                        schema.loweredSchema.requireObjectField("ProductFactory", "create"),
                        emptyMap(),
                    ),
            )
        val demand =
            schema.fragmentFrom(
                """
                fragment fields on Wrapper {
                  product { name }
                }
                """.trimIndent(),
            ).subselections

        assertSame(
            reference,
            with(world.assumptions) { reference.snipToDemand(demand) },
        )

        val wrapper =
            engineObjectDataOf(
                schema.loweredSchema.requireType("Wrapper") as ViaductSchema.Object,
                mapOf("product" to reference),
            )
        val projected =
            assertIs<EngineObjectData.Sync>(
                with(world.assumptions) { wrapper.snipToDemand(demand) },
            )
        assertSame(reference, projected.outputValue("product"))
    }

    private companion object {
        val SCHEMA_SDL =
            """
            type Query {
              factory: ProductFactory
              wrapper: Wrapper
            }

            type ProductFactory {
              create: Product
            }

            type Product {
              name: String
            }

            type Wrapper {
              product: Product
            }
            """.trimIndent()
    }
}
