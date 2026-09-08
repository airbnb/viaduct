package viaduct.arbitrary.cli

import graphql.schema.idl.SchemaParser
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/** Sanity checks for the SDL fragment [GenerateSchema] produces. */
class GenerateSchemaTest {
    @TempDir
    lateinit var tempDir: Path

    private fun generate(seed: Int): Path =
        tempDir.resolve("schema-$seed.graphqls").also {
            GenerateSchema().main(arrayOf("--output", it.toString(), "--seed", seed.toString()))
        }

    @Test
    fun `generated schema fragment is valid SDL and covers every type kind it guarantees`() {
        // A handful of fixed seeds rather than one: generation is randomized, and any single seed
        // could land on a config-legal but low-coverage schema.
        for (seed in 0 until 5) {
            val schema = ViaductSchema.fromTypeDefinitionRegistry(generate(seed).readText())
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
