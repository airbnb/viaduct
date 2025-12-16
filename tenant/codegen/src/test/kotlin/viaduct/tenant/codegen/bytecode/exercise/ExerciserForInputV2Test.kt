package viaduct.tenant.codegen.bytecode.exercise

import kotlin.reflect.KClass
import org.junit.jupiter.api.Test
import viaduct.api.grts.InputV2
import viaduct.api.grts.MissingBuilderBuildInputV2
import viaduct.api.grts.MissingBuilderConstructorInputV2
import viaduct.api.grts.MissingBuilderInputV2
import viaduct.api.grts.MissingBuilderSetterInputV2
import viaduct.api.grts.MissingGetterImplInputV2
import viaduct.api.grts.MissingPropertyInputV2
import viaduct.api.grts.MissingToBuilderInputV2
import viaduct.api.grts.TestArgObject
import viaduct.codegen.utils.JavaName
import viaduct.engine.api.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.test.mkGraphQLSchema
import viaduct.graphql.schema.test.mkSchema
import viaduct.invariants.InvariantChecker

class ExerciserForInputV2Test {
    private class Fixture(
        sdl: String = "",
        val dataClass: KClass<*>,
    ) {
        val schema = mkSchema(sdl)
        val graphqlSchema = ViaductGraphQLSchema(mkGraphQLSchema(sdl))

        fun exerciseInputV2(check: InvariantChecker = InvariantChecker()): InvariantChecker =
            check.also {
                val dataName = dataClass.simpleName!!
                val type = schema.types[dataName]!! as ViaductSchema.Input

                val exerciser = Exerciser(
                    check,
                    ClassResolver.fromSystemClassLoader(
                        JavaName("viaduct.api.grts")
                    ),
                    schema,
                    graphqlSchema
                )
                exerciser.exerciseInputV2(type, graphqlSchema)
            }

        fun exerciseArgInputV2(check: InvariantChecker = InvariantChecker()): InvariantChecker =
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

                for (field in type.fields) {
                    if (!field.args.none()) {
                        exerciser.exerciseArgInputV2(field, graphqlSchema)
                    }
                }
            }
    }

    @Test
    fun `InputV2 has no failures`() {
        Fixture(
            """
            input InputV2 {
                stringField: String!
                intField: Int = 1
                listField: [String]
                nestedField: [[String]]
            }
            """.trimIndent(),
            InputV2::class
        ).exerciseInputV2().assertEmpty("\n")
    }

    @Test
    fun `missing builder`() {
        Fixture(
            """
            input MissingBuilderInputV2 {
                stringField: String
            }
            """.trimIndent(),
            MissingBuilderInputV2::class
        ).exerciseInputV2().assertContainsLabels("INPUT_BUILDER_CLASS_EXISTS")
    }

    @Test
    fun `missing builder build`() {
        Fixture(
            """
            input MissingBuilderBuildInputV2 {
                stringField: String
            }
            """.trimIndent(),
            MissingBuilderBuildInputV2::class
        ).exerciseInputV2().assertContainsLabels("INPUT_BUILDER_BUILD_EXISTS")
    }

    @Test
    fun `missing builder setter`() {
        Fixture(
            """
            input MissingBuilderSetterInputV2 {
                stringField: String
            }
            """.trimIndent(),
            MissingBuilderSetterInputV2::class
        ).exerciseInputV2().assertContainsLabels("INPUT_BUILDER_SETTER_EXISTS")
    }

    @Test
    fun `missing property`() {
        Fixture(
            """
            input MissingPropertyInputV2 {
                stringField: String
            }
            """.trimIndent(),
            MissingPropertyInputV2::class
        ).exerciseInputV2().assertContainsLabels("INPUT_PROPERTY_EXISTS")
    }

    @Test
    fun `missing toBuilder`() {
        Fixture(
            """
            input MissingToBuilderInputV2 {
                stringField: String
            }
            """.trimIndent(),
            MissingToBuilderInputV2::class
        ).exerciseInputV2().assertContainsLabels("INPUT_TOBUILDER_EXISTS")
    }

    @Test
    fun `missing getter impl`() {
        Fixture(
            """
            input MissingGetterImplInputV2 {
                enumField: ExerciserTestEnum
            }

            enum ExerciserTestEnum {
                A
                B
                C
            }
            """.trimIndent(),
            MissingGetterImplInputV2::class
        ).exerciseInputV2().assertContainsLabels("OBJECT_GETTER_VALUE")
    }

    @Test
    fun `missing builder constructor`() {
        Fixture(
            """
            input MissingBuilderConstructorInputV2 {
                stringField: String
            }
            """.trimIndent(),
            MissingBuilderConstructorInputV2::class
        ).exerciseInputV2().assertContainsLabels("INPUT_BUILDER_PUBLIC_CONSTRUCTOR_EXISTS")
    }

    @Test
    fun `test arg input`() {
        Fixture(
            """
            input InputV2 {
                stringField: String!
                intField: Int = 1
                listField: [String]
                nestedField: [[String]]
            }

            enum ExerciserTestEnum {
                A
                B
                C
            }

            type TestArgObject {
                test(input: InputV2, enum: ExerciserTestEnum!): String
            }
            """.trimIndent(),
            TestArgObject::class
        ).exerciseArgInputV2().assertEmpty("\n")
    }
}
