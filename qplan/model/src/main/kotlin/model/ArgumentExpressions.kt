package model

import model.invariants.conformsToInputSchemaType
import viaduct.graphql.schema.ViaductSchema

/**
 * A schema-checked argument expression.
 *
 * This is an internal natural union of [EngineInputData], [Arguments.Variable],
 * [ArgumentResolutionError], lists, and input-object maps. Null represents GraphQL null. The
 * expected schema type supplies the distinctions erased by the broad Kotlin type.
 */
internal typealias ArgumentExpression = Any

private data class ArgumentsImpl(
    val fieldValues: Map<String, ArgumentExpression?>,
) : Arguments

private data class ArgumentsTemplateImpl(
    val fieldValues: Map<String, ArgumentExpression?>,
) : Arguments.Template {
    override fun instantiate(
        expectedField: ViaductSchema.Field,
        resolverOccurrenceId: ResolverOccurrenceId,
    ): Arguments =
        argumentsOfExpressions(
            fieldValues.mapValues { (_, value) ->
                value.instantiateVariables(resolverOccurrenceId)
            },
        ).validatedAgainst(expectedField)
}

internal fun argumentsOf(
    field: ViaductSchema.Field,
    fields: Map<String, Any?>,
): Arguments {
    if (fields.isEmpty() && field.args.all { arg -> !arg.hasDefault && arg.type.isNullable }) {
        return argumentsOfGround(emptyMap())
    }
    return argumentsOfExpressions(
        coerceArgumentFields(field, fields),
    )
}

internal fun argumentTemplateOf(
    expectedField: ViaductSchema.Field,
    arguments: Arguments,
): Arguments.Template {
    require(arguments != Arguments.Error) {
        "Erroneous arguments cannot become a registry template"
    }
    require(arguments.usedVariables().all(Arguments.Variable::isTemplate)) {
        "A registry argument template cannot contain instantiated variables"
    }
    val template =
        if (arguments is Arguments.Template) {
            arguments
        } else {
            ArgumentsTemplateImpl(arguments.fieldExpressions())
        }
    return template.validatedAgainst(expectedField)
}

internal fun coerceArgumentExpression(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    value: Any?,
): ArgumentExpression? {
    if (value == null) {
        if (!typeExpr.isNullable) throw ClassCastException()
        return null
    }
    if (value == ArgumentResolutionError || value is Arguments.Variable) return value

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        val values = value as? List<*> ?: throw ClassCastException()
        return values.map { element ->
            coerceArgumentExpression(elementType, element)
        }
    }
    return when (val type = typeExpr.baseTypeDef) {
        is ViaductSchema.SimpleTypeDef ->
            requireNotNull(toEngineInputData(typeExpr, value)) {
                "A non-null argument expression cannot coerce to null"
            }
        is ViaductSchema.Input -> {
            val fields = value.toStringKeyedArgumentMap()
            coerceArgumentFields(type, fields)
        }
        else -> error("Unsupported input type: ${type.name}")
    }
}

private fun coerceArgumentFields(
    type: ViaductSchema.Input,
    fields: Map<String, Any?>,
): Map<String, ArgumentExpression?> {
    val suppliedFields =
        fields.mapValues { (fieldName, value) ->
            val field = type.field(fieldName) ?: throw ClassCastException()
            coerceArgumentExpression(field.inputType, value)
        }

    val values =
        type.fields
            .mapNotNull { field ->
                val defaultValue = field.coercedDefaultValue()
                if (defaultValue is CoercedDefaultValue.Present) {
                    field.name to defaultValue.value
                } else {
                    null
                }
            }.toMap() + suppliedFields
    if (!values.keys.containsAll(type.requiredFieldNames())) throw ClassCastException()
    return values
}

private fun coerceArgumentFields(
    field: ViaductSchema.Field,
    fields: Map<String, Any?>,
): Map<String, ArgumentExpression?> {
    val suppliedFields =
        fields.mapValues { (argName, value) ->
            val arg = field.arg(argName) ?: throw ClassCastException()
            coerceArgumentExpression(arg.inputType, value)
        }

    val values =
        field.args
            .mapNotNull { arg ->
                val defaultValue = arg.coercedDefaultValue()
                if (defaultValue is CoercedDefaultValue.Present) {
                    arg.name to defaultValue.value
                } else {
                    null
                }
            }.toMap() + suppliedFields
    if (!field.requiredArgsArePresentIn(values)) throw ClassCastException()
    return values
}

private fun argumentsOfExpressions(fields: Map<String, ArgumentExpression?>): Arguments =
    when {
        fields.values.any(ArgumentExpression?::containsArgumentError) -> Arguments.Error
        fields.values.none(ArgumentExpression?::containsVariable) ->
            argumentsOfGround(
                fields.mapValues { (_, value) -> value.toGroundInputData() },
            )
        else -> ArgumentsImpl(fields)
    }

internal fun Arguments.fieldExpressions(): Map<String, ArgumentExpression?> =
    when (this) {
        Arguments.Error -> error("Erroneous arguments have no field expressions")
        is Arguments.Resolved -> fieldValues
        is ArgumentsImpl -> fieldValues
        is ArgumentsTemplateImpl -> fieldValues
    }

private fun <T : Arguments> T.validatedAgainst(expectedField: ViaductSchema.Field): T {
    require(conformsToArgumentDefinition(expectedField)) {
        "Argument expressions do not conform to the expected field"
    }
    return this
}

internal fun Arguments.conformsToArgumentDefinition(expectedField: ViaductSchema.Field): Boolean =
    when (this) {
        Arguments.Error -> true
        else ->
            fieldExpressions().let { fields ->
                expectedField.requiredArgsArePresentIn(fields) &&
                    fields.all { (name, value) ->
                        val arg = expectedField.arg(name) ?: return@all false
                        value.conformsToArgumentType(arg.inputType)
                    }
            }
    }

private fun ArgumentExpression?.conformsToArgumentType(typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>): Boolean {
    if (this == null) return typeExpr.isNullable
    if (this == ArgumentResolutionError || this is Arguments.Variable) return true

    val elementType = typeExpr.unwrapList()
    if (elementType != null) {
        return this is List<*> &&
            all { element -> element.conformsToArgumentType(elementType) }
    }

    return when (val expectedType = typeExpr.baseTypeDef) {
        is ViaductSchema.Input -> {
            val fields = this as? Map<*, *> ?: return false
            val names = fields.keys
            names.all { it is String } &&
                names.containsAll(expectedType.requiredFieldNames()) &&
                fields.all { (name, value) ->
                    val field = expectedType.field(name as String) ?: return@all false
                    value.conformsToArgumentType(field.inputType)
                }
        }
        else -> conformsToInputSchemaType(typeExpr)
    }
}

internal fun Arguments.instantiateVariables(
    expectedField: ViaductSchema.Field,
    resolverOccurrenceId: ResolverOccurrenceId,
): Arguments {
    if (this == Arguments.Error) return this
    return argumentsOfExpressions(
        fieldExpressions().mapValues { (_, value) ->
            value.instantiateVariables(resolverOccurrenceId)
        },
    ).validatedAgainst(expectedField)
}

private fun ArgumentExpression?.instantiateVariables(resolverOccurrenceId: ResolverOccurrenceId): ArgumentExpression? =
    when (this) {
        is Arguments.Variable ->
            if (isTemplate) {
                instantiate(resolverOccurrenceId)
            } else {
                this
            }
        is List<*> -> map { value -> value.instantiateVariables(resolverOccurrenceId) }
        is Map<*, *> ->
            toStringKeyedArgumentMap()
                .mapValues { (_, value) ->
                    value.instantiateVariables(resolverOccurrenceId)
                }
        else -> this
    }

fun Arguments.substituteTemplates(
    expectedField: ViaductSchema.Field,
    bindings: Map<Arguments.Variable, EngineInputData?>,
): Arguments {
    if (this == Arguments.Error) return this
    require(bindings.keys.all(Arguments.Variable::isTemplate)) {
        "Template substitution requires variable templates"
    }
    val substituted =
        fieldExpressions().mapValues { (name, value) ->
            value.substituteTemplates(
                bindings,
                expectedField.requireArg(name).inputType,
            )
        }
    return argumentsOfExpressions(substituted).validatedAgainst(expectedField)
}

private fun ArgumentExpression?.substituteTemplates(
    bindings: Map<Arguments.Variable, EngineInputData?>,
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
): ArgumentExpression? =
    when (this) {
        is Arguments.Variable ->
            if (isTemplate && this in bindings) {
                coerceArgumentExpression(expectedType, bindings[this])
            } else {
                this
            }
        is List<*> -> {
            val elementType = checkNotNull(expectedType.unwrapList())
            map { value ->
                value.substituteTemplates(bindings, elementType)
            }
        }
        is Map<*, *> -> {
            check(!expectedType.isList)
            val expectedObjectType = expectedType.baseTypeDef
            check(expectedObjectType is ViaductSchema.Input)
            toStringKeyedArgumentMap().mapValues { (name, value) ->
                value.substituteTemplates(
                    bindings,
                    expectedObjectType.requireField(name).inputType,
                )
            }
        }
        else -> this
    }

internal fun Arguments.mapVariableTemplates(
    expectedField: ViaductSchema.Field,
    transform: (Arguments.Variable) -> Arguments.Variable,
): Arguments {
    if (this == Arguments.Error) return this
    val mapped =
        fieldExpressions().mapValues { (_, value) ->
            value.mapVariableTemplates(transform)
        }
    return argumentsOfExpressions(mapped).validatedAgainst(expectedField)
}

private fun ArgumentExpression?.mapVariableTemplates(transform: (Arguments.Variable) -> Arguments.Variable): ArgumentExpression? =
    when (this) {
        is Arguments.Variable ->
            if (isTemplate) {
                transform(this).also { transformed ->
                    require(transformed.isTemplate) {
                        "Variable-template mapping must return a template"
                    }
                }
            } else {
                throw IllegalArgumentException(
                    "Pre-reasoning expressions cannot contain instantiated variables",
                )
            }
        is List<*> -> map { value -> value.mapVariableTemplates(transform) }
        is Map<*, *> ->
            toStringKeyedArgumentMap()
                .mapValues { (_, value) -> value.mapVariableTemplates(transform) }
        else -> this
    }

internal fun Arguments.variables(): Set<Arguments.Variable> =
    if (this == Arguments.Error) {
        emptySet()
    } else {
        fieldExpressions().values.flatMapTo(linkedSetOf()) { value -> value.variables() }
    }

/** Returns the variable instances used anywhere in this argument tuple. */
internal fun Arguments.instantiatedVariables(): Set<Arguments.Variable> = variables().filterTo(linkedSetOf(), Arguments.Variable::isInstantiated)

/** Returns every variable expression used anywhere in this argument tuple. */
fun Arguments.usedVariables(): Set<Arguments.Variable> = variables()

/** Returns the argument names whose values recursively contain at least one variable expression. */
fun Arguments.variableArgumentNames(): Set<String> =
    if (this == Arguments.Error) {
        emptySet()
    } else {
        fieldExpressions()
            .filterValues(ArgumentExpression?::containsVariable)
            .keys
    }

/**
 * Returns a structural argument hash that compares variable occurrences relative to their roots.
 *
 * This is intended for comparing equivalent argument expressions from independently rooted
 * executions. Ordinary argument equality and [Any.hashCode] retain complete root-qualified
 * variable identity.
 */
fun Arguments.rootRelativeHashCode(): Int =
    when (this) {
        Arguments.Error -> Arguments.Error.hashCode()
        is Arguments.Resolved -> hashCode()
        else -> fieldExpressions().rootRelativeHashCode()
    }

/** Returns whether these arguments have equal structure modulo occurrence-root identity. */
internal fun Arguments.hasSameRootRelativeStructureAs(other: Arguments): Boolean =
    when {
        this === Arguments.Error || other === Arguments.Error -> this === other
        this is Arguments.Resolved || other is Arguments.Resolved -> this == other
        else -> fieldExpressions().hasSameRootRelativeStructureAs(other.fieldExpressions())
    }

private fun Map<*, *>.rootRelativeHashCode(): Int =
    entries.sumOf { (key, value) ->
        key.hashCode() xor value.rootRelativeHashCode()
    }

private fun ArgumentExpression?.rootRelativeHashCode(): Int =
    when (this) {
        null -> 0
        is Arguments.Variable -> {
            var hash = target.hashCode()
            hash = 31 * hash + variableName.hashCode()
            hash =
                31 * hash +
                when (val id = instanceId) {
                    null -> 0
                    else -> id.resolverOccurrenceId.rootRelativeHashCode()
                }
            hash
        }
        is List<*> -> fold(1) { hash, value ->
            31 * hash + value.rootRelativeHashCode()
        }
        is Map<*, *> -> rootRelativeHashCode()
        else -> hashCode()
    }

private fun Map<*, *>.hasSameRootRelativeStructureAs(other: Map<*, *>): Boolean =
    keys == other.keys &&
        all { (key, value) -> value.hasSameRootRelativeStructureAs(other[key]) }

private fun ArgumentExpression?.hasSameRootRelativeStructureAs(other: ArgumentExpression?): Boolean =
    when {
        this is Arguments.Variable && other is Arguments.Variable ->
            target == other.target &&
                variableName == other.variableName &&
                when {
                    instanceId == null || other.instanceId == null -> instanceId == other.instanceId
                    else ->
                        instanceId!!.resolverOccurrenceId.hasSameRootRelativeAddressAs(
                            other.instanceId!!.resolverOccurrenceId,
                        )
                }
        this is List<*> && other is List<*> ->
            size == other.size &&
                indices.all { index ->
                    this[index].hasSameRootRelativeStructureAs(other[index])
                }
        this is Map<*, *> && other is Map<*, *> -> hasSameRootRelativeStructureAs(other)
        else -> this == other
    }

private fun ArgumentExpression?.variables(): Set<Arguments.Variable> =
    when (this) {
        is Arguments.Variable -> setOf(this)
        is List<*> -> flatMapTo(linkedSetOf()) { value -> value.variables() }
        is Map<*, *> ->
            values.flatMapTo(linkedSetOf()) { value -> value.variables() }
        else -> emptySet()
    }

private fun ArgumentExpression?.containsVariable(): Boolean =
    when (this) {
        is Arguments.Variable -> true
        is List<*> -> any(ArgumentExpression?::containsVariable)
        is Map<*, *> -> values.any(ArgumentExpression?::containsVariable)
        else -> false
    }

internal fun Arguments.variableTemplates(): Set<Arguments.Variable> = variables().filterTo(linkedSetOf(), Arguments.Variable::isTemplate)

internal fun Arguments.retarget(field: ViaductSchema.Field): Arguments {
    if (this == Arguments.Error) return this
    val retargeted = Arguments.of(field, fieldExpressions())
    return when (this) {
        is Arguments.Template -> Arguments.Template.of(field, retargeted)
        else -> retargeted
    }
}

internal fun ArgumentExpression?.matchingVariableTypes(
    variable: Arguments.Variable,
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    hasDefault: Boolean,
): List<Pair<ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>, Boolean>> {
    require(variable.isTemplate) { "Variable type matching requires a template" }
    val elementType = typeExpr.unwrapList()
    return when {
        this == variable -> listOf(typeExpr to hasDefault)
        this is List<*> && elementType != null ->
            flatMap { value ->
                value.matchingVariableTypes(variable, elementType, false)
            }
        this is Map<*, *> &&
            !typeExpr.isList &&
            typeExpr.baseTypeDef is ViaductSchema.Input -> {
            val expectedType = typeExpr.baseTypeDef as ViaductSchema.Input
            toStringKeyedArgumentMap().flatMap { (name, value) ->
                val field = expectedType.requireField(name)
                value.matchingVariableTypes(
                    variable,
                    field.inputType,
                    field.hasDefault,
                )
            }
        }
        else -> emptyList()
    }
}

/** Returns whether this entire ground argument outcome is erroneous. */
fun Arguments.containsErrorValue(): Boolean = this == Arguments.Error

private fun ArgumentExpression?.containsArgumentError(): Boolean =
    when (this) {
        ArgumentResolutionError -> true
        is List<*> -> any(ArgumentExpression?::containsArgumentError)
        is Map<*, *> -> values.any(ArgumentExpression?::containsArgumentError)
        else -> false
    }

private fun ArgumentExpression?.toGroundInputData(): EngineInputData? =
    when (this) {
        null -> null
        is List<*> -> map { value -> value.toGroundInputData() }
        is Map<*, *> ->
            toStringKeyedArgumentMap()
                .mapValues { (_, value) -> value.toGroundInputData() }
        ArgumentResolutionError, is Arguments.Variable ->
            error("An open argument expression cannot become ground input data")
        else -> this
    }

internal fun Any.toStringKeyedArgumentMap(): Map<String, ArgumentExpression?> {
    val values = this as? Map<*, *> ?: throw ClassCastException()
    return values.entries.associate { (key, value) ->
        if (key !is String) throw ClassCastException()
        key to value
    }
}
