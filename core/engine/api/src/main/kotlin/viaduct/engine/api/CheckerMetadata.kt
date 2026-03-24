package viaduct.engine.api

/**
 * Metadata about a checker for instrumentation and observability purposes.
 *
 * @property checkerName The name/type of the checker (e.g., "Himeji", "Gandalf", "Custom")
 * @property typeName The GraphQL type this checker is attached to
 * @property fieldName The GraphQL field this checker is attached to (null for type-level checkers)
 */
data class CheckerMetadata(
    val checkerName: String,
    val typeName: String,
    val fieldName: String? = null
) {
    fun toTagString(): String =
        if (fieldName != null) {
            "$checkerName:$typeName.$fieldName"
        } else {
            "$checkerName:$typeName"
        }
}
