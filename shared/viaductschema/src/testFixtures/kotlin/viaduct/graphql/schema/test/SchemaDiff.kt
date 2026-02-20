package viaduct.graphql.schema.test

import viaduct.graphql.schema.ViaductSchema
import viaduct.invariants.FailureCollector

class SchemaDiff(
    private val expected: ViaductSchema,
    private val actual: ViaductSchema,
    private val checker: FailureCollector = FailureCollector(),
    private val includeIntrospectiveTypes: Boolean = false
) {
    private var done = false

    fun diff(): FailureCollector {
        if (!done) {
            // Exclude introspective types from the comparison (not all ViaductSchema impls support them)
            visit(
                expected.types.values.filter { !it.name.startsWith("__") },
                actual.types.values.filter { !it.name.startsWith("__") },
                "TYPE"
            )

            visitDirectives(expected.directives.entries, actual.directives.entries)
            done = true
        }
        return checker
    }

    private fun visit(
        expectedDefs: Iterable<ViaductSchema.Def>,
        actualDefs: Iterable<ViaductSchema.Def>,
        kind: String
    ) {
        sameNames(
            expectedDefs,
            actualDefs,
            kind,
            ViaductSchema.Def::name
        ).forEach { visit(it.first, it.second) }
    }

    private fun <T> sameNames(
        expected: Iterable<T>,
        actual: Iterable<T>,
        kind: String,
        namer: (T) -> String
    ): List<Pair<T, T>> {
        val expectedNames = expected.map { namer.invoke(it) }
        val actualNames = actual.map { namer.invoke(it) }
        checker.containsExactlyElementsIn(expectedNames, actualNames, "SAME_${kind}_NAMES")
        val agreedNames = expectedNames.intersect(actualNames)
        return agreedNames.map { name ->
            Pair(expected.find { namer.invoke(it) == name }!!, actual.find { namer.invoke(it) == name }!!)
        }
    }

    private fun visitAppliedDirective(
        expectedDir: ViaductSchema.AppliedDirective<*>,
        actualDir: ViaductSchema.AppliedDirective<*>
    ) {
        sameNames(
            expectedDir.arguments.entries,
            actualDir.arguments.entries,
            "ARG",
            Map.Entry<String, ViaductSchema.Literal>::key
        ).forEach {
            checker.withContext(it.first.key) {
                checker.isTrue(areValuesEqual(it.first.value, it.second.value), "ARG_VALUE_AGREES")
            }
        }
    }

    private fun visitDirectives(
        expectedDirectives: Iterable<Map.Entry<String, ViaductSchema.Directive>>,
        actualDirectives: Iterable<Map.Entry<String, ViaductSchema.Directive>>
    ) {
        sameNames(
            expectedDirectives.map { it.value },
            actualDirectives.map { it.value },
            "DIRECTIVE",
            ViaductSchema.Directive::name
        ).forEach {
            checker.withContext(it.first.name) {
                visit(it.first.args, it.second.args, "DIRECTIVE_ARG")
            }
        }
    }

    private fun visit(
        expectedDef: ViaductSchema.Def,
        actualDef: ViaductSchema.Def
    ) {
        try {
            checker.pushContext(actualDef.name)
            // Checks common for all [ViaductSchema.Def]s
            if (!hasSameKind(expectedDef, actualDef, "DEF_CLASS")) {
                return
            }

            // Check sourceLocation agreement
            checker.isEqualTo(expectedDef.sourceLocation, actualDef.sourceLocation, "SOURCE_LOCATION_AGREES")

            sameNames(
                expectedDef.appliedDirectives,
                actualDef.appliedDirectives,
                "DIRECTIVE",
                ViaductSchema.AppliedDirective<*>::name
            ).forEach {
                checker.withContext(it.first.name) { visitAppliedDirective(it.first, it.second) }
            }

            // Checks specific to each [ViaductSchema.Def] subclass
            if (expectedDef is ViaductSchema.HasDefaultValue) {
                cvt(expectedDef, actualDef) { exp, act ->
                    hasSameKind(exp.containingDef, act.containingDef, "CONTAINING_TYPES_AGREE")
                    checker.isEqualTo(exp.type, act.type, "ARG_TYPE_AGREE")
                    checker.isEqualTo(exp.containingDef.name, act.containingDef.name, "CONTAINING_TYPE_NAMES_AGREE")
                    if (checker.isEqualTo(exp.hasDefault, act.hasDefault, "HAS_DEFAULTS_AGREE") && exp.hasDefault) {
                        checker.isTrue(areValuesEqual(exp.defaultValue, act.defaultValue, exp.type), "DEFAULT_VALUES_AGREE")
                    }
                    if (checker.isEqualTo(exp.hasEffectiveDefault, act.hasEffectiveDefault, "HAS_DEFAULTS_AGREE") &&
                        exp.hasEffectiveDefault
                    ) {
                        checker.isTrue(
                            areValuesEqual(
                                exp.effectiveDefaultValue,
                                act.effectiveDefaultValue,
                                exp.type
                            ),
                            "DEFAULT_VALUES_AGREE"
                        )
                    }
                }
            }
            if (expectedDef is ViaductSchema.TypeDef) {
                cvt(expectedDef, actualDef) { exp, act ->
                    checker.isEqualTo(exp.kind, act.kind, "KIND_AGREES")
                    checker.isEqualTo(exp.isSimple, act.isSimple, "IS_SIMPLE_AGREE")
                    checker.isEqualTo(exp.asTypeExpr(), act.asTypeExpr(), "TYPE_EXPR_AGREE")
                    sameNames(
                        exp.possibleObjectTypes,
                        act.possibleObjectTypes,
                        "POSSIBLE_OBJECT_TYPE",
                        ViaductSchema.Def::name
                    )
                }
            }
            if (expectedDef is ViaductSchema.TypeDef) {
                cvt(expectedDef, actualDef) { exp, act ->
                    fun ViaductSchema.Extension<*, *>.memberKeys() =
                        this.members
                            .map { it.name }
                            .sorted()
                            .joinToString("::")
                    sameNames(exp.extensions, act.extensions, "EXTENSION", ViaductSchema.Extension<*, *>::memberKeys)
                }
            }
            if (expectedDef is ViaductSchema.OutputRecord) {
                cvt(expectedDef, actualDef) { exp, act ->
                    fun ViaductSchema.ExtensionWithSupers<*, *>.supersKeys() =
                        this.supers
                            .map { it.name }
                            .sorted()
                            .joinToString("::")
                    sameNames(exp.extensions, act.extensions, "EXTENSION_SUPERS", ViaductSchema.ExtensionWithSupers<*, *>::supersKeys)
                }
            }
            if (expectedDef is ViaductSchema.Record) {
                cvt(expectedDef, actualDef) { exp, act ->
                    visit(exp.fields, act.fields, "FIELD")
                }
            }
            if (expectedDef is ViaductSchema.OutputRecord) {
                cvt(expectedDef, actualDef) { exp, act ->
                    sameNames(exp.supers, act.supers, "SUPER", ViaductSchema.Def::name)
                }
            }
            if (expectedDef is ViaductSchema.Object) {
                cvt(expectedDef, actualDef) { exp, act ->
                    sameNames(exp.unions, act.unions, "UNION", ViaductSchema.Def::name)
                }
            }
            when (expectedDef) {
                is ViaductSchema.Arg ->
                    cvt(expectedDef, actualDef) { exp, act ->
                        hasSameKind(exp.containingDef, act.containingDef, "ARG_DEF_KIND_AGREE")
                        checker.isEqualTo(exp.containingDef.name, act.containingDef.name, "ARG_DEF_NAMES_AGREE")
                    }
                is ViaductSchema.Enum ->
                    cvt(expectedDef, actualDef) { exp, act ->
                        visit(exp.values, act.values, "ENUM_VALUE")
                    }

                is ViaductSchema.EnumValue ->
                    cvt(expectedDef, actualDef) { exp, act ->
                        checker.isEqualTo(
                            exp.containingDef.name,
                            act.containingDef.name,
                            "ENUM_VALUE_CONTAINERS_AGREE"
                        )
                        sameNames(
                            exp.containingDef.appliedDirectives,
                            act.containingDef.appliedDirectives,
                            "EXTENSION_APPLIED_DIRECTIVE",
                            ViaductSchema.AppliedDirective<*>::name
                        ).forEach {
                            checker.withContext(it.first.name) { visitAppliedDirective(it.first, it.second) }
                        }
                    }

                is ViaductSchema.Field ->
                    cvt(expectedDef, actualDef) { exp, act ->
                        checker.isEqualTo(exp.isOverride, act.isOverride, "OVERRIDE_KIND_AGREE")
                        checker.isEqualTo(exp.hasArgs, act.hasArgs, "FIELD_HAS_ARGS_AGREE")
                        sameNames(
                            exp.containingDef.appliedDirectives,
                            act.containingDef.appliedDirectives,
                            "EXTENSION_APPLIED_DIRECTIVE",
                            ViaductSchema.AppliedDirective<*>::name
                        ).forEach {
                            checker.withContext(it.first.name) { visitAppliedDirective(it.first, it.second) }
                        }

                        visit(exp.args, act.args, "ARG")
                    }

                is ViaductSchema.Object -> { }
                is ViaductSchema.Input -> { }
                is ViaductSchema.Interface -> { }
                is ViaductSchema.Scalar -> { }
                is ViaductSchema.Union -> { }
                else -> throw IllegalStateException("Unknown type: $expectedDef")
            }
        } finally {
            checker.popContext()
        }
    }

    fun areValuesEqual(
        expectedValue: ViaductSchema.Literal?,
        actualValue: ViaductSchema.Literal?,
        type: ViaductSchema.TypeExpr<*>? = null
    ): Boolean {
        // Handle null cases
        if (expectedValue == null && actualValue == null) return true
        if (expectedValue == null || actualValue == null) return false

        // For lists, recursively compare elements
        if (type != null && expectedValue is ViaductSchema.ListLiteral && actualValue is ViaductSchema.ListLiteral) {
            if (expectedValue.size != actualValue.size) return false
            val elementType = type.unwrapList()
            return expectedValue.zip(actualValue).all { (exp, act) ->
                areValuesEqual(exp, act, elementType)
            }
        }

        // For integral scalar types (Byte, Short, Long), compare values semantically
        // since they can be represented as either IntValue or StringValue in GraphQL literals
        if (type != null && !type.isList) {
            val baseType = type.baseTypeDef
            if (baseType is ViaductSchema.Scalar && baseType.name in setOf("Byte", "Short", "Long")) {
                val expectedIntegral = extractIntegralValue(expectedValue)
                val actualIntegral = extractIntegralValue(actualValue)
                return expectedIntegral == actualIntegral
            }
        }

        // Default comparison using value equality
        return expectedValue == actualValue
    }

    private fun extractIntegralValue(value: ViaductSchema.Literal?): Any? =
        when (value) {
            is ViaductSchema.IntLiteral -> {
                try {
                    value.value.toLong()
                } catch (e: ArithmeticException) {
                    throw IllegalArgumentException("Integral value out of Long range: ${value.value}", e)
                }
            }
            is ViaductSchema.StringLiteral -> value.value.toLongOrNull()
            // NullValue instances are semantically equal regardless of identity.
            // Return a sentinel object so that all NullValues compare equal with ==.
            is ViaductSchema.NullLiteral -> ViaductSchema.NullLiteral::class
            else -> value
        }

    private fun hasSameKind(
        expectedDef: ViaductSchema.Def,
        actualDef: ViaductSchema.Def,
        msg: String
    ): Boolean =
        when (actualDef) {
            is ViaductSchema.Directive -> {
                checker.isInstanceOf<ViaductSchema.Directive>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.DirectiveArg -> {
                checker.isInstanceOf<ViaductSchema.DirectiveArg>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.FieldArg -> {
                checker.isInstanceOf<ViaductSchema.FieldArg>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.Enum -> {
                checker.isInstanceOf<ViaductSchema.Enum>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.EnumValue -> {
                checker.isInstanceOf<ViaductSchema.EnumValue>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.Field -> {
                checker.isInstanceOf<ViaductSchema.Field>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.Input -> {
                checker.isInstanceOf<ViaductSchema.Input>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.Interface -> {
                checker.isInstanceOf<ViaductSchema.Interface>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.Object -> {
                checker.isInstanceOf<ViaductSchema.Object>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.Scalar -> {
                checker.isInstanceOf<ViaductSchema.Scalar>(expectedDef, "${msg}_AGREE")
            }
            is ViaductSchema.Union -> {
                checker.isInstanceOf<ViaductSchema.Union>(expectedDef, "${msg}_AGREE")
            }
            else -> throw IllegalArgumentException("Unexpected class $actualDef")
        }

    companion object {
        private inline fun <reified T, R> cvt(
            exp: T,
            act: Any?,
            body: (T, T) -> R
        ): R = body.invoke(exp, act as T)
    }
}
