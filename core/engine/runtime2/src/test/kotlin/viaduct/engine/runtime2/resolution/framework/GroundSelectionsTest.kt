package viaduct.engine.runtime2.resolution.framework

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.requireField
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.testRoot
import viaduct.engine.runtime2.resolvers.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

class GroundSelectionsTest {
    @Test
    fun `instantiation rejects an uninstantiated variable template`() {
        val fixture = Fixture()
        val variableField = fixture.schema.requireObjectField("Query", "search")
        val symbolic =
            fixture.searchSelection(
                mapOf("values" to listOf(Arguments.Variable.of(variableField, "x"))),
            )

        assertFailsWith<IllegalStateException> {
            selectionForestOf(symbolic).merge(fixture.query).instantiateBindings(fixture.operation)
        }
    }

    @Test
    fun `instantiation rejects an unbound variable instance`() {
        val fixture = Fixture()
        val variableField = fixture.schema.requireObjectField("Query", "search")
        val variable = Arguments.Variable.of(variableField, "x").instanceAt(emptyList())
        val symbolic = fixture.searchSelection(mapOf("values" to listOf(variable)))

        assertFailsWith<IllegalStateException> {
            selectionForestOf(symbolic).merge(fixture.query).instantiateBindings(fixture.operation)
        }
    }

    @Test
    fun `bound nested variables merge with equal concrete arguments`() {
        val fixture = Fixture()
        val variableField = fixture.schema.requireObjectField("Query", "search")
        val variable =
            Arguments.Variable.of(variableField, "x")
                .instanceAt(listOf(ListEngineResult.Index.of(0)))
        val symbolic = fixture.searchSelection(mapOf("values" to listOf(variable)))
        val concrete = fixture.searchSelection(mapOf("values" to listOf(1)))
        val variableId = requireNotNull(variable.instanceId)
        fixture.operation.variableBindings.declareBinding(variableId)
        fixture.operation.variableBindings.completeBinding(variableId, 1)

        val merged =
            selectionForestOf(symbolic, concrete).merge(fixture.query).instantiateBindings(fixture.operation)

        assertEquals(1, merged.size)
        assertEquals(concrete.objectKey(fixture.query), merged.single().key)
    }

    @Test
    fun `repeated merge preserves a key containing substituted list bindings`() {
        val fixture = Fixture()
        val source = fixture.schema.requireObjectField("Query", "source")
        val variable = Arguments.Variable.of(source, "values").instanceAt(emptyList())
        val binding =
            Arguments.Resolved
                .of(source, mapOf("values" to listOf(1, 2)))
                .fieldValues
                .getValue("values")
        val symbolic =
            fixture.selection(
                typeName = "Query",
                fieldName = "nested",
                arguments = mapOf("values" to listOf(variable, variable)),
            )
        val variableId = requireNotNull(variable.instanceId)
        fixture.operation.variableBindings.declareBinding(variableId)
        fixture.operation.variableBindings.completeBinding(variableId, binding)

        val once =
            selectionForestOf(symbolic).merge(fixture.query).instantiateBindings(fixture.operation)
        val twice = once.instantiateBindings(fixture.operation)

        assertEquals(once.single().key, twice.single().key)
    }

    private class Fixture {
        val worldFixture = TestWorld.fromSDL(SCHEMA)
        val world = worldFixture.assumptions
        val operation = SharedOperationContext.create(world)
        val schema = world.schema
        val query = schema.requireQueryTypeDef()

        fun selection(
            typeName: String,
            fieldName: String,
            arguments: Map<String, Any?> = emptyMap(),
            possibleTypes: Set<ViaductSchema.Object> =
                (schema.requireType(typeName) as ViaductSchema.CompositeTypeDef).possibleObjectTypes,
            subselections: SelectionForest = selectionForestOf(),
        ): Selection =
            Selection.of(
                key = ObjectEngineResult.Key.of(schema.requireField(typeName, fieldName), arguments),
                possibleTypes = possibleTypes,
                subselections = subselections,
            )

        fun searchSelection(filter: Any): Selection =
            selection(
                typeName = "Query",
                fieldName = "search",
                arguments = mapOf("filter" to filter),
            )
    }

    private companion object {
        val SCHEMA =
            """
            input Filter {
              values: [Int!]!
            }

            type Query {
              search(filter: Filter!): Int!
              source(values: [Int!]!): Int!
              nested(values: [[Int]]): Int!
            }
            """.trimIndent()
    }
}

private fun Arguments.Variable.instanceAt(path: List<PathComponent>): Arguments.Variable =
    instantiate(
        ResolverOccurrenceId.at((target as ResolverTarget.FieldTarget).field.testRoot(), path),
    )
