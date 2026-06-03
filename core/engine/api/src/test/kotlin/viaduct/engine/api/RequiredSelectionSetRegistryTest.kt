package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.RequiredSelectionSetRegistry

class RequiredSelectionSetRegistryTest {
    @Test
    fun `Empty`() {
        val reg = RequiredSelectionSetRegistry.Empty
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("Query", "__typename"))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("Foo", "foo"))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("", ""))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForType("Query"))

        assertEquals(listOf<RequiredSelectionSet>(), reg.getFieldResolverRequiredSelectionSets("Foo", "foo"))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getFieldCheckerRequiredSelectionSets("Foo", "foo"))
    }
}
