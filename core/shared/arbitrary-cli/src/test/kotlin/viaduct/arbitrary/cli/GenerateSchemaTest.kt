package viaduct.arbitrary.cli

import java.nio.file.Files
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/** Sanity checks for the SDL fragment [GenerateSchema] produces. */
class GenerateSchemaTest {
    @Test
    fun `generated schema fragment is valid SDL and covers every type kind it guarantees`() {
        // A handful of fixed seeds rather than one: generation is randomized, and any single seed
        // could land on a config-legal but low-coverage schema.
        for (seed in 0 until 5) {
            val output = Files.createTempFile("generate-schema-test", ".graphqls")
            GenerateSchema().main(arrayOf("--output", output.toString(), "--seed", seed.toString()))

            val schema = ViaductSchema.fromTypeDefinitionRegistry(output.readText())
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
}
