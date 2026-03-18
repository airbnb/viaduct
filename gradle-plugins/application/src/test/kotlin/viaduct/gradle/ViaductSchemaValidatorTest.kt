package viaduct.gradle

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldExist
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import viaduct.graphql.schema.validation.ValidationErrorCodes

class ViaductSchemaValidatorTest {
    private val logger = LoggerFactory.getLogger(ViaductSchemaValidatorTest::class.java)
    private val validator = ViaductSchemaValidator(logger)

    @TempDir
    lateinit var tempDir: Path

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
}
