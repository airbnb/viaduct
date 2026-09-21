package viaduct.arbitrary.cli

import graphql.schema.idl.SchemaParser
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.utils.DefaultSchemaFactory

/** Sanity checks for the SDL fragment [GenerateSchema] produces. */
class GenerateSchemaTest {
    @TempDir
    lateinit var tempDir: Path

    private fun generate(seed: Int): Path =
        tempDir.resolve("schema-$seed.graphqls").also {
            GenerateSchema().main(arrayOf("--output", it.toString(), "--seed", seed.toString()))
        }

    private fun generateDerived(
        name: String,
        inputs: List<Path>,
        variant: Long = 0
    ): String {
        val out = tempDir.resolve("$name.graphqls")
        val args = listOf("--output", out.toString(), "--seed-variant", variant.toString()) +
            inputs.flatMap { listOf("--seed-input", it.toString()) }
        GenerateSchema().main(args.toTypedArray())
        return out.readText()
    }

    private fun input(
        name: String,
        content: String
    ): Path = tempDir.resolve(name).also { it.writeText(content) }

    @Test
    fun `derived schema depends on contents instead of file paths or input order`() {
        val a = input("a.txt", "codegen v1")
        val sameAsA = input("a-copy.txt", "codegen v1")
        val b = input("b.txt", "codegen v2")
        val schema = generateDerived("original", listOf(a, b))

        assertEquals(schema, generateDerived("repeated", listOf(a, b)))
        assertEquals(schema, generateDerived("relocated", listOf(b, sameAsA)))

        b.writeText("codegen v3")
        assertNotEquals(schema.substringAfter("\n\n"), generateDerived("changed", listOf(a, b)).substringAfter("\n\n"))
    }

    @Test
    fun `changing the variant generates a different reproducible schema`() {
        val source = input("codegen.txt", "codegen")
        val original = generateDerived("original", listOf(source))
        val variant = generateDerived("variant", listOf(source), variant = 1)

        assertNotEquals(original.substringAfter("\n\n"), variant.substringAfter("\n\n"))
        assertEquals(variant, generateDerived("repeated", listOf(source), variant = 1))
    }

    @Test
    fun `file boundaries affect the derived schema`() {
        val split = generateDerived("split", listOf(input("a.txt", "a"), input("bc.txt", "bc")))
        val otherSplit = generateDerived("other-split", listOf(input("ab.txt", "ab"), input("c.txt", "c")))

        assertNotEquals(split.substringAfter("\n\n"), otherSplit.substringAfter("\n\n"))
    }

    @Test
    fun `explicit seed reproduces the derived schema`() {
        val schema = generateDerived("derived", listOf(input("codegen.txt", "codegen")), variant = Long.MIN_VALUE)
        val seed = schema.lineSequence().first { it.startsWith("# seed=") }.removePrefix("# seed=")
        val out = tempDir.resolve("reproduced.graphqls")

        GenerateSchema().main(arrayOf("--output", out.toString(), "--seed", seed))

        assertEquals(schema, out.readText())
    }

    @Test
    fun `inputs alone use variant zero and variant alone is deterministic`() {
        val source = input("codegen.txt", "codegen")
        val out = tempDir.resolve("inputs-only.graphqls")

        GenerateSchema().main(arrayOf("--output", out.toString(), "--seed-input", source.toString()))

        assertEquals(generateDerived("explicit-zero", listOf(source)), out.readText())
        assertEquals(generateDerived("variant-only", emptyList(), variant = 1), generateDerived("repeated", emptyList(), variant = 1))
    }

    @Test
    fun `explicit seed cannot be combined with derived seed options`() {
        val out = tempDir.resolve("conflict.graphqls").toString()
        assertThrows(IllegalArgumentException::class.java) {
            GenerateSchema().main(arrayOf("--output", out, "--seed", "1", "--seed-variant", "1"))
        }
        val source = input("codegen.txt", "codegen")
        assertThrows(IllegalArgumentException::class.java) {
            GenerateSchema().main(arrayOf("--output", out, "--seed", "1", "--seed-input", source.toString()))
        }
    }

    @Test
    fun `generated schema fragment is valid SDL and covers every type kind it guarantees`() {
        val observedDirectives = mutableSetOf<String>()
        // A handful of fixed seeds rather than one: generation is randomized, and any single seed
        // could land on a config-legal but low-coverage schema.
        for (seed in 0 until 5) {
            // The fragment applies Viaduct's directives and implements `Node` without declaring
            // them, so decode it the way real consumers do: spliced into the default schema.
            val output = generate(seed)
            val sdl = output.readText()
            val registry = SchemaParser().parse(output.toFile())
            val defaultDirectiveNames = DefaultSchemaFactory.DefaultDirective.values().map { it.directiveName } +
                setOf("skip", "include", "deprecated", "specifiedBy", "oneOf", "defer", "experimental_disableErrorPropagation")

            assertFalse(registry.schemaDefinition().isPresent, "seed=$seed: fragment must omit root operation wiring")
            assertFalse(registry.types().containsKey("Node"), "seed=$seed: fragment must omit the Node definition")
            assertTrue(registry.getDirectiveDefinitions().keys.none { it in defaultDirectiveNames })
            assertTrue(registry.getDirectiveDefinitions().isNotEmpty(), "seed=$seed: expected custom directive definitions")
            listOf("idOf", "resolver").filterTo(observedDirectives) { sdl.contains("@$it(") }

            DefaultSchemaFactory.addDefaults(
                registry = registry,
                includeNodeDefinition = DefaultSchemaFactory.IncludeNodeSchema.Always,
                includeNodeQueries = DefaultSchemaFactory.IncludeNodeSchema.Never,
            )

            val schema = ViaductSchema.fromTypeDefinitionRegistry(registry)
            val defs = schema.types.values

            val objects = defs.filterIsInstance<ViaductSchema.Object>()
            val interfaces = defs.filterIsInstance<ViaductSchema.Interface>()
            val unions = defs.filterIsInstance<ViaductSchema.Union>()
            val inputs = defs.filterIsInstance<ViaductSchema.Input>()
            val enums = defs.filterIsInstance<ViaductSchema.Enum>()

            assertTrue(objects.isNotEmpty(), "seed=$seed: expected at least one object type")
            assertTrue(interfaces.isNotEmpty(), "seed=$seed: expected at least one interface type")
            assertTrue(unions.any { it.possibleObjectTypes.size > 1 }, "seed=$seed: expected a union with multiple members")
            assertTrue(inputs.isNotEmpty(), "seed=$seed: expected at least one input type")
            assertTrue(enums.isNotEmpty(), "seed=$seed: expected at least one enum type")
            assertTrue(
                interfaces.all { iface -> objects.any { obj -> obj.supers.any { it.name == iface.name } } },
                "seed=$seed: expected every interface to have an implementing object"
            )
        }
        assertEquals(setOf("idOf", "resolver"), observedDirectives)
    }

    // Case-only-different names only break on a case-insensitive filesystem, so no Linux CI job can
    // catch a regression here. Enough seeds that at least one would collide without the dedupe.
    @Test
    fun `generated schema fragment has no type names differing only by case`() {
        for (seed in 0 until 25) {
            val registry = SchemaParser().parse(generate(seed).toFile())

            val collisions = (registry.types().keys + registry.scalars().keys + registry.getDirectiveDefinitions().keys)
                .groupBy(String::lowercase)
                .values
                .filter { it.size > 1 }

            assertTrue(
                collisions.isEmpty(),
                "seed=$seed: GRT codegen writes one file per type name, so these would overwrite " +
                    "each other on a case-insensitive filesystem: $collisions"
            )
        }
    }
}
