package viaduct.engine.runtime.execution

/**
 * Section 6.3.4: Normal and Serial Execution
 *
 * Controls the order in which selected fields are executed.
 */
enum class ExecutionMode {
    /** Allows fields to execute in any order, including in parallel, without requiring parallel execution. */
    Normal,

    /** Executes fields in selection order, completing each before starting the next */
    Serial,
}
