package viaduct.engine.api

/** Base interface for a variable declaration that can be parsed from a [viaduct.api.Variable] annotation */
sealed interface SelectionSetVariable {
    val name: String
}

/** Interface for a variable declaration that derives its value from a path into a selection set */
sealed interface FromFieldVariable {
    val name: String
    val valueFromPath: String
}

/**
 * A variable declaration whose value is derived from a "."-delimited path into an associated
 * selection set.
 *
 * @see viaduct.api.Variable#fromObjectField
 */
data class FromObjectFieldVariable(
    override val name: String,
    override val valueFromPath: String
) : SelectionSetVariable, FromFieldVariable

/**
 * A variable declaration whose value is derived from a "." delimited path into
 * a query field value of the field that uses this Variable.
 *
 * @see viaduct.api.Variable#fromQueryField
 */
data class FromQueryFieldVariable(
    override val name: String,
    override val valueFromPath: String
) : SelectionSetVariable, FromFieldVariable

/**
 * A variable declaration whose value is derived from a "." delimited path into
 * an argument value of the field that uses this Variable.
 *
 * @see viaduct.api.Variable#fromArgument
 */
data class FromArgumentVariable(
    override val name: String,
    val valueFromPath: String
) : SelectionSetVariable
