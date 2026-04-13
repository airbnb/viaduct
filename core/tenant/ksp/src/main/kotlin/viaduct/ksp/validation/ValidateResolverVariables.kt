package viaduct.ksp.validation

import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.collectVariableReferences
import viaduct.tenant.validation.ErrorMessage
import viaduct.tenant.validation.IValidator

/**
 * Validates @Variable declarations within @Resolver annotations at compile time
 */
internal class ValidateResolverVariables(
    private val annotationSpecs: List<ResolverAnnotationSpec>,
) : IValidator {
    override fun validate(): List<ErrorMessage> {
        val errors = mutableListOf<ErrorMessage>()
        annotationSpecs.forEach { spec ->
            validateVariableDeclarations(spec, errors)
            validateVariableReferences(spec, errors)
        }
        return errors
    }

    /**
     * Checks that each @Variable annotation is well-formed
     */
    private fun validateVariableDeclarations(
        spec: ResolverAnnotationSpec,
        errors: MutableList<ErrorMessage>
    ) {
        spec.variables.forEach { v ->
            // Each variable sets exactly one source
            val setCount = listOfNotNull(v.fromObjectField, v.fromQueryField, v.fromArgument).size
            if (setCount != 1) {
                errors.add("${spec.metadata.fullClassName}: Variable '${v.name}' must set exactly one of fromObjectField, fromQueryField, or fromArgument (found $setCount set)")
            }

            if (v.fromArgument?.isBlank() == true) {
                errors.add("${spec.metadata.fullClassName}: Variable '${v.name}' has blank fromArgument")
            }

            validateFromField(v.fromObjectField, spec, v, ResolverFragmentType.OBJECT, errors)
            validateFromField(v.fromQueryField, spec, v, ResolverFragmentType.QUERY, errors)
        }
    }

    private fun validateFromField(
        path: String?,
        spec: ResolverAnnotationSpec,
        variable: ResolverVariableSpec,
        fragmentType: ResolverFragmentType,
        errors: MutableList<ErrorMessage>,
    ) {
        if (path == null) return
        val prefix = "${spec.metadata.fullClassName}: Variable '${variable.name}'"
        val fieldName = "from${fragmentType.name.lowercase().replaceFirstChar { it.uppercase() }}Field"

        if (path.isBlank()) {
            errors.add("$prefix has blank $fieldName")
            return
        }

        val matchingFragment = spec.fragments.firstOrNull { it.metadata.fragmentType == fragmentType }
        if (matchingFragment == null) {
            errors.add("$prefix uses $fieldName but no ${fragmentType.name.lowercase()}ValueFragment is set")
            return
        }
        val parsed = ParsedSelections.fromDocument(matchingFragment.metadata.typeName, matchingFragment.fragmentDocument())
        if (parsed.filterToPath(path.split(".")) == null) {
            errors.add("$prefix path '$path' not found in ${fragmentType.name.lowercase()} fragment selections")
        }
    }

    /**
     * Checks that declared variables are used and that fragment variable references are declared
     */
    private fun validateVariableReferences(
        spec: ResolverAnnotationSpec,
        errors: MutableList<ErrorMessage>
    ) {
        val annotationVarNames = spec.variables.map { it.name }
        val allVarNames = annotationVarNames + spec.variablesProviderVarNames

        // No duplicate variable names across @Variable annotations and @Variables provider
        val dupes = allVarNames.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        if (dupes.isNotEmpty()) {
            errors.add("${spec.metadata.fullClassName}: Duplicate variable names: ${dupes.joinToString(", ")}")
        }

        if (spec.fragments.isEmpty()) {
            // Variables require at least one fragment
            if (allVarNames.isNotEmpty()) {
                errors.add("${spec.metadata.fullClassName}: @Resolver has variables but neither objectValueFragment nor queryValueFragment is set")
            }
            return
        }

        val referencedVars = buildSet {
            spec.fragments.forEach { fragment ->
                addAll(fragment.fragmentDocument().collectVariableReferences())
            }
        }

        val allProducerNames = allVarNames.toSet()

        // Unused variables
        val unusedVars = allProducerNames - referencedVars
        if (unusedVars.isNotEmpty()) {
            errors.add("${spec.metadata.fullClassName}: Declared variables not referenced in objectValueFragment or queryValueFragment: ${unusedVars.joinToString(", ")}")
        }

        // Unbound variable references
        val unboundVars = referencedVars - allProducerNames
        if (unboundVars.isNotEmpty()) {
            errors.add("${spec.metadata.fullClassName}: Fragment references undeclared variables: ${unboundVars.joinToString(", ") { "\$$it" }}")
        }
    }
}
