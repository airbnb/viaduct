package viaduct.tenant.codegen.bytecode.testschema

import graphql.schema.idl.UnExecutableSchemaGenerator
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import viaduct.codegen.utils.JavaName
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.schema.graphqljava.GJSchemaRaw
import viaduct.graphql.schema.graphqljava.readTypes
import viaduct.graphql.utils.DefaultSchemaProvider
import viaduct.invariants.InvariantChecker
import viaduct.tenant.codegen.bytecode.CodeGenArgs
import viaduct.tenant.codegen.bytecode.GRTClassFilesBuilder
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.exercise.ClassResolver
import viaduct.tenant.codegen.bytecode.exercise.Exerciser
import viaduct.utils.timer.Timer

/**
 * Parameterized tests for schema-driven bytecode generation.
 *
 * Each test runs the full exerciser on a different schema configuration,
 * allowing easy addition of new test schemas by adding to the prefixes list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParameterizedSchemaDrivenTests {
    companion object {
        private val SUCCESS_SCHEMA_CONFIGS = listOf(
            "default" to listOf("graphql/bytecode_test_schema.graphqls", "graphql/classic.graphqls"),
            "simple" to listOf("graphql/edge-cases/simple_test_schema.graphqls"),
            "kotlin-reserved-names" to listOf("graphql/edge-cases/kotlin_reserved_names_schema.graphqls"),
            "recursion" to listOf("graphql/edge-cases/recursion_schema.graphqls"),
            "edge-cases" to listOf("graphql/edge-cases/edge_cases_schema.graphqls"),
            "custom-schema" to listOf("graphql/custom_schema.graphqls"),
            "custom-root-types" to listOf("graphql/custom_root_types_schema.graphqls"),
        )
        private val INVALID_SCHEMA_CONFIGS: List<Pair<String, List<String>>> = listOf(
            "invalid-undefined-type" to listOf("graphql/edge-cases/invalid_undefined_type_schema.graphqls"),
        )

        @JvmStatic
        fun successfulSchemaTestCases(): List<Arguments> =
            SUCCESS_SCHEMA_CONFIGS.map { (name, resourcePaths) ->
                Arguments.of(name, prepareSchemaFiles(resourcePaths))
            }

        @JvmStatic
        fun invalidSchemaTestCases(): List<Arguments> =
            INVALID_SCHEMA_CONFIGS.map { (name, resourcePaths) ->
                Arguments.of(name, prepareSchemaFiles(resourcePaths))
            }

        private fun prepareSchemaFiles(resourcePaths: List<String>): List<File> {
            return resourcePaths.map { resourcePath ->
                val resourceStream = ParameterizedSchemaDrivenTests::class.java.classLoader.getResourceAsStream(resourcePath)
                    ?: error("Resource not found: $resourcePath")
                val tempFile = File.createTempFile("schema-", ".graphqls")
                tempFile.deleteOnExit()
                tempFile.outputStream().use { output ->
                    resourceStream.use { input ->
                        input.copyTo(output)
                    }
                }
                tempFile
            }
        }
    }

    @ParameterizedTest(name = "Schema: {0}")
    @MethodSource("successfulSchemaTestCases")
    fun successfulReflectionDrivenExercisesV2(
        schemaName: String,
        schemaFiles: List<File>
    ) = reflectionDrivenExercisesV2(schemaName, schemaFiles) { failures ->
        if (!failures.isEmpty) {
            println("Test failures for schema '$schemaName':")
            failures.toListOfErrors().forEach { println(it) }
        }
        assertTrue(failures.isEmpty, "Expected no failures for schema '$schemaName', but found:\n${failures.joinToString("\n")}")
    }

    @ParameterizedTest(name = "Schema (expected failures): {0}")
    @MethodSource("invalidSchemaTestCases")
    fun invalidReflectionDrivenExercisesV2(
        schemaName: String,
        schemaFiles: List<File>
    ) {
        reflectionDrivenExercisesV2(schemaName, schemaFiles) { failures ->
            assertFalse(failures.isEmpty, "Expected failures for invalid schema '$schemaName', but found none.")
            assertTrue(
                failures.toList().map { f -> f.details }
                    .any { it?.contains("SchemaProblem{errors=[The field type 'NonExistentProfileType' is not present when resolving type 'UserWithInvalidField'") == true }
            )
        }
    }

    private fun reflectionDrivenExercisesV2(
        schemaName: String,
        schemaFiles: List<File>,
        assert: (failures: InvariantChecker) -> Unit
    ) = runBlockingTest {
        val failures = InvariantChecker()

        try {
            // Get the default SDL (directives, scalars, Node interface, root types)
            val defaultSdl = DefaultSchemaProvider.getDefaultSDL(
                existingSDLFiles = schemaFiles,
                includeNodeDefinition = DefaultSchemaProvider.IncludeNodeSchema.Always,
                includeNodeQueries = DefaultSchemaProvider.IncludeNodeSchema.Never
            )

            // Combine user schema files with the defaults
            val userSdl = schemaFiles.joinToString("\n") { it.readText() }
            val sdl = userSdl + "\n" + defaultSdl

            val schema = GJSchemaRaw.fromSDL(sdl)
            val graphqlSchema = ViaductSchema(UnExecutableSchemaGenerator.makeUnExecutableSchema(readTypes(sdl)))

            val args = CodeGenArgs(
                moduleName = null,
                pkgForGeneratedClasses = "viaduct.api.grts",
                includeIneligibleTypesForTestingOnly = false,
                excludeCrossModuleFields = false,
                javaTargetVersion = null,
                workerNumber = 0,
                workerCount = 1,
                timer = Timer(),
                baseTypeMapper = ViaductBaseTypeMapper(schema),
            )

            val builder = GRTClassFilesBuilder(args)
                .addAll(schema)

            val classLoader = builder.buildClassLoader()

            val exerciser = Exerciser(
                check = failures,
                classResolver = ClassResolver.fromClassLoader(JavaName("viaduct.api.grts"), classLoader),
                schema = schema,
                graphqlSchema = graphqlSchema,
                classLoader = classLoader,
            )

            exerciser.exerciseGeneratedCodeV2()
        } catch (ex: Exception) {
            failures.addExceptionFailure(ex, "Exception during exerciser run for schema '$schemaName'")
        }

        assert(failures)
    }
}
