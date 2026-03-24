package viaduct.engine.runtime.tenantloading

class InvalidVariableException(
    val typeOrFieldCoordinate: TypeOrFieldCoordinate,
    val variableName: String,
    val reason: String
) : Exception() {
    override val message: String
        get() {
            val (typeName, fieldName) = typeOrFieldCoordinate
            val coord = if (fieldName != null) {
                "field `$typeName.$fieldName"
            } else {
                "type `$typeName`"
            }
            return "Invalid configuration for coordinate `$coord` variable `$variableName`: $reason"
        }
}
