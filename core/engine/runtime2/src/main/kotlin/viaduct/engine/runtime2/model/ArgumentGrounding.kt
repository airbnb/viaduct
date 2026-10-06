package viaduct.engine.runtime2.model

import viaduct.graphql.schema.ViaductSchema

/** Grounds this argument tuple using [bindingFor] for each variable instance. */
fun Arguments.groundWithBindings(
    expectedField: ViaductSchema.Field,
    bindingFor: (Arguments.Variable) -> VariableBinding,
): Arguments.Ground {
    if (this == Arguments.Error) return Arguments.Error
    return groundedArguments(expectedField) { value, typeExpr ->
        value.groundWithBindings(typeExpr, bindingFor)
    }
}

/** Suspends while [bindingFor] supplies each variable instance, then grounds this argument tuple. */
suspend fun Arguments.fetchGroundWithBindings(
    expectedField: ViaductSchema.Field,
    bindingFor: suspend (Arguments.Variable) -> VariableBinding,
): Arguments.Ground {
    if (this == Arguments.Error) return Arguments.Error
    return groundedArguments(expectedField) { value, typeExpr ->
        value.fetchGroundWithBindings(typeExpr, bindingFor)
    }
}

private inline fun Arguments.groundedArguments(
    expectedField: ViaductSchema.Field,
    ground: (ArgumentExpression?, ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>) -> VariableBinding,
): Arguments.Ground {
    val fields = linkedMapOf<String, EngineInputData?>()
    fieldExpressions().forEach { (name, value) ->
        val typeExpr = expectedField.requireArg(name).inputType
        when (val binding = ground(value, typeExpr)) {
            VariableBinding.Error -> return Arguments.Error
            is VariableBinding.Input -> fields[name] = binding.value
        }
    }
    return argumentsOfGround(fields)
}

private fun ArgumentExpression?.groundWithBindings(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    bindingFor: (Arguments.Variable) -> VariableBinding,
): VariableBinding {
    if (this == null) return VariableBinding.of(null)
    if (this == ArgumentResolutionError) return VariableBinding.Error
    if (this is Arguments.Variable) {
        check(isInstantiated) {
            "Variable template $this must be instantiated before it can be grounded"
        }
        return bindingFor(this).coerceTo(expectedType)
    }

    val elementType = expectedType.unwrapList()
    if (elementType != null) {
        val grounded = mutableListOf<EngineInputData?>()
        for (value in this as List<*>) {
            when (val binding = value.groundWithBindings(elementType, bindingFor)) {
                VariableBinding.Error -> return VariableBinding.Error
                is VariableBinding.Input -> grounded += binding.value
            }
        }
        return VariableBinding.of(grounded.toList())
    }
    return when (val type = expectedType.baseTypeDef) {
        is ViaductSchema.Input -> {
            val grounded = linkedMapOf<String, EngineInputData?>()
            toStringKeyedArgumentMap().forEach { (name, value) ->
                val fieldType = type.requireField(name).inputType
                when (val binding = value.groundWithBindings(fieldType, bindingFor)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded[name] = binding.value
                }
            }
            VariableBinding.of(grounded.toMap())
        }
        else -> VariableBinding.of(toEngineInputData(expectedType, this))
    }
}

private suspend fun ArgumentExpression?.fetchGroundWithBindings(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    bindingFor: suspend (Arguments.Variable) -> VariableBinding,
): VariableBinding {
    if (this == null) return VariableBinding.of(null)
    if (this == ArgumentResolutionError) return VariableBinding.Error
    if (this is Arguments.Variable) {
        check(isInstantiated) {
            "Variable template $this must be instantiated before it can be grounded"
        }
        return bindingFor(this).coerceTo(expectedType)
    }

    val elementType = expectedType.unwrapList()
    if (elementType != null) {
        val grounded = mutableListOf<EngineInputData?>()
        for (value in this as List<*>) {
            when (val binding = value.fetchGroundWithBindings(elementType, bindingFor)) {
                VariableBinding.Error -> return VariableBinding.Error
                is VariableBinding.Input -> grounded += binding.value
            }
        }
        return VariableBinding.of(grounded.toList())
    }
    return when (val type = expectedType.baseTypeDef) {
        is ViaductSchema.Input -> {
            val grounded = linkedMapOf<String, EngineInputData?>()
            for ((name, value) in toStringKeyedArgumentMap()) {
                val fieldType = type.requireField(name).inputType
                when (val binding = value.fetchGroundWithBindings(fieldType, bindingFor)) {
                    VariableBinding.Error -> return VariableBinding.Error
                    is VariableBinding.Input -> grounded[name] = binding.value
                }
            }
            VariableBinding.of(grounded.toMap())
        }
        else -> VariableBinding.of(toEngineInputData(expectedType, this))
    }
}

private fun VariableBinding.coerceTo(expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>): VariableBinding =
    when (this) {
        VariableBinding.Error -> this
        is VariableBinding.Input ->
            VariableBinding.of(coerceVariableInput(expectedType, value))
    }

private fun coerceVariableInput(
    expectedType: ViaductSchema.TypeExpr<ViaductSchema.InputTypeDef>,
    value: EngineInputData?,
): EngineInputData? {
    if (value == null) return toEngineInputData(expectedType, null)

    val elementType = expectedType.unwrapList()
        ?: return toEngineInputData(expectedType, value)
    return if (value is List<*>) {
        value.map { element -> coerceVariableInput(elementType, element) }
    } else {
        listOf(coerceVariableInput(elementType, value))
    }
}
