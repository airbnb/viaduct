package viaduct.engine.api

interface CheckerExecutorFactory {
    fun checkerExecutorForField(
        schema: ViaductSchema,
        typeName: String,
        fieldName: String
    ): CheckerExecutor?

    fun checkerExecutorForType(
        schema: ViaductSchema,
        typeName: String
    ): CheckerExecutor?
}
