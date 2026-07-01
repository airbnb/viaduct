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

    private val _errors = mutableListOf<SchemaValidationError>()

    val errors: List<SchemaValidationError> get() = _errors.toList()

    fun reportError(
        code: String,
        message: String,
        location: SchemaLocation,
        severity: SchemaValidationError.Severity = SchemaValidationError.Severity.ERROR
    ) {
        _errors.add(SchemaValidationError(code, message, location, severity))
    }

    private val ruleCaches = mutableMapOf<Pair<ValidationRule, Any>, Any?>()

    /**
     * Per-context memoization for rules that need to derive intermediate state from the schema once
     * per (rule, key) pair. Cache lifetime equals this context, so rules need not own a long-lived
     * cache nor worry about cross-schema reuse semantics.
     *
     * Not thread-safe. Assumes the sequential, single-threaded rule walk the framework performs
     * today ([walkSchema]); if the walker is ever parallelized, callers must synchronize or the
     * backing map must be replaced with a concurrent one.
     */
    @Suppress("UNCHECKED_CAST")
    fun <K : Any, V : Any> computeIfAbsent(
        rule: ValidationRule,
        key: K,
        compute: (K) -> V
    ): V {
        val cacheKey = rule to key
        val cached = ruleCaches[cacheKey]
        if (cached != null) return cached as V
        val v = compute(key)
        ruleCaches[cacheKey] = v
        return v
    }

    /**
     * Walks the schema and invokes all rules at each node.
     */
    fun walkSchema(rules: List<ValidationRule>) {
        // Schema-level visit
        rules.forEach { it.visitSchema(this) }

        // Directive definitions
        schema.directives.values.forEach { directive ->
            rules.forEach { it.visitDirective(this, directive) }
            directive.args.forEach { arg ->
                rules.forEach { it.visitDirectiveArg(this, arg) }
            }
        }

        // Type definitions
        schema.types.values.forEach { typeDef ->
            rules.forEach { it.visitTypeDef(this, typeDef) }

            when (typeDef) {
                is ViaductSchema.Scalar ->
                    rules.forEach { it.visitScalar(this, typeDef) }

                is ViaductSchema.Enum -> {
                    rules.forEach { it.visitEnum(this, typeDef) }
                    typeDef.values.forEach { value ->
                        rules.forEach { it.visitEnumValue(this, value) }
                    }
                }

                is ViaductSchema.Union ->
                    rules.forEach { it.visitUnion(this, typeDef) }

                is ViaductSchema.Input -> {
                    rules.forEach { it.visitInput(this, typeDef) }
                    walkRecordFields(typeDef, rules)
                }

                is ViaductSchema.Interface -> {
                    rules.forEach { it.visitInterface(this, typeDef) }
                    walkRecordFields(typeDef, rules)
                }

                is ViaductSchema.Object -> {
                    rules.forEach { it.visitObject(this, typeDef) }
                    walkRecordFields(typeDef, rules)
                }
            }
        }
    }

    private fun walkRecordFields(
        record: ViaductSchema.Record,
        rules: List<ValidationRule>
    ) {
        record.fields.forEach { field ->
            rules.forEach { it.visitField(this, field) }
            field.args.forEach { arg ->
                rules.forEach { it.visitFieldArg(this, arg) }
            }
        }
    }
}
