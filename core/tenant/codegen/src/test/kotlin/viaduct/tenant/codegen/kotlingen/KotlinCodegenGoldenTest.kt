package viaduct.tenant.codegen.kotlingen

import graphql.schema.idl.SchemaParser
import io.github.classgraph.ClassGraph
import java.io.File
import kotlin.io.path.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.utils.DefaultSchemaFactory
import viaduct.graphql.utils.Predicates
import viaduct.graphql.utils.toSDL
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.kotlingen.bytecode.KotlinCodeGenArgs
import viaduct.tenant.codegen.kotlingen.bytecode.KotlinGRTFilesBuilder
import viaduct.utils.timer.Timer

/**
 * Golden-output characterization test for the Kotlin "kotlingen" GRT and resolver code generators.
 *
 * This test regenerates Kotlin source from `golden/golden_schema.graphqls` and asserts that every
 * generated file is byte-for-byte identical to the checked-in golden snapshot under
 * `src/test/resources/golden/expected/`. It exists to lock in the *current* generator output so
 * that a behavior-preserving refactor (e.g. sharing schema-analysis logic between the Kotlin and
 * Java codegens) can be proven to leave the output unchanged.
 *
 * It drives all three kotlingen entry points:
 *  - [KotlinGRTFilesBuilder] for GRT source (enums, inputs, interfaces, objects, unions, and the
 *    `_Arguments` types of fields that take arguments),
 *  - [ViaductSchema.generateFieldResolvers] for `{Type}Resolvers.kt`, and
 *  - [ViaductSchema.generateNodeResolvers] for `resolverbases/NodeResolvers.kt`.
 *
 * The fixture deliberately exercises every construct the generators branch on: plain objects, Node
 * types and `Node.id`, connections/edges (`@connection`/`@edge`), `@idOf`, `BackingData` fields,
 * unions, interfaces (including an interface that extends `Node`), enums (with and without
 * descriptions), input types, and resolvers in every variant (field, mutation, connection,
 * batching, selective, and node).
 *
 * ## Regenerating the golden files
 *
 * If a deliberate codegen change alters the output, regenerate the golden snapshot by running the
 * test with `-Dviaduct.codegen.golden.regenerate=true`, then review the diff under
 * `src/test/resources/golden/expected/`. An *unexpected* diff is a real signal: the generator
 * changed behavior. From the `oss` directory:
 *
 * ```
 * ./gradlew -p core :tenant:codegen:test \
 *     --tests "viaduct.tenant.codegen.kotlingen.KotlinCodegenGoldenTest" \
 *     -Dviaduct.codegen.golden.regenerate=true
 * ```
 */
class KotlinCodegenGoldenTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `grt and resolver output matches golden snapshot`() {
        val schema = loadSchemaWithDefaults()

        val grtDir = File(tempDir, "grts").apply { mkdirs() }
        val resolverDir = File(tempDir, "resolvers").apply { mkdirs() }

        // GRT source generation.
        KotlinGRTFilesBuilder.builderFrom(
            KotlinCodeGenArgs(
                pkgForGeneratedClasses = GRT_PACKAGE,
                dirForOutput = grtDir,
                timer = Timer(),
                baseTypeMapper = ViaductBaseTypeMapper(schema),
            )
        ).addAll(schema)

        // Resolver source generation. isFeatureAppTest = true disables the tenant-module
        // source-location filter so every @resolver field and node is generated regardless of
        // which (synthetic) module the fixture's types are attributed to.
        val resolverArgs = Args(
            tenantPackage = TENANT_PACKAGE,
            tenantPackagePrefix = TENANT_PACKAGE_PREFIX,
            tenantName = "golden",
            grtPackage = GRT_PACKAGE,
            modernModuleGeneratedDir = null,
            metainfGeneratedDir = null,
            resolverGeneratedDir = resolverDir,
            isFeatureAppTest = true,
            baseTypeMapper = ViaductBaseTypeMapper(schema),
        )
        schema.generateFieldResolvers(resolverArgs)
        schema.generateNodeResolvers(resolverArgs)

        val generated = readGeneratedTree(grtDir, "grts") + readGeneratedTree(resolverDir, "resolvers")

        if (isRegenerating()) {
            rewriteGoldenFiles(generated)
            return
        }

        assertOutputMatchesGolden(generated)
    }

    /** A generated source file, keyed by its path relative to the golden root. */
    private data class GeneratedFile(val relativePath: String, val content: String)

    private fun readGeneratedTree(
        root: File,
        prefix: String
    ): List<GeneratedFile> =
        root.walkTopDown()
            .filter { it.isFile }
            .map { file ->
                val rel = "$prefix/" + root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
                GeneratedFile(rel, file.readText().replace("\r\n", "\n"))
            }
            .sortedBy { it.relativePath }
            .toList()

    private fun assertOutputMatchesGolden(generated: List<GeneratedFile>) {
        val goldenPaths = listGoldenRelativePaths().sorted()
        val generatedPaths = generated.map { it.relativePath }.sorted()

        // The set of generated files is itself part of the contract: a refactor that adds or drops
        // a file changes behavior, so compare the file sets before comparing contents.
        assertEquals(
            goldenPaths,
            generatedPaths,
            "Set of generated files differs from golden snapshot. If this is an intentional " +
                "codegen change, regenerate with -D$REGENERATE_PROPERTY=true.",
        )

        for (file in generated) {
            assertEquals(
                readGolden(file.relativePath),
                file.content,
                "Generated ${file.relativePath} differs from golden snapshot. If this is an " +
                    "intentional codegen change, regenerate with -D$REGENERATE_PROPERTY=true.",
            )
        }
    }

    // ===== Schema loading =====

    /**
     * Loads the fixture schema and applies the framework defaults (the `@resolver`, `@idOf`,
     * `@backingData`, `@connection`, `@edge` directives, the `Node` interface, the `PageInfo`
     * type, standard scalars, and the `Query`/`Mutation` root types). This mirrors how the codegen
     * is driven in production and in `SchemaDrivenTests`, so the fixture only needs to declare the
     * domain types.
     */
    private fun loadSchemaWithDefaults(): ViaductSchema {
        val raw = this::class.java.classLoader.getResourceAsStream(SCHEMA_RESOURCE)
            ?.bufferedReader()?.use { it.readText() }
            ?: error("Resource not found on classpath: $SCHEMA_RESOURCE")

        // Apply the framework defaults in place so the user's domain types are retained, then
        // re-render to SDL. This mirrors JavaResolversCodegenTest's writeSchemaWithDefaults.
        val registry = SchemaParser().parse(raw)
        DefaultSchemaFactory.addDefaults(
            registry,
            DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
            DefaultSchemaFactory.IncludeNodeSchema.IfUsed,
            false,
            false,
            false,
        )
        val sdl = registry.toSDL(Predicates.alwaysTrue(), Predicates.alwaysTrue())
        return ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(sdl))
    }

    // ===== Golden-file IO =====

    private fun isRegenerating(): Boolean = System.getProperty(REGENERATE_PROPERTY, "false").toBoolean()

    private fun rewriteGoldenFiles(generated: List<GeneratedFile>) {
        if (GOLDEN_DIR.exists()) GOLDEN_DIR.deleteRecursively()
        GOLDEN_DIR.mkdirs()
        for (file in generated) {
            val dest = File(GOLDEN_DIR, file.relativePath)
            dest.parentFile.mkdirs()
            dest.writeText(file.content)
        }
    }

    /**
     * Lists golden snapshot paths (relative to [GOLDEN_RESOURCE_PREFIX]) from the classpath, so the
     * verification path works identically under Gradle and Bazel. Bazel runs tests from a sandbox
     * whose working directory is not the module directory, so the source-tree [GOLDEN_DIR] is only
     * usable by the Gradle-only regeneration path.
     */
    private fun listGoldenRelativePaths(): List<String> =
        ClassGraph().acceptPaths(GOLDEN_RESOURCE_PREFIX).scan().use { scan ->
            scan.allResources.map { it.path.removePrefix("$GOLDEN_RESOURCE_PREFIX/") }
        }

    private fun readGolden(relativePath: String): String {
        val resourcePath = "$GOLDEN_RESOURCE_PREFIX/$relativePath"
        val stream = this::class.java.classLoader.getResourceAsStream(resourcePath)
            ?: error("Golden not found on classpath: $resourcePath")
        return stream.bufferedReader().use { it.readText() }.replace("\r\n", "\n")
    }

    companion object {
        private const val SCHEMA_RESOURCE = "golden/golden_schema.graphqls"
        private const val GRT_PACKAGE = "viaduct.api.grts"
        private const val TENANT_PACKAGE = "com.example.tenant"
        private const val TENANT_PACKAGE_PREFIX = "com.example"
        private const val REGENERATE_PROPERTY = "viaduct.codegen.golden.regenerate"

        /**
         * Classpath prefix under which the golden snapshots are packaged as test resources. Goldens
         * are read from the classpath during verification so the test passes identically under
         * Gradle and Bazel.
         */
        private const val GOLDEN_RESOURCE_PREFIX = "golden/expected"

        /**
         * Source-tree location of the golden files, used only by the `-D…regenerate=true` path so
         * regeneration writes to the checked-in resources. Resolved relative to the module working
         * directory, which is only well-defined under Gradle; regeneration is always run via Gradle.
         */
        private val GOLDEN_DIR =
            Path("src", "test", "resources", "golden", "expected").toFile()
    }
}
