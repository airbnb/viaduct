@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution.framework

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.execution.ScalarFeatureTest
import viaduct.engine.runtime2.model.BackingDataEngineResult
import viaduct.engine.runtime2.model.IDEngineResult
import viaduct.engine.runtime2.model.JSONEngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.invariants.conformsToSchema
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.toEngineOutputData
import viaduct.engine.runtime2.resolution.ResolutionDispatcherResource
import viaduct.engine.runtime2.resolution.resolve as resolveProduction
import viaduct.engine.runtime2.resolvers.resolver01.resolve as resolve01
import viaduct.engine.runtime2.resolvers.resolver02.resolve as resolve02
import viaduct.engine.runtime2.resolvers.resolver03.resolve as resolve03
import viaduct.engine.runtime2.resolvers.resolver06.resolve as resolve06
import viaduct.engine.runtime2.resolvers.resolver07.resolve as resolve07
import viaduct.engine.runtime2.resolvers.resolver08.resolve as resolve08
import viaduct.engine.runtime2.resolvers.resolver21.resolve as resolve21
import viaduct.engine.runtime2.resolvers.resolver22.resolve as resolve22
import viaduct.engine.runtime2.resolvers.resolver23.resolve as resolve23
import viaduct.engine.runtime2.schema.operationSelectionsFrom
import viaduct.graphql.schema.ViaductSchema

/** Verifies the same scalar construction and correctness judgment across the maintained ladder. */
class ScalarResolutionTest : ResolutionDispatcherResource {
    private class Subject(
        val name: String,
        val selective: Boolean,
        val resolve: SharedOperationContext<*>.(SelectionForest) -> ObjectEngineResult,
    )

    private val subjects = listOf(
        Subject("Resolver01", false) { resolve01(it) },
        Subject("Resolver02", false) { resolve02(it) },
        Subject("Resolver03", true) { resolve03(it) },
        Subject("Resolver06", false) { resolve06(it) },
        Subject("Resolver07", false) { resolve07(it) },
        Subject("Resolver08", true) { resolve08(it) },
        Subject("Resolver21", false) { resolve21(it) },
        Subject("Resolver22", false) { resolve22(it) },
        Subject("Resolver23", true) { resolve23(it) },
        Subject("Resolution", true) { resolveProduction(it, resolverDispatcher) },
    )

    @TestFactory
    fun `correctness replay rejects an equal but distinct backing payload`() =
        subjects.map { subject ->
            dynamicTest("${subject.name} backing payload identity") {
                val payload = mutableMapOf("value" to 1)
                val world = TestWorld.fromSDL(
                    selectiveResolvers = subject.selective,
                    schemaSDL = "type Query { backing: BackingData }",
                    fieldResolvers = { schemas ->
                        val schema = schemas.loweredSchema
                        mapOf(
                            schema.requireObjectField("Query", "backing") to
                                fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> payload },
                        )
                    },
                )
                val operation = SharedOperationContext.create(world.assumptions)
                val selections = world.schemas.operationSelectionsFrom("{ backing }")
                val result = subject.resolve(operation, selections)
                val demand = selections.merge(world.schema.requireQueryTypeDef())
                assertTrue(result.correctResolution(operation, demand))

                val distinctPayload = mutableMapOf("value" to 1)
                assertEquals(payload, distinctPayload)
                val corrupted = ObjectEngineResult.of(
                    result.type,
                    mapOf(result.keys.single() to BackingDataEngineResult.of(distinctPayload)),
                )
                assertFalse(corrupted.correctResolution(operation, demand))
            }
        }

    @TestFactory
    fun `scalars remain leaves through lists references and correctness replay`() =
        subjects.flatMap { subject ->
            val backing = ScalarFeatureTest.ScalarCase("BackingData", "", Any(), Any())
            (ScalarFeatureTest.scalars().toList() + backing).map { scalar ->
                dynamicTest("${subject.name} ${scalar.name}") {
                    val observer = CorrectnessResolverObserver()
                    val world = TestWorld.fromSDL(
                        selectiveResolvers = subject.selective,
                        schemaSDL = "type Query { value: ${scalar.name}! values: [${scalar.name}]! reference: ${scalar.name}! }",
                        fieldResolvers = { schemas ->
                            val schema = schemas.loweredSchema
                            val value = schema.requireObjectField("Query", "value")
                            val empty = schema.emptyFragmentOf("Query")
                            mapOf(
                                value to fieldResolverOf(empty) { _, _ -> scalar.output },
                                schema.requireObjectField("Query", "values") to fieldResolverOf(empty) { _, _ -> listOf(scalar.output, null) },
                                schema.requireObjectField("Query", "reference") to fieldResolverOf(empty) { _, _ -> RootFieldReferenceData.of(listOf(value), emptyMap()) },
                            )
                        },
                    )
                    val operation = SharedOperationContext.create(world.assumptions, resolverObserver = observer)
                    val selections = world.schemas.operationSelectionsFrom("{ value values reference }")
                    val result = subject.resolve(operation, selections)
                    val type = world.schema.requireObjectField("Query", "value").type.baseTypeDef as ViaductSchema.SimpleTypeDef
                    val values = result.keys.associate { it.field.name to result.getCell(it).value.get() }
                    assertEquals(scalar.output, values.getValue("value")!!.toEngineOutputData(type))
                    assertEquals(scalar.output, values.getValue("reference")!!.toEngineOutputData(type))
                    val list = values.getValue("values") as ListEngineResult
                    assertEquals(scalar.output, list[0].value.get()!!.toEngineOutputData(type))
                    assertEquals(null, list[1].value.get())
                    val scalarResult = values.getValue("value")
                    when (scalar.name) {
                        "JSON" -> assertTrue(scalarResult is JSONEngineResult)
                        "BackingData" -> assertTrue(scalarResult is BackingDataEngineResult)
                        "ID" -> assertTrue(scalarResult is IDEngineResult)
                        else -> assertEquals(scalar.output.javaClass, scalarResult!!.javaClass)
                    }
                    assertTrue(result.conformsToSchema(emptyMap()))
                    assertTrue(result.correctResolution(operation, selections.merge(world.schema.requireQueryTypeDef())))
                    assertEquals(4, observer.invokedResolverOccurrences().size)
                }
            }
        }
}
