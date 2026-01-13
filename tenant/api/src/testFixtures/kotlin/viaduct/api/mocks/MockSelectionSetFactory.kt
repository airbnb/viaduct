package viaduct.api.mocks

import viaduct.api.internal.select.SelectionSetFactory
import viaduct.api.reflect.CompositeField
import viaduct.api.reflect.Field
import viaduct.api.reflect.Type
import viaduct.api.select.SelectionSet
import viaduct.api.types.CompositeOutput
import viaduct.engine.api.ViaductSchema

/**
 * Mock implementation of SelectionSetFactory for testing.
 *
 * This implementation creates simple string-based selection sets that are suitable
 * for unit testing resolver logic without requiring the full runtime selection set
 * implementation.
 *
 * ## Behavior
 *
 * - Selection sets store the raw selection string
 * - `contains()` checks if field name appears as a complete token in selection string
 * - `requestsType()` always returns true (assumes all types are requested)
 * - Suitable for unit tests where exact selection set behavior is not critical
 *
 * ## Usage
 *
 * ```kotlin
 * val schema = mkSchema(schemaSDL).viaduct
 * val factory = MockSelectionSetFactory(schema)
 *
 * val selections = factory.selectionsOn(
 *     type = MyType.Reflection,
 *     selections = "field1 field2 { nested }",
 *     variables = emptyMap()
 * )
 * ```
 *
 * @param schema The Viaduct schema used for type information
 * @since 1.0
 */
class MockSelectionSetFactory(
    private val schema: ViaductSchema
) : SelectionSetFactory {
    override fun <T : CompositeOutput> selectionsOn(
        type: Type<T>,
        selections: String,
        variables: Map<String, Any?>
    ): SelectionSet<T> {
        return if (selections.isBlank()) {
            SelectionSet.NoSelections as SelectionSet<T>
        } else {
            MockSelectionSet(type, selections)
        }
    }

    /**
     * Simple mock SelectionSet implementation that stores the selection string.
     *
     * This implementation provides basic field checking suitable for testing.
     * It tokenizes the selection string and checks for complete field name matches,
     * avoiding false positives from substring matching (e.g., "name" won't match "username").
     */
    private class MockSelectionSet<T : CompositeOutput>(
        override val type: Type<T>,
        private val selectionsString: String
    ) : SelectionSet<T> {
        companion object {
            /**
             * Regex pattern to tokenize GraphQL selection strings.
             * Splits on whitespace and GraphQL structural characters like {, }, (, ), :, etc.
             * Compiled once and shared across all MockSelectionSet instances.
             */
            private val TOKEN_PATTERN = Regex("[\\s{}(),:\\[\\]@!]+")
        }

        /**
         * Set of field names extracted from the selection string for O(1) lookup.
         * Computed lazily on first use.
         */
        private val fieldTokens: Set<String> by lazy {
            selectionsString.split(TOKEN_PATTERN)
                .filter { it.isNotBlank() }
                .toSet()
        }

        override fun <U : T> contains(field: Field<U>): Boolean {
            // Check for exact field name match in the tokenized selection string.
            // This prevents false positives where "name" incorrectly matches "username".
            return fieldTokens.contains(field.name)
        }

        override fun <U : T> requestsType(type: Type<U>): Boolean {
            // For testing purposes, assume all types are requested.
            // This simplifies test setup when type narrowing isn't being tested.
            return true
        }

        override fun <U : T, R : CompositeOutput> selectionSetFor(field: CompositeField<U, R>): SelectionSet<R> {
            // Return a mock selection set for the field's type.
            // In practice, this would extract nested selections, but for testing
            // we just return the same selections string. This is acceptable because
            // most tests don't deeply inspect nested selection behavior.
            return MockSelectionSet(field.type, selectionsString)
        }

        override fun <U : T> selectionSetFor(type: Type<U>): SelectionSet<U> {
            // Return a projected selection set for the given type.
            return MockSelectionSet(type, selectionsString)
        }

        override fun isEmpty(): Boolean = selectionsString.isBlank()

        override fun toString(): String = selectionsString

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MockSelectionSet<*>) return false
            return selectionsString == other.selectionsString && type == other.type
        }

        override fun hashCode(): Int {
            var result = type.hashCode()
            result = 31 * result + selectionsString.hashCode()
            return result
        }
    }
}
