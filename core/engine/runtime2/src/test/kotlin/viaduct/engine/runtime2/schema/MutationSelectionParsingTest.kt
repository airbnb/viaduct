package viaduct.engine.runtime2.schema

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.MutationSelectionForest
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.selectionForestOf

class MutationSelectionParsingTest {
    private val schemas = ViaductAndGJSchema.fromGraphQLSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(
                """
                directive @namespaceType on OBJECT
                directive @resolver on FIELD_DEFINITION
                type Query { value: Int }
                type Mutation { group: Group, update(value: Int = 1): Payload @resolver }
                type Group @namespaceType { nested: Nested, update(value: Int = 1): Payload @resolver }
                type Nested @namespaceType { update(value: Int): Payload @resolver }
                type Payload { value: Int }
                """.trimIndent(),
            ),
        ),
    )

    @Test
    fun `mutations collect by response key in source order while keys remain alias free`() {
        val forest = schemas.operationSelectionsFrom(
            """
            mutation {
                second: update { value }
                group { nested { update(value: 2) { value } } first: update { value } }
                ...More
                second: update { value }
            }
            fragment More on Mutation { third: update { value } }
            """.trimIndent(),
        ) as MutationSelectionForest
        val selections = forest.orderedSelections()
        assertEquals(listOf("second", "group", "third"), selections.map { it.responseKey })
        assertEquals(selections.first().key, selections.last().key)
        assertNotEquals(selections.first().responseKey, selections.last().responseKey)
        assertTrue(selections.all { it.possibleTypes == setOf(forest.type) && it.inclusionCondition === InclusionCondition.Always })
        val group = selections[1].subselections as MutationSelectionForest
        assertEquals(listOf("nested", "first"), group.orderedSelections().map { it.responseKey })
        assertInstanceOf(MutationSelectionForest::class.java, group.orderedSelections().first().subselections)
        assertTrue(selections.first().subselections !is MutationSelectionForest)
    }

    @Test
    fun `excluded fields and fragments do not occupy execution positions`() {
        val forest = schemas.operationSelectionsFrom(
            "mutation(${ '$' }enabled: Boolean!) { omitted: update @skip(if: true) { value } ... @include(if: ${ '$' }enabled) { group { update { value } } } kept: update { value } }",
            mapOf("enabled" to false),
        ) as MutationSelectionForest
        assertEquals(listOf("kept"), forest.orderedSelections().map { it.responseKey })
    }

    @Test
    fun `ordinary forests cannot contain mutation selections or mutation subselections`() {
        val forest = schemas.operationSelectionsFrom("mutation { group { update { value } } }") as MutationSelectionForest
        val member = forest.single()
        assertThrows<IllegalArgumentException> { selectionForestOf(member) }
        assertThrows<IllegalArgumentException> {
            selectionForestOf(Selection.of(member.key, member.possibleTypes, member.subselections))
        }
        assertThrows<IllegalArgumentException> { MutationSelectionForest.of(schemas.loweredSchema, schemas.loweredSchema.queryTypeDef!!, emptyList()) }
    }
}
