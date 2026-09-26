package viaduct.engine.api.spi

import kotlinx.coroutines.CancellationException
import viaduct.engine.api.EngineObjectData

/** Reads field values from materialized object data. */
@Deprecated("For Airbnb use only", level = DeprecationLevel.WARNING)
fun interface MaterializedFieldValueReader {
    /**
     * Returns the value of one field of [source].
     *
     * [source] already comes from the materialization that fetched the field with the requested
     * arguments, so the field is identified by name and response key alone.
     *
     * All field reads from materialized data go through here, so an object-typed field must be
     * returned as [EngineObjectData], or a list of it.
     *
     * @param source the object data that holds the field.
     * @param fieldName the field's name in the schema.
     * @param responseKey the field's alias, or [fieldName] if it has none. For `displayName: name`,
     *   [fieldName] is `name` and [responseKey] is `displayName`.
     * @return the value, with [ReadResult.fieldIsMissing] set when [source] does not have the
     *   field at all, as opposed to having it set to null.
     */
    suspend fun read(
        source: EngineObjectData,
        fieldName: String,
        responseKey: String
    ): ReadResult

    data class ReadResult(val value: Any?, val fieldIsMissing: Boolean)

    companion object {
        @Suppress("DEPRECATION")
        val Default = MaterializedFieldValueReader { source, fieldName, _ ->
            val value = source.fetchOrNull(fieldName)
            val fieldIsMissing = value == null && try {
                source.fetchSelections().none { it == fieldName }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            ReadResult(value, fieldIsMissing)
        }
    }
}
