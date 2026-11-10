package viaduct.graphql.schema.validation

import viaduct.graphql.schema.ViaductReverseSchema
import viaduct.graphql.schema.ViaductSchema

/**
 * Context passed to validation rules during schema traversal.
 * Provides access to the schema and error reporting.
 *
 * This class is open to allow subclasses to add custom context
 * for specialized validation use cases.
 */
open class ValidationContext(val schema: ViaductSchema) {
    /**
     * Reverse (inbound) navigation over the schema.  Constructed
     * lazily so that validation rules that don't need it pay no
     * cost.  Once constructed, shared across all rules for the
     * duration of the validation pass.
     */
    val reverseSchema: ViaductReverseSchema by lazy {
        ViaductReverseSchema.from(schema)
    }

    private val reportedErrors = mutableListOf<Pair<ValidationRule?, SchemaValidationError>>()
    private var currentRule: ValidationRule? = null

    val errors: List<SchemaValidationError> get() = reportedErrors.map { it.second }

    val errorsByRule: List<Pair<ValidationRule?, SchemaValidationError>> get() = reportedErrors.toList()

    fun reportError(
        code: String,
        message: String,
        location: SchemaLocation,
        severity: SchemaValidationError.Severity = SchemaValidationError.Severity.ERROR
    ) {
        reportedErrors.add(currentRule to SchemaValidationError(code, message, location, severity))
    }

    /**
     * Walks the schema and invokes all rules at each node.
     */
    fun walkSchema(rules: List<ValidationRule>) = walkSchema(rules) { _, callback -> callback() }

    fun walkSchema(
        rules: List<ValidationRule>,
        invokeRule: (ValidationRule, () -> Unit) -> Unit
    ) {
        // Schema-level visit
        visitRules(rules, invokeRule) { it.visitSchema(this) }

        // Directive definitions
        schema.directives.values.forEach { directive ->
            visitRules(rules, invokeRule) { it.visitDirective(this, directive) }
            directive.args.forEach { arg ->
                visitRules(rules, invokeRule) { it.visitDirectiveArg(this, arg) }
            }
        }

        // Type definitions
        schema.types.values.forEach { typeDef ->
            visitRules(rules, invokeRule) { it.visitTypeDef(this, typeDef) }

            when (typeDef) {
                is ViaductSchema.Scalar ->
                    visitRules(rules, invokeRule) { it.visitScalar(this, typeDef) }

                is ViaductSchema.Enum -> {
                    visitRules(rules, invokeRule) { it.visitEnum(this, typeDef) }
                    typeDef.values.forEach { value ->
                        visitRules(rules, invokeRule) { it.visitEnumValue(this, value) }
                    }
                }

                is ViaductSchema.Union ->
                    visitRules(rules, invokeRule) { it.visitUnion(this, typeDef) }

                is ViaductSchema.Input -> {
                    visitRules(rules, invokeRule) { it.visitInput(this, typeDef) }
                    walkRecordFields(typeDef, rules, invokeRule)
                }

                is ViaductSchema.Interface -> {
                    visitRules(rules, invokeRule) { it.visitInterface(this, typeDef) }
                    walkRecordFields(typeDef, rules, invokeRule)
                }

                is ViaductSchema.Object -> {
                    visitRules(rules, invokeRule) { it.visitObject(this, typeDef) }
                    walkRecordFields(typeDef, rules, invokeRule)
                }
            }
        }
    }

    private fun walkRecordFields(
        record: ViaductSchema.Record,
        rules: List<ValidationRule>,
        invokeRule: (ValidationRule, () -> Unit) -> Unit
    ) {
        record.fields.forEach { field ->
            visitRules(rules, invokeRule) { it.visitField(this, field) }
            field.args.forEach { arg ->
                visitRules(rules, invokeRule) { it.visitFieldArg(this, arg) }
            }
        }
    }

    private fun visitRules(
        rules: List<ValidationRule>,
        invokeRule: (ValidationRule, () -> Unit) -> Unit,
        visit: (ValidationRule) -> Unit
    ) {
        rules.forEach { rule ->
            val previousRule = currentRule
            currentRule = rule
            try {
                invokeRule(rule) { visit(rule) }
            } finally {
                currentRule = previousRule
            }
        }
    }
}
