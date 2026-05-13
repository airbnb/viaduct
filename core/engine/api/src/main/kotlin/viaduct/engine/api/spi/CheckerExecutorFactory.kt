package viaduct.engine.api.spi

import viaduct.engine.api.ViaductSchema

/**
 * SPI for providing per-field and per-type access-control checkers.
 *
 * The engine calls this factory once per schema initialisation (or per field/type lookup) to
 * obtain [CheckerExecutor] instances. Implementations inspect the schema and return the
 * appropriate checker for each field or type, or `null` if no check is required.
 *
 * See `impldocs/modern-access-check.md` for a full description of the access-check architecture.
 */
interface CheckerExecutorFactory {
    /**
     * Returns a [CheckerExecutor] that enforces access control for the given field, or `null`
     * if no check is needed for [typeName].[fieldName].
     */
    fun checkerExecutorForField(
        schema: ViaductSchema,
        typeName: String,
        fieldName: String
    ): CheckerExecutor?

    /**
     * Returns a [CheckerExecutor] that enforces type-level access control for [typeName], or
     * `null` if no check is needed.
     */
    fun checkerExecutorForType(
        schema: ViaductSchema,
        typeName: String
    ): CheckerExecutor?
}
