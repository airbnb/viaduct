package viaduct.graphql.schema.validation

import viaduct.graphql.schema.ViaductSchema

/**
 * Represents a single validation error with location context.
 */
data class SchemaValidationError(
    val code: String,
    val message: String,
    val location: SchemaLocation,
    val severity: Severity = Severity.ERROR
) {
    enum class Severity { ERROR, WARNING }
}

/**
 * Location in the schema where an error occurred.
 */
data class SchemaLocation(
    val path: List<String>,
    val sourceLocation: ViaductSchema.SourceLocation? = null
) {
    override fun toString(): String {
        val file = sourceLocation?.let { "${it.sourceName}:" } ?: ""
        return file + path.joinToString(".")
    }

    fun withSourceLocation(loc: ViaductSchema.SourceLocation?) = copy(sourceLocation = loc)

    companion object {
        fun ofType(typeName: String) = SchemaLocation(listOf(typeName))

        fun ofField(
            typeName: String,
            fieldName: String
        ) = SchemaLocation(listOf(typeName, fieldName))

        fun ofDirective(directiveName: String) = SchemaLocation(listOf("@$directiveName"))
    }
}
