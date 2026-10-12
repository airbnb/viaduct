package viaduct.engine.runtime2.arbitrary

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.objectOf

class PrecomputedResolverOutputsTest {
    @Test
    fun `node outputs are built during assembly and reused by type and ID`() {
        val discovery = PrecomputedResolverOutputs()
        val first = discovery.nodeResolver("First") { it }
        val second = discovery.nodeResolver("Second") { null }
        assertEquals("same-id", first("same-id"))
        assertNull(second("same-id"))

        val prepared = discovery.prepared()
        var materializations = 0
        val output = listOf("prepared")
        val lookup = prepared.nodeResolver("First") {
            materializations++
            output
        }
        val nullLookup = prepared.nodeResolver("Second") {
            materializations++
            null
        }
        assertEquals(2, materializations)
        repeat(3) {
            assertSame(output, lookup("same-id"))
            assertNull(nullLookup("same-id"))
        }
        assertEquals(2, materializations)
    }

    @Test
    fun `node ID snapshot rejects undiscovered IDs without generating outputs`() {
        val discovery = PrecomputedResolverOutputs()
        val record = discovery.nodeResolver("Node") { it }
        record("known")
        val prepared = discovery.prepared()
        record("late")

        val lookup = prepared.nodeResolver("Node") { it }
        assertEquals("known", lookup("known"))
        assertFailsWith<NoSuchElementException> { lookup("late") }
        val unused = prepared.nodeResolver("Unused") { error("Must not generate unused outputs") }
        assertFailsWith<NoSuchElementException> { unused("known") }
    }

    @Test
    fun `prepared field outputs do not inspect inputs while ordinary worlds retain sensitivity`() =
        runTest {
            val config =
                Config.default +
                    (ArgumentsEnabled to false) +
                    (SchemaObjectCount to 3..3) +
                    (ExplicitFieldResolverWeight to 1.0) +
                    (ResolverFragmentsEnabled to true) +
                    (ResolverFragmentWeight to 1.0) +
                    (NodeResolversEnabled to false)
            val random = RandomSource.seeded(4815162343L)
            val schema = Arb.schema(config).next(random)
            val registry = schema.registry(config).next(random)
            assertTrue(registry.resolverPrograms.values.any { it != ResolverProgramKind.CONSTANT })

            val preparedWorld =
                registry.world(
                    schema,
                    fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
                    typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_SUCCESS,
                    precomputedResolverOutputs = PrecomputedResolverOutputs().prepared(),
                )
            val ordinaryWorld =
                registry.world(
                    schema,
                    fieldCheckerMode = GeneratedFieldCheckerMode.RUNTIME_SUCCESS,
                    typeCheckerMode = GeneratedTypeCheckerMode.RUNTIME_SUCCESS,
                )
            registry.fieldResolverCoordinates.forEach { coordinate ->
                suspend fun invoke(world: viaduct.engine.runtime2.model.testing.TestWorld) =
                    world.resolverRegistry.resolver(world.schema.requireObjectField(coordinate.typeName, coordinate.fieldName))(
                        input =
                            object : EngineObjectData.Sync by world.schema.objectOf(coordinate.typeName) {
                                override fun getSelections(): Iterable<String> = error("Input was inspected")
                            },
                        queryValue = world.schema.objectOf("Query"),
                        arguments = Arguments.Resolved.of(world.schema.requireObjectField(coordinate.typeName, coordinate.fieldName), emptyMap()),
                        selectiveResolvers = false,
                        executionContext = ResolutionExecutionContext.Unsupported,
                    )

                assertEquals(invoke(preparedWorld).outputResolutionFingerprint(), invoke(preparedWorld).outputResolutionFingerprint())
                assertFailsWith<IllegalStateException> { invoke(ordinaryWorld) }

                val field = preparedWorld.schema.requireObjectField(coordinate.typeName, coordinate.fieldName)
                val arguments = Arguments.Resolved.of(field, emptyMap())
                assertSame(
                    CheckerResult.Success,
                    preparedWorld.resolverRegistry.fieldChecker(field)!!(arguments, emptyMap(), ResolutionExecutionContext.Unsupported),
                )
                if (field.containingDef.name != "Query") {
                    assertSame(
                        CheckerResult.Success,
                        preparedWorld.resolverRegistry.typeChecker(field.containingDef)!!(emptyMap(), ResolutionExecutionContext.Unsupported),
                    )
                }
                val ordinaryField = ordinaryWorld.schema.requireObjectField(coordinate.typeName, coordinate.fieldName)
                assertFailsWith<NoSuchElementException> {
                    ordinaryWorld.resolverRegistry.fieldChecker(ordinaryField)!!(arguments, emptyMap(), ResolutionExecutionContext.Unsupported)
                }
                if (ordinaryField.containingDef.name != "Query") {
                    assertFailsWith<NoSuchElementException> {
                        ordinaryWorld.resolverRegistry.typeChecker(ordinaryField.containingDef)!!(emptyMap(), ResolutionExecutionContext.Unsupported)
                    }
                }
            }
        }
}
