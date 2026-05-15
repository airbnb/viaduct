package viaduct.graphql.schema.scopes

object ScopeIdSyntax {
    const val PATTERN_STRING = "^[a-z]+$"

    private val PATTERN = Regex(PATTERN_STRING)

    fun validate(id: String): Result<String> =
        if (PATTERN.matches(id)) {
            Result.success(id)
        } else {
            Result.failure(
                IllegalArgumentException(
                    "Scope id \"$id\" is invalid: must be one or more lowercase ASCII letters " +
                        "(regex: $PATTERN_STRING)"
                )
            )
        }
}
