package viaduct.tenant.validation

typealias ErrorMessage = String

/*
 * Interface class for all the validators. Do not directly throw error
 * in validator classes. Instead, return a list of error messages that
 * will be collected by the caller class and report altogether to the user.
 */
interface IValidator {
    fun validateAndReportErrors(): ErrorMessage? {
        val errors: List<ErrorMessage>
        try {
            errors = validate()
        } catch (ex: Exception) {
            // Catch exception like building scoped schema failure during the execution of a validator
            return "Running ${this::class.simpleName} failed with exception: ${ex.message}. " +
                "Stack trace: ${ex.stackTraceToString()}"
        }

        if (errors.isNotEmpty()) {
            return "${this::class.simpleName} validation failed with errors: \n" +
                errors.joinToString("\n") { " - $it" }
        }
        return null
    }

    /*
     * Returns a list of error messages. Each error message should indicate
     * one validation error by this validator. If no error, then return an
     * empty list.
     */
    fun validate(): List<ErrorMessage>
}
