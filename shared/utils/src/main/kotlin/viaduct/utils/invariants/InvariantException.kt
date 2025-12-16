package viaduct.utils.invariants

class InvariantException(
    className: String,
    fieldName: String,
    cause: Throwable
) : RuntimeException(
        "Exception in invariant test of class $className in field $fieldName: ${cause.message}",
        cause
    )
