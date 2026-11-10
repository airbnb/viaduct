package viaduct.gradle

import graphql.GraphqlErrorBuilder
import graphql.schema.validation.InvalidSchemaException
import graphql.schema.validation.SchemaValidationError
import graphql.schema.validation.SchemaValidationErrorType
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import java.util.concurrent.CancellationException
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import viaduct.graphql.schema.validation.ValidationErrorCodes
import viaduct.graphql.utils.DefaultSchemaFactory

class ViaductSchemaValidatorTest {
    private val logger = LoggerFactory.getLogger(ViaductSchemaValidatorTest::class.java)
    private val validator = ViaductSchemaValidator(logger)

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `failed diagnostic attribution retains the original schema error`() {
        val original = InvalidSchemaException(listOf(SchemaValidationError(SchemaValidationErrorType.ImplementingTypeLackOfFieldError, "Original schema failure")))

        val errors = validator.recoverSchemaErrors(original) { throw ClassCastException("Diagnostic failure") }

        errors shouldHaveSize 1
        errors.single().message shouldContain "Original schema failure"
        errors.single().message shouldNotContain "Diagnostic failure"
    }

    @Test
    fun `dependency linkage failures retain the original schema error`() {
        val original = InvalidSchemaException(listOf(SchemaValidationError(SchemaValidationErrorType.ImplementingTypeLackOfFieldError, "Original schema failure")))
        listOf(NoSuchMethodError("Changed method"), NoClassDefFoundError("Missing class")).forEach { failure ->
            val errors = validator.recoverSchemaErrors(original) { throw failure }
            errors shouldHaveSize 1
            errors.single().message shouldContain "Original schema failure"
        }
    }

    @Test
    fun `empty diagnostic attribution retains the original schema error`() {
        val original = InvalidSchemaException(listOf(SchemaValidationError(SchemaValidationErrorType.ImplementingTypeLackOfFieldError, "Original schema failure")))

        val errors = validator.recoverSchemaErrors(original) { emptyList() }

        errors shouldHaveSize 1
        errors.single().message shouldContain "Original schema failure"
    }

    @Test
    fun `partial attribution retains every original error even when counts match`() {
        val originalErrors = listOf(
            SchemaValidationError(SchemaValidationErrorType.ImplementingTypeLackOfFieldError, "First failure"),
            SchemaValidationError(SchemaValidationErrorType.InputObjectTypeLackOfFieldError, "Second failure"),
        )
        val original = InvalidSchemaException(originalErrors)
        val builder: GraphqlErrorBuilder<*> = GraphqlErrorBuilder.newError()
        val located = builder.message("Located: First failure").build()

        val errors = validator.recoverSchemaErrors(original) { listOf(located, located) }

        errors shouldHaveSize 3
        assertSame(located, errors.first())
        originalErrors.forEach { originalError -> errors shouldExist { it.message.contains(originalError.description) } }
    }

    @Test
    fun `diagnostic fallback propagates cancellation interruption and fatal errors`() {
        val original = InvalidSchemaException(emptyList())
        listOf(CancellationException("Cancelled"), InterruptedException("Interrupted"), OutOfMemoryError("Fatal")).forEach { failure ->
            val thrown = assertThrows<Throwable> { validator.recoverSchemaErrors(original) { throw failure } }
            assertSame(failure, thrown)
        }
    }

    @Test
    fun `framework exclusions match lexical aliases with and without an artifact root`() {
        val frameworkFile = tempDir.resolve("framework.graphqls").toFile().apply {
            writeText("scalar UnknownFrameworkScalar\ntype Query { data: String }")
        }
        val tenantFile = tempDir.resolve("tenant.graphqls").toFile().apply { writeText("scalar TenantScalar") }
        val alias = tempDir.resolve("unused/.././framework.graphqls").toFile()

        listOf(null, tempDir.toFile()).forEach { root ->
            val errors = ViaductSchemaValidator(logger, sourceRoot = root).validateSchema(listOf(frameworkFile, tenantFile), listOf(alias))

            errors shouldHaveSize 1
            errors.single().message shouldContain "Internal framework error"
            errors.single().message shouldContain "UnknownFrameworkScalar"
            errors.single().message shouldNotContain "TenantScalar"
        }
    }

    @Test
    fun `final schema rejects plugin scalar return types with contributor attribution`() {
        val central = tempDir.resolve("schema/application/source/schema.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { value: String }")
        }
        val plugin = tempDir.resolve("schema/plugin/dev.final/generated/dev.generator/final.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("scalar PluginScalar\nextend type Query { forbidden: PluginScalar }")
        }
        val finalValidator = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile(), finalContributors = listOf("dev.generator"))

        val errors = finalValidator.validateSchema(listOf(central, plugin))

        errors shouldHaveSize 1
        errors.single().message shouldContain "PluginScalar"
        errors.single().message shouldContain "Final-schema contribution error from 'dev.generator'"
        errors.single().message shouldContain "schema/plugin/dev.final/generated/dev.generator/final.graphqls"
    }

    @Test
    fun `valid schema passes both syntax and viaduct validation`() {
        val schemaFile = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText(
                """
                type Query {
                    hello: String
                    count: Int
                }
                """.trimIndent()
            )
        }

        val errors = validator.validateSchema(listOf(schemaFile))

        errors.shouldBeEmpty()
    }

    @Test
    fun `syntax error produces parse failure message`() {
        val schemaFile = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText(
                """
                type Query {
                    hello String
                }
                """.trimIndent()
            )
        }

        val errors = validator.validateSchema(listOf(schemaFile))

        errors shouldHaveSize 1
        errors[0].message shouldContain "non schema definition language"
    }

    @Test
    fun `viaduct errors are collected and converted to GraphQLError format`() {
        val schemaFile = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText(
                """
                scalar DateTime
                scalar URL
                type Query {
                    data: String
                }
                type Subscription {
                    onEvent: String
                }
                schema {
                    query: Query
                    subscription: Subscription
                }
                """.trimIndent()
            )
        }

        val errors = validator.validateSchema(listOf(schemaFile))

        errors shouldHaveSize 2 // 1 custom scalar (URL; DateTime is a Viaduct standard scalar) + 1 subscription
        val messages = errors.map { it.message }
        messages.shouldExist { it.contains("[${ValidationErrorCodes.CUSTOM_SCALAR_NOT_ALLOWED}]") }
        messages.shouldExist { it.contains("[${ValidationErrorCodes.SUBSCRIPTION_NOT_ALLOWED}]") }
    }

    @Test
    fun `empty file list fails with descriptive error`() {
        val errors = validator.validateSchema(emptyList())

        errors shouldHaveSize 1
        errors[0].message shouldContain "empty or blank"
    }

    @Test
    fun `empty schema content fails with descriptive error`() {
        val schemaFile = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText("")
        }

        val errors = validator.validateSchema(listOf(schemaFile))

        errors shouldHaveSize 1
        errors[0].message shouldContain "empty or blank"
    }

    @Test
    fun `multiple schema files are merged and validated together`() {
        val queryFile = tempDir.resolve("query.graphqls").toFile().apply {
            writeText("type Query { hello: String }")
        }
        val typesFile = tempDir.resolve("types.graphqls").toFile().apply {
            writeText("type User { name: String }")
        }

        val errors = validator.validateSchema(listOf(queryFile, typesFile))

        errors.shouldBeEmpty()
    }

    @Test
    fun `errors from framework files are reported as internal framework errors`() {
        val builtinFile = tempDir.resolve("BUILTIN_SCHEMA.graphqls").toFile().apply {
            writeText(
                """
                scalar UnknownFrameworkScalar
                type Query { data: String }
                """.trimIndent()
            )
        }
        val userFile = tempDir.resolve("user.graphqls").toFile().apply {
            writeText("type User { name: String }")
        }

        val errors = validator.validateSchema(
            schemaFiles = listOf(builtinFile, userFile),
            excludeFromViaductValidation = listOf(builtinFile)
        )

        errors shouldHaveSize 1
        errors[0].message shouldContain "Internal framework error"
        errors[0].message shouldContain "UnknownFrameworkScalar"
    }

    @Test
    fun `framework errors halt processing and tenant errors are not reported`() {
        val builtinFile = tempDir.resolve("BUILTIN_SCHEMA.graphqls").toFile().apply {
            writeText(
                """
                scalar UnknownFrameworkScalar
                type Query { data: String }
                """.trimIndent()
            )
        }
        val userFile = tempDir.resolve("user.graphqls").toFile().apply {
            writeText("scalar UserCustomScalar\n")
        }

        val errors = validator.validateSchema(
            schemaFiles = listOf(builtinFile, userFile),
            excludeFromViaductValidation = listOf(builtinFile)
        )

        errors shouldHaveSize 1
        errors[0].message shouldContain "Internal framework error"
        errors[0].message shouldContain "UnknownFrameworkScalar"
        errors[0].message shouldNotContain "UserCustomScalar"
    }

    @Test
    fun `empty output type produces user-friendly error with placeholder suggestion`() {
        val schemaFile = tempDir.resolve("schema.graphqls").toFile().apply {
            writeText(
                """
                type Query { data: String }
                type Empty {}
                """.trimIndent()
            )
        }

        val errors = validator.validateSchema(listOf(schemaFile))

        errors shouldHaveSize 1
        errors[0].message shouldContain "must define one or more fields"
        errors[0].message shouldContain "_placeholder"
    }

    @Test
    fun `empty bundled and generated plugin types identify their supplier and original location`() {
        val paths = listOf(
            "schema/plugin/dev.example.bundled/source/nested/types.graphqls" to "dev.example.bundled",
            "schema/module/example/generated/dev.example.generator/nested/types.graphqls" to "dev.example.generator",
        )
        paths.forEach { (path, plugin) ->
            val schemaFile = tempDir.resolve(path).toFile().apply {
                parentFile.mkdirs()
                writeText("type Query { data: String }\n\n  type Empty {}\n")
            }

            val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(schemaFile))

            errors shouldHaveSize 1
            errors[0].message shouldContain "Internal framework/plugin error from '$plugin'"
            errors[0].message shouldContain "must define one or more fields"
            errors[0].message shouldContain "$path:3:3"
            errors[0].message shouldNotContain "_placeholder"
        }
    }

    @Test
    fun `empty types retain individual responsibility when developer and plugin sources are combined`() {
        val pluginFile = tempDir.resolve("schema/plugin/dev.example.plugin/source/types.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { data: String }\ntype PluginEmpty {}\n")
        }
        val developerFile = tempDir.resolve("schema/module/example/source/types.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("type DeveloperEmpty {}\n")
        }

        val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(pluginFile, developerFile))

        errors shouldHaveSize 2
        val pluginError = errors.single { it.message.contains("PluginEmpty") }.message
        pluginError shouldContain "Internal framework/plugin error from 'dev.example.plugin'"
        pluginError shouldContain "schema/plugin/dev.example.plugin/source/types.graphqls:2:1"
        pluginError shouldNotContain "_placeholder"
        val developerError = errors.single { it.message.contains("DeveloperEmpty") }.message
        developerError shouldContain "schema/module/example/source/types.graphqls:1:1"
        developerError shouldContain "_placeholder"
        developerError shouldNotContain "Internal framework"
    }

    @Test
    fun `empty type validation includes members supplied by extensions`() {
        val schemaFile = tempDir.resolve("types.graphqls").toFile().apply {
            writeText(
                """
                type Query {}
                extend type Query { data: String }
                interface Shared {}
                extend interface Shared { data: String }
                input Options {}
                extend input Options { data: String }
                enum Choice
                extend enum Choice { FIRST }
                """.trimIndent()
            )
        }

        validator.validateSchema(listOf(schemaFile)).shouldBeEmpty()
    }

    @Test
    fun `empty plugin interfaces inputs enums and unions retain source attribution`() {
        val path = "schema/plugin/dev.example.plugin/source/types.graphqls"
        listOf("interface Empty {}", "input Empty {}", "enum Empty", "union Empty").forEach { definition ->
            val schemaFile = tempDir.resolve(path).toFile().apply {
                parentFile.mkdirs()
                writeText("type Query { data: String }\n$definition\n")
            }

            val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(schemaFile))

            errors shouldHaveSize 1
            errors[0].message shouldContain "Internal framework/plugin error from 'dev.example.plugin'"
            errors[0].message shouldContain "$path:2:1"
            errors[0].message shouldNotContain "_placeholder"
        }
    }

    @Test
    fun `final schema validation attributes invalid extension fields to their own supplier`() {
        val basePath = "schema/module/example/source/types.graphqls"
        val extensionPath = "schema/plugin/dev.example.plugin/source/extension.graphqls"
        val baseFile = tempDir.resolve(basePath).toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { data: String }\n")
        }
        val extensionFile = tempDir.resolve(extensionPath).toFile().apply {
            parentFile.mkdirs()
            writeText("extend type Query { __invalid: String }\n")
        }

        val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(baseFile, extensionFile))

        errors shouldHaveSize 1
        errors[0].message shouldContain "Internal framework/plugin error from 'dev.example.plugin'"
        errors[0].message shouldContain "$extensionPath:1:"
        errors[0].message shouldContain "__invalid"
        errors[0].message shouldNotContain "_placeholder"
    }

    @Test
    fun `invalid developer extension is not attributed to the plugin defining its base type`() {
        val pluginFile = tempDir.resolve("schema/plugin/dev.example.plugin/source/types.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { data: String }\n")
        }
        val path = "schema/module/example/source/extension.graphqls"
        val developerFile = tempDir.resolve(path).toFile().apply {
            parentFile.mkdirs()
            writeText("extend type Query { __invalid: String }\n")
        }

        val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(pluginFile, developerFile))

        errors shouldHaveSize 1
        errors[0].message shouldContain "$path:1:"
        errors[0].message shouldContain "__invalid"
        errors[0].message shouldNotContain "Internal framework"
    }

    @Test
    fun `final schema validation retains source locations for other GraphQL Java rules`() {
        val path = "schema/plugin/dev.example.plugin/generated/dev.example.generator/types.graphqls"
        val schemas = listOf(
            "type Query { data(arg: Int! @deprecated): String }",
            "type Query { data: String }\ninput Options { value: Int! @deprecated }",
            "type Query { data(arg: Options): String }\ninput Options { next: Options! }",
        )
        schemas.forEach { sdl ->
            val schemaFile = tempDir.resolve(path).toFile().apply {
                parentFile.mkdirs()
                writeText(sdl)
            }

            val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(schemaFile))

            errors shouldHaveSize 1
            errors[0].message shouldContain "Internal framework/plugin error from 'dev.example.generator'"
            errors[0].message shouldContain "$path:"
            errors[0].message shouldNotContain "_placeholder"
        }
    }

    @Test
    fun `invalid input extensions retain the supplier of the failing field`() {
        val baseFile = tempDir.resolve("schema/module/example/source/types.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { data(arg: Options): String }\ninput Options { value: String }\n")
        }
        val path = "schema/plugin/dev.example.plugin/source/extension.graphqls"
        val extensionFile = tempDir.resolve(path).toFile().apply {
            parentFile.mkdirs()
            writeText("extend input Options { next: Options! }\n")
        }

        val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(baseFile, extensionFile))

        errors shouldHaveSize 1
        errors[0].message shouldContain "Internal framework/plugin error from 'dev.example.plugin'"
        errors[0].message shouldContain "$path:1:"
    }

    @Test
    fun `cycle diagnostics retain plugin sources for required input wrappers`() {
        val developerFile = tempDir.resolve("schema/module/example/source/types.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { data(arg: Options): String }\ninput Options { value: String }\ntype Empty\n")
        }
        val path = "schema/plugin/dev.example.plugin/source/extension.graphqls"
        val extensionFile = tempDir.resolve(path).toFile().apply { parentFile.mkdirs() }
        listOf("Options!", "[Options!]!").forEach { type ->
            extensionFile.writeText("extend input Options { next: $type }\n")

            val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(developerFile, extensionFile))

            errors.shouldExist { it.message.contains("\"Empty\" must define") && !it.message.contains("Internal framework") }
            errors.filter { it.message.contains("unsatisfiable cycle") }.forEach { error ->
                error.message shouldContain "Internal framework/plugin error from 'dev.example.plugin'"
                error.message shouldContain "$path:1:"
            }
        }
    }

    @Test
    fun `valid plugin input fields are not blamed for an unrelated developer input cycle`() {
        val path = "schema/module/example/source/types.graphqls"
        val developerFile = tempDir.resolve(path).toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { data(arg: Options): String }\ninput Options { next: Options! }\ninput Leaf { value: String }\n")
        }
        val pluginFile = tempDir.resolve("schema/plugin/dev.example.plugin/source/extension.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("extend input Options { leaf: Leaf! }\n")
        }

        val errors = ViaductSchemaValidator(logger, sourceRoot = tempDir.toFile()).validateSchema(listOf(developerFile, pluginFile))

        errors shouldHaveSize 1
        errors[0].message shouldContain "$path:2:"
        errors[0].message shouldNotContain "Internal framework"
    }

    @Test
    fun `tenant errors are reported normally when no framework errors exist`() {
        val builtinFile = tempDir.resolve("BUILTIN_SCHEMA.graphqls").toFile().apply {
            writeText("type Query { data: String }\n")
        }
        val userFile = tempDir.resolve("user.graphqls").toFile().apply {
            writeText("scalar UserCustomScalar\n")
        }

        val errors = validator.validateSchema(
            schemaFiles = listOf(builtinFile, userFile),
            excludeFromViaductValidation = listOf(builtinFile)
        )

        errors shouldHaveSize 1
        errors[0].message shouldContain "UserCustomScalar"
        errors[0].message shouldNotContain "Internal framework error"
    }

    @Test
    fun `framework files are matched by full path`() {
        val dirA = tempDir.resolve("a").toFile().apply { mkdirs() }
        val dirB = tempDir.resolve("b").toFile().apply { mkdirs() }
        val fileA = dirA.resolve("schema.graphqls").apply {
            writeText("scalar CustomA\n")
        }
        val fileB = dirB.resolve("schema.graphqls").apply {
            writeText(
                """
                scalar CustomB
                type Query { hello: String }
                """.trimIndent()
            )
        }

        val errors = validator.validateSchema(
            schemaFiles = listOf(fileA, fileB),
            excludeFromViaductValidation = listOf(fileA)
        )

        // fileA is a framework file — its error is reported as internal and halts processing
        errors shouldHaveSize 1
        errors[0].message shouldContain "Internal framework error"
        errors[0].message shouldContain "CustomA"
        errors[0].message shouldNotContain "CustomB"
    }

    @Test
    fun `generated roots allow unscoped extensions when scoping is disabled`() {
        val userFile = tempDir.resolve("user.graphqls").toFile().apply {
            writeText("extend type Query { greeting: String }\n")
        }
        val builtinFile = tempDir.resolve("BUILTIN_SCHEMA.graphqls").toFile().apply {
            writeText(DefaultSchemaFactory.getDefaultSDL(existingSDLFiles = listOf(userFile)))
        }

        val errors = ViaductSchemaValidator(
            logger,
            validateScopeConsistency = false,
        ).validateSchema(
            schemaFiles = listOf(userFile, builtinFile),
            excludeFromViaductValidation = listOf(builtinFile),
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `generated roots require scoped extensions when scoping is enabled`() {
        val userFile = tempDir.resolve("user.graphqls").toFile().apply {
            writeText("extend type Query { greeting: String }\n")
        }
        val builtinFile = tempDir.resolve("BUILTIN_SCHEMA.graphqls").toFile().apply {
            writeText(DefaultSchemaFactory.getDefaultSDL(existingSDLFiles = listOf(userFile)))
        }

        val errors = ViaductSchemaValidator(
            logger,
            validateScopeConsistency = true,
        ).validateSchema(
            schemaFiles = listOf(userFile, builtinFile),
            excludeFromViaductValidation = listOf(builtinFile),
        )

        errors shouldHaveSize 1
        errors[0].message shouldContain
            "[${ValidationErrorCodes.OBJECT_OR_INTERFACE_EXTENSION_SCOPE_DIRECTIVE_MISSING}]"
        errors[0].message shouldNotContain "Internal framework error"
    }
}
