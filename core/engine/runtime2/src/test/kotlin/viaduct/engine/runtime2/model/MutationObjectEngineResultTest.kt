package viaduct.engine.runtime2.model

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.operationSelectionsFrom

class MutationObjectEngineResultTest {
    private val schemas = ViaductAndGJSchema.fromGraphQLSchema(
        UnExecutableSchemaGenerator.makeUnExecutableSchema(SchemaParser().parse("type Query { idle: Int } type Mutation { update: Int }")),
    )
    private val forest = schemas.operationSelectionsFrom("mutation { first: update second: update }") as MutationSelectionForest
    private val selection: MutationSelection = forest.orderedSelections().first()
    private val first: ObjectEngineResult.MutationKey = ObjectEngineResult.MutationKey.of(selection.key, "first")
    private val second: ObjectEngineResult.MutationKey = ObjectEngineResult.MutationKey.of(selection.key, "second")

    @Test
    fun `mutation publications distinguish response keys and forbid ordinary keys`() {
        assertNotEquals(first, second)
        assertNotEquals(selection.key, first)
        assertEquals(first, ObjectEngineResult.MutationKey.of(selection.key, "first"))
        val result = MutationObjectEngineResult.of(forest)
        assertThrows<IllegalArgumentException> { result.reserveCell(selection.key) }
        result.setCellValue(first, 1).fieldCheckerResult.complete(null)
        result.setCellValue(second, 2).fieldCheckerResult.complete(null)
        result.freeze()
        assertEquals(2, result.keys.size)
        assertTrue(result.isCompleted)
        assertEquals(1, result.getCell(first).value.get())
        assertEquals(2, result.getCell(second).value.get())
    }

    @Test
    fun `completed mutation result comparison preserves response identities`() {
        fun result(
            firstValue: Int,
            secondValue: Int
        ) = MutationObjectEngineResult.of(forest).apply {
            setCellValue(first, firstValue).fieldCheckerResult.complete(null)
            setCellValue(second, secondValue).fieldCheckerResult.complete(null)
            freeze()
        }
        assertTrue(result(1, 2).sameCompletedResultAs(result(1, 2)))
        assertFalse(result(1, 2).sameCompletedResultAs(result(2, 1)))
        val root = result(1, 2)
        val firstOccurrence = ResolverOccurrenceId.at(root, listOf(first))
        val secondOccurrence = ResolverOccurrenceId.at(root, listOf(second))
        assertFalse(firstOccurrence.hasSameRootRelativeAddressAs(secondOccurrence))
    }
}
