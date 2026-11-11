package viaduct.gradle.federation

import java.io.File
import java.net.JarURLConnection
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import viaduct.apiannotations.InternalApi
import viaduct.gradle.ViaductSchemaValidator
import viaduct.graphql.schema.validation.SchemaValidationError
import viaduct.x.federation.validation.FoundationFieldSelectionRule

@OptIn(InternalApi::class)
class FoundationValidationIntegrationTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `SPI discovers real federation provider and accepts valid central and final schemas`() {
        val files =
            schemaFiles("type Product @key(fields: \"kind: __typename productId: id\") { id: ID! name: String @external } type Query { product: Product @provides(fields: \"productName: name\") }")
        assertEquals(emptyList<Any>(), validator().validateSchema(files))
        assertEquals(emptyList<Any>(), validator(final = true).validateSchema(files))
    }

    @Test
    fun `SPI diagnostics retain provider rule source and extension coordinates`() {
        val files = schemaFiles("type Product { id: ID! } type Query { product: Product }")
        val extension = source("schema/module/example/source/keys.graphqls", "extend type Product @key(fields: 123)")
        val diagnostics = mutableListOf<Triple<String, String?, SchemaValidationError>>()
        val errors = validator(diagnostics = diagnostics).validateSchema(files + extension)

        assertEquals(1, errors.size)
        val (provider, rule, error) = diagnostics.single()
        assertEquals(FoundationValidationRuleProvider::class.java.name, provider)
        assertEquals("viaduct.foundation.field-selections", rule)
        assertEquals("KEY_INVALID_FIELDS_TYPE", error.code)
        assertEquals(listOf("Product"), error.location.path)
        assertEquals("schema/module/example/source/keys.graphqls", error.location.sourceLocation?.sourceName)
        assertTrue(errors.single().message.contains(provider))
    }

    @Test
    fun `final schema revalidation rejects generated federation declarations`() {
        val files = schemaFiles("type Product { id: ID! } type Query { product: Product }")
        val final = source("schema/plugin/dev.final/generated/dev.generator/final.graphqls", "extend type Product @key(fields: \"missing\")")
        val errors = validator(final = true).validateSchema(files + final)

        assertEquals(1, errors.size)
        assertTrue(errors.single().message.contains("KEY_INVALID_FIELDS"))
        assertTrue(errors.single().message.contains("Final-schema contribution error from 'dev.generator'"))
        assertTrue(errors.single().message.contains("Product"))
    }

    @Test
    fun `structural failures run before federation rules and Apollo key arguments are rejected`() {
        val files = schemaFiles("type Product @key(fields: \"id\", resolvable: false) { id: ID! } type Query { product: Product }")
        val diagnostics = mutableListOf<Triple<String, String?, SchemaValidationError>>()
        val errors = validator(diagnostics = diagnostics).validateSchema(files)

        assertTrue(errors.isNotEmpty())
        assertTrue(errors.any { "resolvable" in it.message })
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `provides semantic failure is reported through the integrated host`() {
        val files = schemaFiles("type Product { name: String } type Query { product: Product @provides(fields: \"name\") }")
        val errors = validator().validateSchema(files)
        assertEquals(1, errors.size)
        assertTrue(errors.single().message.contains("PROVIDES_FIELDS_MISSING_EXTERNAL"))
        assertTrue(errors.single().message.contains("Product.name"))
    }

    private fun validator(
        final: Boolean = false,
        diagnostics: MutableList<Triple<String, String?, SchemaValidationError>> = mutableListOf(),
    ): ViaductSchemaValidator =
        ViaductSchemaValidator(
            LoggerFactory.getLogger(javaClass),
            sourceRoot = root.toFile(),
            validationClasspath = providerClasspath(),
            finalContributors = if (final) listOf("dev.generator") else emptyList(),
            reportDiagnostic = { provider, rule, error -> diagnostics.add(Triple(provider, rule, error)) },
        )

    private fun schemaFiles(body: String): List<File> {
        val sdl = requireNotNull(javaClass.getResourceAsStream("/viaduct/federation/foundation.graphqls")).use { it.reader().readText() }
        return listOf(
            source("schema/plugin/com.airbnb.viaduct.foundation-federation/source/viaduct/federation/foundation.graphqls", sdl),
            source("schema/module/example/source/schema.graphqls", body),
        )
    }

    private fun source(
        path: String,
        content: String
    ): File =
        root.resolve(path).toFile().apply {
            parentFile.mkdirs()
            writeText(content)
        }

    private fun providerClasspath(): List<File> {
        val service = "META-INF/services/viaduct.gradle.spi.ValidationRuleProvider"
        val resource = requireNotNull(javaClass.classLoader.getResource(service))
        val descriptorRoot = if (resource.protocol == "jar") {
            File((resource.openConnection() as JarURLConnection).jarFileURL.toURI())
        } else {
            File(resource.toURI()).toPath().let { path -> (1..service.split('/').size).fold(path) { parent, _ -> parent.parent }.toFile() }
        }
        return listOf(
            descriptorRoot,
            File(FoundationValidationRuleProvider::class.java.protectionDomain.codeSource.location.toURI()),
            File(FoundationFieldSelectionRule::class.java.protectionDomain.codeSource.location.toURI()),
        ).distinct()
    }
}
