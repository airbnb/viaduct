package viaduct.api.internal

import kotlin.collections.Map as KMap
import viaduct.engine.api.RawSelectionSet

/**
 * A [KeyMapping] describes the nature of keys between 2 representations.
 *
 * For example, a Json value is always keyed by selection name, while a GRT value may be keyed
 * by selections if it was obtained from the engine, or by field names if it was created by
 * a tenant.
 *
 * A [KeyMapping] is a "type hint" about the nature of an object key, which can be used by
 * a Conv to correctly map object values.
 */
data class KeyMapping(val fromType: KeyType, val toType: KeyType) {
    /** The type of key in an object */
    enum class KeyType {
        /** When describing the key of an Object entry, the key describes a selection in a selection set */
        Selection,

        /** When describing the key of an Object entry, the key describes a field name of a GraphQL object */
        FieldName
    }

    /** A [Map] provides methods for reversibly mapping keys */
    interface Map {
        /** For the provided [key] on a source type, return the corresponding IR object keys */
        fun forward(key: String): List<String>

        /** For the provided IR object [key], return the corresponding source type keys */
        fun invert(key: String): List<String>

        object Identity : Map {
            override fun forward(key: String): List<String> = listOf(key)

            override fun invert(key: String): List<String> = listOf(key)
        }

        companion object {
            /** Construct a [Map] from the provided forward and inverse maps */
            fun fromMaps(
                forward: KMap<String, List<String>>,
                inverse: KMap<String, List<String>>
            ): Map =
                object : Map {
                    override fun forward(key: String): List<String> = forward[key] ?: emptyList()

                    override fun invert(key: String): List<String> = inverse[key] ?: emptyList()
                }
        }
    }

    companion object {
        /** Source objects are keyed by selections and will be mapped to IR objects keyed by selections */
        val SelectionToSelection = KeyMapping(KeyType.Selection, KeyType.Selection)

        /** Source objects are keyed by field names and will be mapped to IR objects keyed by selections */
        val FieldNameToSelection = KeyMapping(KeyType.FieldName, KeyType.Selection)

        /** Source objects are keyed by field names and will be mapped to IR objects keyed by field names */
        val FieldNameToFieldName = KeyMapping(KeyType.FieldName, KeyType.FieldName)

        /**
         * Get a default [KeyMapping] that can support the provided [selectionSet]
         * @return [SelectionToSelection] if [selectionSet] is not null, otherwise [FieldNameToFieldName]
         */
        fun defaultKeyMapping(selectionSet: RawSelectionSet?): KeyMapping =
            if (selectionSet != null) {
                SelectionToSelection
            } else {
                FieldNameToFieldName
            }

        /** Create a [Map] for the given [keyMapping] and [selectionSet] */
        fun map(
            keyMapping: KeyMapping,
            selectionSet: RawSelectionSet?
        ): Map =
            if (keyMapping.fromType == keyMapping.toType) {
                Map.Identity
            } else {
                val selectionNameToFieldName = requireNotNull(selectionSet).selections()
                    .map { sel -> sel.selectionName to sel.fieldName }

                if (keyMapping.fromType == KeyType.FieldName && keyMapping.toType == KeyType.Selection) {
                    val forwardMap = selectionNameToFieldName.groupBy({ it.second }, { it.first })
                    val inverseMap = selectionNameToFieldName.groupBy({ it.first }, { it.second })
                    Map.fromMaps(forwardMap, inverseMap)
                } else if (keyMapping.fromType == KeyType.Selection && keyMapping.toType == KeyType.FieldName) {
                    val forwardMap = selectionNameToFieldName.groupBy({ it.first }, { it.second })
                    val inverseMap = selectionNameToFieldName.groupBy({ it.second }, { it.first })
                    Map.fromMaps(forwardMap, inverseMap)
                } else {
                    throw IllegalArgumentException("Unsupported mapping: $keyMapping")
                }
            }
    }
}
