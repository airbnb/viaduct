package viaduct.engine.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.engine.runtime.RequiredSelectionSetRegistry

class RequiredSelectionSetRegistryTest {
    @Test
    fun `Empty`() {
        val reg = RequiredSelectionSetRegistry.Empty
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("Query", "__typename", true))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("Foo", "foo", true))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("", "", true))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForType("Query", true))

        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("Query", "__typename", false))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("Foo", "foo", false))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForField("", "", false))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getRequiredSelectionSetsForType("Query", false))

        assertEquals(listOf<RequiredSelectionSet>(), reg.getFieldResolverRequiredSelectionSets("Foo", "foo"))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getFieldCheckerRequiredSelectionSets("Foo", "foo", true))
        assertEquals(listOf<RequiredSelectionSet>(), reg.getFieldCheckerRequiredSelectionSets("Foo", "foo", false))
    }
}
