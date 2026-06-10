package viaduct.ksp.validation

import graphql.language.Field
import graphql.language.FragmentDefinition
import graphql.language.FragmentSpread
import graphql.language.InlineFragment
import graphql.language.SelectionSet
import viaduct.graphql.utils.ParsedSelections
import viaduct.graphql.utils.collectVariableReferences
import viaduct.tenant.validation.ErrorMessage
import viaduct.tenant.validation.IValidator

/**
 * Validates @Variable declarations within @Resolver annotations at compile time.
 *
 * Checks that depend on the *expanded* selection set (variable-path resolution, unused/unbound
 * references) are deferred when a fragment spreads a cross-leaf `@GraphQLFragment` that KSP can't
 * see; those are validated at assembly time and by the engine's `FromFieldVariablesHaveValidPaths`
 * at runtime. Schema-free checks (one-source, blank paths, duplicate names, fragment-required)
 * always run here.
 */
internal class ValidateResolverVariables(
    private val annotationSpecs: List<ResolverAnnotationSpec>,
) : IValidator {
    override fun validate(): List<ErrorMessage> {
        val errors = mutableListOf<ErrorMessage>()
        annotationSpecs.forEach { spec ->
            val deferExpansionChecks = hasUnresolvedFragmentSpread(spec)
            validateVariableDeclarations(spec, errors, deferExpansionChecks)
            validateVariableReferences(spec, errors, deferExpansionChecks)
        }
        return errors
    }

    /**
     * True if any of [spec]'s fragments spreads a fragment that is not defined inline in the same
     * annotation text — i.e. a cross-leaf `@GraphQLFragment` spread that KSP cannot resolve. When
     * true, expansion-dependent checks must be deferred to assembly/runtime.
     */
    private fun hasUnresolvedFragmentSpread(spec: ResolverAnnotationSpec): Boolean =
        spec.fragments.any { fragment ->
            val definitions = fragment.fragmentDocument().getDefinitionsOfType(FragmentDefinition::class.java)
            val definedNames = definitions.mapTo(mutableSetOf()) { it.name }
            collectSpreadNames(definitions.mapNotNull { it.selectionSet }).any { it !in definedNames }
        }

    /** Recursively collects every fragment-spread name reachable from [selectionSets]. */
    private fun collectSpreadNames(selectionSets: List<SelectionSet>): Set<String> {
        val names = mutableSetOf<String>()
        val queue = ArrayDeque(selectionSets)
        while (queue.isNotEmpty()) {
            queue.removeFirst().selections.forEach { selection ->
                when (selection) {
                    is FragmentSpread -> names.add(selection.name)
                    is Field -> selection.selectionSet?.let { queue.add(it) }
                    is InlineFragment -> queue.add(selection.selectionSet)
                }
            }
        }
        return names
    }

    /**
     * Checks that each @Variable annotation is well-formed
     */
    private fun validateVariableDeclarations(
        spec: ResolverAnnotationSpec,
        errors: MutableList<ErrorMessage>,
        deferExpansionChecks: Boolean,
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

            validateFromField(v.fromObjectField, spec, v, ResolverFragmentType.OBJECT, errors, deferExpansionChecks)
            validateFromField(v.fromQueryField, spec, v, ResolverFragmentType.QUERY, errors, deferExpansionChecks)
        }
    }

    private fun validateFromField(
        path: String?,
        spec: ResolverAnnotationSpec,
        variable: ResolverVariableSpec,
        fragmentType: ResolverFragmentType,
        errors: MutableList<ErrorMessage>,
        deferExpansionChecks: Boolean,
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
        // Path resolution requires the fully-expanded selection set; defer when a cross-leaf spread
        // is present (the path may be selected inside a fragment KSP cannot see).
        if (deferExpansionChecks) return
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
        errors: MutableList<ErrorMessage>,
        deferExpansionChecks: Boolean,
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

        // Unused/unbound checks count variable references in the selection set, which is incomplete
        // until cross-leaf spreads are expanded at assembly time — defer them in that case.
        if (deferExpansionChecks) return

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
