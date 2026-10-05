package viaduct.engine.runtime2.resolution

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.FieldResolverDefinition
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom

class MutationRegistryTest {
    @Test
    fun `mutation root and namespace resolvers reject object and Query fragments`() {
        for (owner in listOf("Mutation", "Group")) {
            for (queryFragment in listOf(false, true)) {
                val failure = assertThrows<IllegalArgumentException> {
                    TestWorld.fromSDL(
                        """
                        directive @namespaceType on OBJECT
                        type Query { idle: Int }
                        type Mutation { update: Int, group: Group }
                        type Group @namespaceType { update: Int }
                        """.trimIndent(),
                        fieldResolvers = { schemas ->
                            val schema = schemas.loweredSchema
                            val definition: FieldResolverDefinition = if (queryFragment) {
                                fieldResolverOf(schema.emptyFragmentOf(owner), schemas.fragmentFrom("fragment Input on Query { idle }")) { _, _, _ -> 1 }
                            } else {
                                fieldResolverOf(schemas.fragmentFrom("fragment Input on $owner { update }")) { _, _ -> 1 }
                            }
                            mapOf(schema.requireObjectField(owner, "update") to definition)
                        },
                    )
                }
                assertTrue(failure.message!!.contains("cannot declare object or Query fragments; use ctx.query()"))
            }
        }
    }
}
