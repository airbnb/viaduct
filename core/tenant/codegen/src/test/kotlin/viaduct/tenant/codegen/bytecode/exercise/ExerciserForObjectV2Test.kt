package viaduct.tenant.codegen.bytecode.exercise

import kotlin.reflect.KClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import viaduct.api.grts.MissingBuilderObjectV2
import viaduct.api.grts.MissingDefaultGetterObjectV2
import viaduct.api.grts.MissingGetterObjectV2
import viaduct.api.grts.MissingNonDefaultGetterObjectV2
import viaduct.api.grts.MissingSetterObjectV2
import viaduct.api.grts.ObjectV2
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.codegen.utils.JavaName
import viaduct.engine.api.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.createGraphQLSchema
import viaduct.graphql.schema.test.createSchema
import viaduct.invariants.FailureCollector
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
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

    // DSL constructor tests

    private val objectV2Sdl = """
        type ObjectV2 {
            stringField: String!
            intField: Int
        }
    """.trimIndent()

    private fun mkObjectV2Context() =
        MockInternalContext(
            ViaductGraphQLSchema(createGraphQLSchema(objectV2Sdl)),
            GlobalIDCodecDefault
        ).executionContext

    @Test
    fun `DSL-style builder produces same result as chained builder`() =
        runTest {
            val ctx = mkObjectV2Context()

            val chained = ObjectV2.Builder(ctx)
                .stringField("hello")
                .intField(42)
                .build()

            val dsl = ObjectV2.of(ctx) {
                stringField("hello")
                intField(42)
            }

            assertEquals(chained.getStringFieldOrThrow(), dsl.getStringFieldOrThrow())
            assertEquals(chained.getIntFieldOrThrow(), dsl.getIntFieldOrThrow())
        }

    @Test
    fun `chained API is unaffected by DSL constructor addition`() =
        runTest {
            val ctx = mkObjectV2Context()

            val obj = ObjectV2.Builder(ctx).stringField("legacy").build()
            assertNotNull(obj)
            assertEquals("legacy", obj.getStringFieldOrThrow())
        }

    @Test
    fun `adding of object does not add constructors to Builder`() {
        assertEquals(1, ObjectV2.Builder::class.constructors.size)
    }

    @Test
    fun `of nested object exists with invoke operator`() {
        val ofClass = ObjectV2::class.nestedClasses.firstOrNull { it.simpleName == "of" }
        assertNotNull(ofClass, "Expected ObjectV2 to have a nested class named 'of'")
        assertNotNull(ofClass!!.objectInstance, "Expected ObjectV2.of to be a Kotlin object (singleton)")
        val invokeMethod = ofClass.java.declaredMethods.filter { it.name == "invoke" }
        assertEquals(1, invokeMethod.size, "Expected exactly one 'invoke' method on ObjectV2.of")
        assertEquals(2, invokeMethod[0].parameterCount, "Expected invoke to take (ExecutionContext, block)")
    }
}
