package viaduct.tenant.codegen.bytecode.testschema

import graphql.schema.idl.SchemaParser
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.tenant.codegen.bytecode.CodeGenArgs
import viaduct.tenant.codegen.bytecode.GRTClassFilesBuilder
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.utils.timer.Timer

/**
 * Regression tests for the accessor-name collision check on the bytecode GRT generator.
 *
 * Undetected, these schemas reach javassist and fail there with a `DuplicateMemberException` naming a
 * method the schema author never wrote. Generation runs through
 * [GRTClassFilesBuilder.buildClassLoader], so schemas expected to generate are checked against
 * javassist as well as against this check.
 */
class AccessorNameCollisionTest {
    private fun generate(sdl: String): ClassLoader {
        val sdlFile = File.createTempFile("collision-", ".graphqls").also {
            it.deleteOnExit()
            it.writeText(sdl)
        }
        val defaultSdl = DefaultSchemaFactory.getDefaultSDL(
            existingSDLFiles = listOf(sdlFile),
            includeNodeDefinition = DefaultSchemaFactory.IncludeNodeSchema.Always,
            includeNodeQueries = DefaultSchemaFactory.IncludeNodeSchema.Never
        )
        val schema = ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(sdl + "\n" + defaultSdl))

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
        return GRTClassFilesBuilder(args).addAll(schema).buildClassLoader()
    }

    private fun assertRejects(
        sdl: String,
        expectedInMessage: String
    ) {
        val error = assertThrows<IllegalArgumentException> { generate(sdl) }
        assertTrue(
            error.message!!.contains(expectedInMessage),
            "Expected the collision to be reported as `$expectedInMessage`, got: ${error.message}"
        )
    }

    @Test
    fun `object field colliding with a strict accessor is rejected`() {
        assertRejects(
            """
            type Query { collision: Collision }
            type Collision { foo: String fooOrThrow: String }
            """.trimIndent(),
            "fields `foo` and `fooOrThrow` both generate `getFooOrThrow`"
        )
    }

    @Test
    fun `interface field colliding with a strict accessor is rejected`() {
        assertRejects(
            """
            type Query { collision: Collision }
            interface Collision { foo: String fooOrThrow: String }
            """.trimIndent(),
            "fields `foo` and `fooOrThrow` both generate `getFooOrThrow`"
        )
    }

    /** `Sub` would declare `getFooOrThrow()` on top of the one it inherits from `Base` for `foo`. */
    @Test
    fun `interface field colliding with an inherited accessor is rejected`() {
        assertRejects(
            """
            type Query { collision: Sub }
            interface Base { foo: String }
            interface Sub implements Base { foo: String fooOrThrow: String }
            """.trimIndent(),
            "fields `foo` and `fooOrThrow` both generate `getFooOrThrow`"
        )
    }

    /** An `is`-prefixed field keeps its name as its accessor, so neither side gets a `get` prefix. */
    @Test
    fun `is-prefixed field colliding with a strict accessor is rejected`() {
        assertRejects(
            """
            type Query { collision: Collision }
            type Collision { isReady: Boolean isReadyOrThrow: Boolean }
            """.trimIndent(),
            "fields `isReady` and `isReadyOrThrow` both generate `isReadyOrThrow`"
        )
    }

    @Test
    fun `object field colliding with a soft accessor is rejected`() {
        assertRejects(
            """
            type Query { collision: Collision }
            type Collision { bar: String barOrNull: String }
            """.trimIndent(),
            "fields `bar` and `barOrNull` both generate `getBarOrNull`"
        )
    }

    @Test
    fun `suffix-named fields without siblings are generated`() {
        assertDoesNotThrow {
            generate(
                """
                type Query { fine: Fine }
                type Fine { fooOrThrow: String barOrNull: String }
                """.trimIndent()
            )
        }
    }

    @Test
    fun `ordinary fields are generated`() {
        assertDoesNotThrow {
            generate(
                """
                type Query { fine: Fine }
                type Fine { foo: String bar: String isReady: Boolean }
                """.trimIndent()
            )
        }
    }

    /** Guards that a new `AccessorForm` cannot reach the emit sites without the check seeing it. */
    @Test
    fun `emitted accessor names match the suffix list the check uses`() {
        val classLoader = generate(
            """
            type Query { fine: Fine }
            type Fine { foo: String }
            """.trimIndent()
        )

        val emitted = classLoader.loadClass("viaduct.api.grts.Fine").declaredMethods
            .map { it.name }
            .filter { it.startsWith("getFoo") }
            .toSet()
        assertEquals(cfg.FIELD_ACCESSOR_SUFFIXES.map { "getFoo$it" }.toSet(), emitted)
    }
}
