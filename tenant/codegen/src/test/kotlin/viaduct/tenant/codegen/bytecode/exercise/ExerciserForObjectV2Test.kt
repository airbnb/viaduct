package viaduct.tenant.codegen.bytecode.exercise

import kotlin.reflect.KClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import viaduct.api.grts.MissingBuilderObjectV2
import viaduct.api.grts.MissingDefaultGetterObjectV2
import viaduct.api.grts.MissingGetterObjectV2
import viaduct.api.grts.MissingNonDefaultGetterObjectV2
import viaduct.api.grts.MissingSetterObjectV2
import viaduct.api.grts.ObjectV2
import viaduct.codegen.utils.JavaName
import viaduct.engine.api.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.createGraphQLSchema
import viaduct.graphql.schema.test.createSchema
import viaduct.invariants.FailureCollector

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciserForObjectV2Test {
    private class Fixture(
        sdl: String = "",
        val dataClass: KClass<*>,
    ) {
        val schema = createSchema(sdl)
        val graphqlSchema = ViaductGraphQLSchema(createGraphQLSchema(sdl))

        suspend fun exerciseV2(check: FailureCollector = FailureCollector()): FailureCollector =
            check.also {
                val dataName = dataClass.simpleName!!
                val type = schema.types[dataName]!! as ViaductSchema.Object

                val exerciser = Exerciser(
                    check,
                    ClassResolver.fromSystemClassLoader(
                        JavaName("viaduct.api.grts")
                    ),
                    schema,
                    graphqlSchema
                )
                exerciser.exerciseObjectV2(type, graphqlSchema)
            }
    }

    @Test
    fun `ObjectV2 has no failures`() =
        runTest {
            Fixture(
                """
            type ObjectV2 {
                stringField: String!,
                intField: Int,
                listField: [String],
                nestedListField: [[String]]
            }
                """.trimIndent(),
                ObjectV2::class
            ).exerciseV2().assertEmpty("\n")
        }

    @Test
    fun `missing builder`() =
        runTest {
            Fixture(
                """
            type MissingBuilderObjectV2 {
                stringField: String!
            }
                """.trimIndent(),
                MissingBuilderObjectV2::class
            ).exerciseV2().assertContainsLabels("OBJECT_BUILDER_CLASS_EXISTS")
        }

    @Test
    fun `missing getter`() =
        runTest {
            Fixture(
                """
            type MissingGetterObjectV2 {
                stringField: String!
            }
                """.trimIndent(),
                MissingGetterObjectV2::class
            ).exerciseV2().assertContainsLabels("OBJECT_GETTER")
        }

    @Test
    fun `missing default getter`() =
        runTest {
            Fixture(
                """
            type MissingDefaultGetterObjectV2 {
                stringField: String!
            }
                """.trimIndent(),
                MissingDefaultGetterObjectV2::class
            ).exerciseV2().assertContainsLabels("OBJECT_DEFAULT_GETTER")
        }

    @Test
    fun `missing non default getter`() =
        runTest {
            Fixture(
                """
            type MissingNonDefaultGetterObjectV2 {
                stringField: String!
            }
                """.trimIndent(),
                MissingNonDefaultGetterObjectV2::class
            ).exerciseV2().assertContainsLabels("OBJECT_GETTER")
        }

    @Test
    fun `missing setter`() =
        runTest {
            Fixture(
                """
            type MissingSetterObjectV2 {
                stringField: String!
            }
                """.trimIndent(),
                MissingSetterObjectV2::class
            ).exerciseV2().assertContainsLabels("OBJECT_SETTER", "OBJECT_SETTER_MISSING")
        }
}
