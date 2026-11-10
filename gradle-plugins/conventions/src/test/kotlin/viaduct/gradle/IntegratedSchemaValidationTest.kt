@file:OptIn(viaduct.apiannotations.ExperimentalApi::class)

package viaduct.gradle

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import viaduct.gradle.spi.ValidationRuleProvider
import viaduct.gradle.spi.ViaductRuleSet
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaLocation
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.graphql.schema.validation.ValidationContext
import viaduct.graphql.schema.validation.ValidationRule

class IntegratedSchemaValidationTest {
    @TempDir
    lateinit var tempDir: Path
    private val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { value: String }")

    @Test
    fun `shared rules join the built in context and retain order at every node`() {
        val events = mutableListOf<String>()
        var shared: ValidationContext? = null
        val builtIn = object : ValidationRule("built-in") {
            override fun visitTypeDef(
                ctx: ValidationContext,
                typeDef: ViaductSchema.TypeDef
            ) {
                shared = ctx
                events.add("built-in:${typeDef.name}")
            }
        }

        fun rule(id: String) =
            object : ValidationRule(id) {
                override fun visitTypeDef(
                    ctx: ValidationContext,
                    typeDef: ViaductSchema.TypeDef
                ) {
                    assertSame(shared, ctx)
                    events.add("$id:${typeDef.name}")
                }
            }

        IntegratedSchemaValidation.validate(schema, listOf(builtIn), listOf(provider(listOf(rule("first"), rule("second")))))

        events.chunked(3).forEach { node ->
            node.map { it.substringBefore(':') } shouldBe listOf("built-in", "first", "second")
        }
        events.count { it == "first:Query" } shouldBe 1
    }

    @Test
    fun `private contexts use the same schema but separate traversals and aggregate diagnostics`() {
        val first = ValidationContext(schema)
        val second = ValidationContext(schema)
        val providers = listOf(provider(listOf(reportingRule("one")), first), provider(listOf(reportingRule("two")), second))

        val diagnostics = IntegratedSchemaValidation.validate(schema, listOf(reportingRule("built-in")), providers)

        diagnostics.map { it.ruleId } shouldBe listOf("built-in", "one", "two")
        diagnostics.map { it.error.code } shouldBe listOf("SAME_CODE", "SAME_CODE", "SAME_CODE")
        first.errors shouldHaveSize 1
        second.errors shouldHaveSize 1
    }

    @Test
    fun `duplicate IDs are rejected before traversal including collisions with built ins`() {
        val duplicate = reportingRule("duplicate")
        val error = assertThrows<IllegalStateException> {
            IntegratedSchemaValidation.validate(schema, listOf(duplicate), listOf(provider(listOf(duplicate))))
        }
        error.message shouldContain "duplicate rule ID 'duplicate'"
        error.message shouldContain "com.airbnb.viaduct"
        val acrossProviders = assertThrows<IllegalStateException> {
            IntegratedSchemaValidation.validate(schema, emptyList(), listOf(provider(listOf(duplicate)), provider(listOf(duplicate))))
        }
        acrossProviders.message shouldContain "duplicate rule ID"
    }

    @Test
    fun `a context for an equivalent but different schema is rejected`() {
        val otherSchema = ViaductSchema.fromTypeDefinitionRegistry("type Query { value: String }")
        val error = assertThrows<IllegalStateException> {
            IntegratedSchemaValidation.validate(schema, emptyList(), listOf(provider(emptyList(), ValidationContext(otherSchema))))
        }
        error.message shouldContain "different schema"
    }

    @Test
    fun `factory and callback failures retain provider and rule identities`() {
        val brokenProvider = object : ValidationRuleProvider {
            override fun createRuleSet(schema: ViaductSchema): ViaductRuleSet = error("factory defect")
        }
        val factory = assertThrows<IllegalStateException> { IntegratedSchemaValidation.validate(schema, emptyList(), listOf(brokenProvider)) }
        factory.message shouldContain brokenProvider.javaClass.name
        factory.cause?.message shouldBe "factory defect"
        val brokenRule = object : ValidationRule("broken-rule") {
            override fun visitSchema(ctx: ValidationContext) = error("callback defect")
        }
        val callback = assertThrows<IllegalStateException> {
            IntegratedSchemaValidation.validate(schema, emptyList(), listOf(provider(listOf(brokenRule))))
        }
        callback.message shouldContain "broken-rule"
        callback.cause?.message shouldBe "callback defect"
    }

    @Test
    fun `cancellation and fatal failures propagate unchanged`() {
        listOf(CancellationException("cancel"), OutOfMemoryError("fatal")).forEach { failure ->
            val rule = object : ValidationRule("failed") {
                override fun visitSchema(ctx: ValidationContext) {
                    throw failure
                }
            }
            val thrown = assertThrows<Throwable> { IntegratedSchemaValidation.validate(schema, listOf(rule), emptyList<ValidationRuleProvider>()) }
            assertSame(failure, thrown)
        }
    }

    @Test
    fun `wrapped interruption restores the flag and propagates the original interruption`() {
        val interruption = InterruptedException("interrupt")
        val rule = object : ValidationRule("interrupted") {
            override fun visitSchema(ctx: ValidationContext) {
                throw IllegalStateException("wrapper", interruption)
            }
        }
        try {
            val thrown = assertThrows<InterruptedException> {
                IntegratedSchemaValidation.validate(schema, listOf(rule), emptyList<ValidationRuleProvider>())
            }
            assertSame(interruption, thrown)
            Thread.currentThread().isInterrupted shouldBe true
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `execution discovers registered SPI descriptors and creates fresh providers rules and contexts`() {
        val classpath = listOf(serviceJar(FreshProvider::class.java.name))
        repeat(2) {
            val diagnostics = IntegratedSchemaValidation.validate(schema, emptyList(), classpath)
            diagnostics.single().ruleId shouldBe "fresh"
            diagnostics.single().provider shouldBe FreshProvider::class.java.name
        }
        IntegratedSchemaValidation.validate(schema, emptyList(), emptyList<File>()).shouldBeEmpty()
    }

    @Test
    fun `warnings are reported without failing schema validation and partial validation ignores the SPI`() {
        val file = tempDir.resolve("schema.graphqls").toFile().apply { writeText("type Query { value: String }") }
        val diagnostics = mutableListOf<SchemaValidationError>()
        val logger = LoggerFactory.getLogger(javaClass)
        val classpath = listOf(serviceJar(FreshProvider::class.java.name))
        val validator = ViaductSchemaValidator(logger, validationClasspath = classpath, reportDiagnostic = { _, _, error -> diagnostics.add(error) })
        validator.validateSchema(listOf(file)).shouldBeEmpty()
        diagnostics.single().severity shouldBe SchemaValidationError.Severity.WARNING
        ViaductSchemaValidator(logger, extensionsOnly = true, validationClasspath = listOf(serviceJar("missing.Provider")))
            .validateSchema(listOf(file)).shouldBeEmpty()
    }

    @Test
    fun `structural validation runs before provider loading`() {
        val file = tempDir.resolve("invalid.graphqls").toFile().apply { writeText("type Query { value: Missing }") }
        val validator = ViaductSchemaValidator(LoggerFactory.getLogger(javaClass), validationClasspath = listOf(serviceJar("missing.Provider")))
        validator.validateSchema(listOf(file)).single().message shouldContain "Missing"
    }

    @Test
    fun `final errors attribute final contributors even at central or absent locations`() {
        val central = tempDir.resolve("schema/module/tenant/source/schema.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("type Query { value: String } interface Added { value: String } type Central implements Added { value: String }")
        }
        val final = tempDir.resolve("schema/plugin/dev.final/generated/dev.generator/final.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("extend interface Added { missing: String }")
        }
        val validator = ViaductSchemaValidator(LoggerFactory.getLogger(javaClass), sourceRoot = tempDir.toFile(), finalContributors = listOf("dev.generator"))
        val errors = validator.validateSchema(listOf(central, final))
        errors.forEach { error ->
            error.message shouldContain "Final-schema contribution error from 'dev.generator'"
            error.message shouldContain "central locations are context"
        }
        errors.isNotEmpty() shouldBe true
    }

    @Test
    fun `final conflicts and locationless rule errors retain final contributor attribution`() {
        val central = tempDir.resolve("central.graphqls").toFile().apply { writeText("type Query { value: String }") }
        val duplicate = tempDir.resolve("final.graphqls").toFile().apply { writeText("type Query { other: String }") }
        val logger = LoggerFactory.getLogger(javaClass)
        val structural = ViaductSchemaValidator(logger, finalContributors = listOf("dev.final"))
            .validateSchema(listOf(central, duplicate)).single()
        structural.message shouldContain "Final-schema contribution error from 'dev.final'"
        val reported = mutableListOf<SchemaValidationError>()
        val ruleError = ViaductSchemaValidator(
            logger,
            validationClasspath = listOf(
                serviceJar(LocationlessErrorProvider::class.java.name)
            ),
            finalContributors = listOf("dev.final"),
            reportDiagnostic = {
                    _,
                    _,
                    error ->
                reported.add(error)
            }
        )
            .validateSchema(listOf(central)).single()
        ruleError.message shouldContain "Final-schema contribution error from 'dev.final'"
        ruleError.message shouldContain "rule=locationless"
        reported.single().message shouldContain "Final-schema contribution error from 'dev.final'"
    }

    @Test
    fun `plugin rule diagnostics survive builtin framework failures`() {
        val file = tempDir.resolve("schema/plugin/dev.central/source/invalid.graphqls").toFile().apply {
            parentFile.mkdirs()
            writeText("scalar InvalidScalar type Query { value: String }")
        }
        val validator = ViaductSchemaValidator(LoggerFactory.getLogger(javaClass), sourceRoot = tempDir.toFile(), validationClasspath = listOf(serviceJar(LocationlessErrorProvider::class.java.name)))
        val errors = validator.validateSchema(listOf(file))
        errors.any { "rule=locationless" in it.message } shouldBe true
    }

    private fun provider(
        rules: List<ValidationRule>,
        context: ValidationContext? = null
    ) = object : ValidationRuleProvider {
        override fun createRuleSet(schema: ViaductSchema) =
            object : ViaductRuleSet {
                override fun createContext() = context

                override fun getRules() = rules
            }
    }

    private fun reportingRule(id: String) =
        object : ValidationRule(id) {
            override fun visitSchema(ctx: ValidationContext) = ctx.reportError("SAME_CODE", id, SchemaLocation.ofType("Query"))
        }

    private fun serviceJar(provider: String): File =
        tempDir.resolve("rules-${tempDir.toFile().listFiles()!!.size}.jar").toFile().apply {
            JarOutputStream(outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("META-INF/services/viaduct.gradle.spi.ValidationRuleProvider"))
                jar.write(provider.toByteArray())
                jar.closeEntry()
            }
        }

    class LocationlessErrorProvider : ValidationRuleProvider {
        override fun createRuleSet(schema: ViaductSchema) =
            object : ViaductRuleSet {
                override fun createContext(): ValidationContext? = null

                override fun getRules() =
                    listOf(object : ValidationRule("locationless") {
                        override fun visitSchema(ctx: ValidationContext) = ctx.reportError("ERROR", "No source location", SchemaLocation(emptyList()))
                    })
            }
    }

    class FreshProvider : ValidationRuleProvider {
        private var created = false

        override fun createRuleSet(schema: ViaductSchema): ViaductRuleSet {
            check(!created)
            created = true
            return object : ViaductRuleSet {
                private var contextCreated = false

                override fun createContext(): ValidationContext {
                    check(!contextCreated)
                    contextCreated = true
                    return ValidationContext(schema)
                }

                override fun getRules() =
                    listOf(object : ValidationRule("fresh") {
                        private var visited = false

                        override fun visitSchema(ctx: ValidationContext) {
                            check(!visited)
                            visited = true
                            ctx.reportError("WARNING", "fresh provider", SchemaLocation(emptyList()), SchemaValidationError.Severity.WARNING)
                        }
                    })
            }
        }
    }
}
