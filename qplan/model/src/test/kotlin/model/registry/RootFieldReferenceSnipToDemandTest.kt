package model.registry

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame
import model.Arguments
import model.RootFieldReferenceData
import model.engineObjectDataOf
import model.fragmentFrom
import model.outputValue
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import viaduct.engine.api.EngineObjectData
import viaduct.graphql.schema.ViaductSchema

class RootFieldReferenceSnipToDemandTest {
    @Test
    fun `projection preserves direct and nested root-field references`() {
        val world = TestWorld.fromSDL(SCHEMA_SDL)
        val schema = world.schema
        val reference =
            RootFieldReferenceData.of(
                path =
                    listOf(
                        schema.requireObjectField("Query", "factory"),
                        schema.requireObjectField("ProductFactory", "create"),
                    ),
                arguments =
                    Arguments.Resolved.of(
                        schema.requireObjectField("ProductFactory", "create"),
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
                schema.requireType("Wrapper") as ViaductSchema.Object,
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
