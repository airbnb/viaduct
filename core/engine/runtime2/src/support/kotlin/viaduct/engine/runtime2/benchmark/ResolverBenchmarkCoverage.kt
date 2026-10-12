package viaduct.engine.runtime2.benchmark

import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.correctresolution.CorrectnessCheckerObserver
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.sameMaterializedValueAs
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.PathComponent
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.outputValue
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.resolution.framework.CheckerInvocationObservation
import viaduct.engine.runtime2.resolution.framework.CheckerKind
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.graphql.schema.ViaductSchema

internal enum class ResolverBenchmarkFeature {
    SELECTIVE_DEMAND,
    NONSELECTIVE_CALLBACK,
    SYMBOLIC_LIST_ARGUMENT,
    SHARED_QUERY_ROOT,
    OBJECT_INPUT,
    QUERY_INPUT,
    FROM_ARGUMENT,
    FROM_OBJECT_FIELD,
    FROM_QUERY_FIELD,
    FROM_PROVIDER,
    NESTED_ARGUMENT,
    NULL_ARGUMENT_TRAVERSAL,
    NESTED_VARIABLE_EXPRESSION,
    INPUT_OBJECT,
    INPUT_LIST,
    SINGLETON_COERCION,
    ABSTRACT_PROVIDER,
    PASSIVE_DESCENDANT_VARIABLE,
    OWNER_DEPENDENCY_CHAIN,
    SYMBOLIC_CONVERGENCE,
    PARENT_SOURCE_OVERRIDE,
    PARENT_VARIABLE_MIX,
    PARENT,
    GREAT_GRANDPARENT,
    DIAGONAL_PARENT,
    SOMETIMES_PASSIVE,
    CONDITION_INCLUDED,
    CONDITION_EXCLUDED,
    NULL_PROVIDER_INTERMEDIATE,
    ERROR_PROVIDER_INTERMEDIATE,
    ARGUMENT_ERROR,
    NODE_REFERENCE,
    GENERAL_REFERENCE,
    REFERENCE_NAMESPACE,
    REFERENCE_TAIL,
    REFERENCE_LIST,
    REFERENCE_ENUM,
    REFERENCE_CONCRETE,
    REFERENCE_UNION,
    REFERENCE_TARGET_ARGUMENT_VARIABLE,
    REFERENCE_TARGET_QUERY_VARIABLE,
    REFERENCE_ABSTRACT,
    REFERENCE_OVERRIDE,
    REFERENCE_FALLBACK,
    FIELD_CHECK_SUCCESS,
    FIELD_CHECK_DENIAL,
    TYPE_CHECK_SUCCESS,
    TYPE_CHECK_DENIAL,
    CHECKER_PARENT_DEMAND,
    CHECKER_ARGUMENT_ERROR,
    CHECKER_PROVIDER_BOTH_INPUTS,
    CHECKER_ARGUMENT_VARIABLE,
    CHECKER_NESTED_PATH,
    CHECKER_RAW_RESOLVER_INPUT,
    CHECKER_COMPOSITE,
    CHECKER_DUPLICATE_INPUT,
    CHECKER_LIST_OCCURRENCE,
    CHECKER_SYMBOLIC_KEY,
    CHECKER_PASSIVE_FIELD,
    CHECKER_QUERY_OCCURRENCE,
    CHECKER_OBJECT_VARIABLE,
    CHECKER_QUERY_VARIABLE,
    CHECKER_PROVIDER_VARIABLE,
    NAMED_EMPTY_CHECKER_INPUT,
}

internal class ResolverBenchmarkCoverage {
    private val activatedQueries = ResolverBenchmarkFeature.entries.associateWith { linkedSetOf<Int>() }

    fun record(
        queryIndex: Int,
        operation: SharedOperationContext<*>,
        result: ObjectEngineResult,
        checkerApplications: List<CheckerInvocationObservation>
    ) {
        val observer = operation.resolverObserver as CorrectnessResolverObserver
        val checkerObserver = operation.checkerObserver as CorrectnessCheckerObserver
        val calls = observer.allResolverInvocations()
        val hits = linkedSetOf<ResolverBenchmarkFeature>()

        fun hit(
            feature: ResolverBenchmarkFeature,
            condition: Boolean = true
        ) {
            if (condition) hits += feature
        }

        fun parentDepth(data: EngineObjectData.Sync?): Int {
            if (data == null || "parent" !in data.getSelections()) return 0
            return 1 + parentDepth(data.outputValue("parent") as? EngineObjectData.Sync)
        }
        hit(ResolverBenchmarkFeature.SHARED_QUERY_ROOT, observer.allQueryFragmentResults().values.flatten().groupingBy { it }.eachCount().values.any { it > 1 })
        calls.forEach { call ->
            hit(ResolverBenchmarkFeature.SELECTIVE_DEMAND, call.suppliedDemand != null)
            hit(ResolverBenchmarkFeature.NONSELECTIVE_CALLBACK, call.field.name.startsWith("coverage") || call.field.containingDef.name.startsWith("Coverage"))
            hit(
                ResolverBenchmarkFeature.SYMBOLIC_LIST_ARGUMENT,
                call.occurrencePath.any {
                    it is ListEngineResult.Index
                } && call.occurrencePath.any { it is ObjectEngineResult.ObjectKey && it !is ObjectEngineResult.GroundKey }
            )
            hit(ResolverBenchmarkFeature.OBJECT_INPUT, call.input.getSelections().any())
            hit(ResolverBenchmarkFeature.QUERY_INPUT, call.queryValue.getSelections().any())
            val resolver = operation.world.resolverRegistry.resolver(call.field)
            resolver.instantiatedVariableDefinitions(call.resolverOccurrenceId).forEach variableLoop@{ variable ->
                if (!operation.variableBindings.isBound(requireNotNull(variable.variable.instanceId))) return@variableLoop
                when (val definition = variable.definition) {
                    is VariableDefinition.FromArgument -> {
                        hit(ResolverBenchmarkFeature.FROM_ARGUMENT)
                        hit(ResolverBenchmarkFeature.NESTED_ARGUMENT, definition.inputPath.isNotEmpty())
                        hit(ResolverBenchmarkFeature.NULL_ARGUMENT_TRAVERSAL, definition.inputPath.isNotEmpty() && definition.read(call.arguments) == null)
                    }
                    is VariableDefinition.FromField -> {
                        hit(if (definition.providerFragment == ProviderFragment.OBJECT) ResolverBenchmarkFeature.FROM_OBJECT_FIELD else ResolverBenchmarkFeature.FROM_QUERY_FIELD)
                        hit(
                            ResolverBenchmarkFeature.ABSTRACT_PROVIDER,
                            definition.path.any { it.field.type.baseTypeDef is ViaductSchema.Interface || it.field.type.baseTypeDef is ViaductSchema.Union }
                        )
                    }
                    VariableDefinition.FromProvider -> hit(ResolverBenchmarkFeature.FROM_PROVIDER)
                }
            }
            hit(
                ResolverBenchmarkFeature.NESTED_VARIABLE_EXPRESSION,
                call.field.name == "coverageInputEcho" && (call.arguments.fieldValues["input"] as? Map<*, *>)?.get("child").let {
                    it is Map<*, *> && it["value"] == 7 && it["values"] == listOf(7, 7)
                }
            )
            hit(ResolverBenchmarkFeature.INPUT_OBJECT, call.arguments.fieldValues.values.any { it is Map<*, *> })
            hit(ResolverBenchmarkFeature.INPUT_LIST, call.arguments.fieldValues.values.any { it is List<*> })
            hit(ResolverBenchmarkFeature.SINGLETON_COERCION, call.field.name == "coverageList" && call.arguments.fieldValues["values"] == listOf(7))
            hit(
                ResolverBenchmarkFeature.PASSIVE_DESCENDANT_VARIABLE,
                call.field.containingDef.name == "CoverageValue" && call.field.name == "computed" && call.occurrencePath.dropLast(1).any {
                    it is ObjectEngineResult.ObjectKey && it.field.name == "passive"
                }
            )
            hit(
                ResolverBenchmarkFeature.PARENT_SOURCE_OVERRIDE,
                calls.any { it.field.name == "coverageParent" } && call.field.containingDef.name == "CoverageBranch" && "parent" in call.input.getSelections() && call.input.outputValue("parent").let {
                    it is EngineObjectData.Sync && it.outputValue("seed") == 7
                }
            )
            hit(
                ResolverBenchmarkFeature.PARENT_VARIABLE_MIX,
                call.field.containingDef.name == "CoverageBranch" && call.field.name == "bridge" && parentDepth(call.input) > 0 && resolver.variables.values.let {
                        definitions ->
                    definitions.any {
                        it is VariableDefinition.FromArgument
                    } && definitions.any {
                        it is VariableDefinition.FromField && it.providerFragment == ProviderFragment.OBJECT
                    } && definitions.any { it is VariableDefinition.FromField && it.providerFragment == ProviderFragment.QUERY } && VariableDefinition.FromProvider in definitions
                }
            )
            hit(ResolverBenchmarkFeature.PARENT, parentDepth(call.input) > 0)
            hit(ResolverBenchmarkFeature.GREAT_GRANDPARENT, parentDepth(call.input) >= 3)
            hit(
                ResolverBenchmarkFeature.DIAGONAL_PARENT,
                call.field.containingDef.name == "CoverageBranch" && call.field.name == "bridge" && parentDepth(call.input) > 0 && calls.any {
                    it.field.containingDef.name == "CoverageLeaf" && it.field.name == "result" && parentDepth(it.input) >= 3
                }
            )
            if (call.field.name == "coverageVariables") {
                val queryFields = call.queryValue.getSelections().toSet()
                hit(ResolverBenchmarkFeature.CONDITION_INCLUDED, "included" in queryFields && "skipped" in queryFields)
                hit(ResolverBenchmarkFeature.CONDITION_EXCLUDED, call.arguments.fieldValues["enabled"] == false && "included" !in queryFields && "skipped" !in queryFields)
            }
            hit(
                ResolverBenchmarkFeature.NULL_PROVIDER_INTERMEDIATE,
                call.field.name == "coverageNullVariables" && "coverageNull" in call.input.getSelections() && call.input.outputValue("coverageNull") == null
            )
            hit(
                ResolverBenchmarkFeature.ERROR_PROVIDER_INTERMEDIATE,
                call.field.name == "coverageErrorVariables" && "coverageError" in call.input.getSelections() && call.input.outputValue("coverageError") is EngineErrorData
            )
        }
        val variableOwners = calls.associate { it.resolverOccurrenceId to it.variableResolverOccurrenceIds }

        fun ownerDepth(id: ResolverOccurrenceId): Int = variableOwners[id].orEmpty().maxOfOrNull { 1 + ownerDepth(it) } ?: 0
        hit(
            ResolverBenchmarkFeature.OWNER_DEPENDENCY_CHAIN,
            calls.any { it.field.name == "coverageChain0" && ownerDepth(it.resolverOccurrenceId) >= 4 } && (1..4).all {
                    depth ->
                calls.any {
                    it.field.name == "coverageChain$depth" && it.arguments.fieldValues["value"] == 7 && operation.world.resolverRegistry.resolver(
                        it.field
                    ).variables.values.any { it is VariableDefinition.FromField }
                }
            } && calls.any { it.field.name == "coverageChainEcho4" && it.arguments.fieldValues["value"] == 7 }
        )
        val references = observer.rootFieldReferenceInvocations()
        val roots = (
            observer.allQueryOERs().keys + observer.allQueryFragmentResults().values.flatten() + checkerObserver.allQueryFragmentResults().values.flatten() + result + references.map {
                it.invocationRoot
            }
        ).toSet()
        val convergent = calls.filter { it.field.name == "coverageEcho" && it.arguments.fieldValues["value"] == 7 }
            .groupBy { call -> roots.single { call.resolverOccurrenceId == ResolverOccurrenceId.at(it, call.occurrencePath) } to call.occurrencePath.dropLast(1) }
            .values.any { group ->
                val keys = group.map { it.occurrencePath.last() }.filterIsInstance<ObjectEngineResult.ObjectKey>()
                keys.any { it is ObjectEngineResult.GroundKey } && keys.any { it !is ObjectEngineResult.GroundKey }
            }
        hit(ResolverBenchmarkFeature.SYMBOLIC_CONVERGENCE, convergent)
        hit(
            ResolverBenchmarkFeature.ARGUMENT_ERROR,
            calls.any {
                it.field.name == "coverageErrorVariables" && "coverageEcho" in it.input.getSelections() && it.input.outputValue("coverageEcho") is EngineErrorData
            }
        )
        hit(
            ResolverBenchmarkFeature.REFERENCE_FALLBACK,
            calls.any {
                it.field.name == "coverageFallbackReferences"
            } && calls.any { it.field.containingDef.name == "CoverageReferences" && it.field.name == "object" }
        )
        references.forEach { reference ->
            hit(if (reference.reference.targetField.name == "node") ResolverBenchmarkFeature.NODE_REFERENCE else ResolverBenchmarkFeature.GENERAL_REFERENCE)
            hit(ResolverBenchmarkFeature.REFERENCE_NAMESPACE, reference.reference.path.size >= 4)
            hit(
                ResolverBenchmarkFeature.REFERENCE_TAIL,
                reference.reference.targetField.name == "coverageTail" && references.filter {
                    it.publicationRoot === reference.publicationRoot && it.publicationPath == reference.publicationPath
                }.map { it.reference.targetField.name }.containsAll(listOf("coverageScalar", "coverageTail", "coverageNumber"))
            )
            hit(ResolverBenchmarkFeature.REFERENCE_LIST, reference.publicationPath.any { it is ListEngineResult.Index })
            hit(ResolverBenchmarkFeature.REFERENCE_CONCRETE, reference.reference.type is ViaductSchema.Object)
            hit(ResolverBenchmarkFeature.REFERENCE_UNION, reference.reference.type is ViaductSchema.Union)
            val targetCalls = calls.filter { it.resolverOccurrenceId == ResolverOccurrenceId.at(reference.invocationRoot, reference.invocationPath) }
            hit(
                ResolverBenchmarkFeature.REFERENCE_TARGET_ARGUMENT_VARIABLE,
                targetCalls.any { operation.world.resolverRegistry.resolver(it.field).variables.values.any { it is VariableDefinition.FromArgument } }
            )
            hit(
                ResolverBenchmarkFeature.REFERENCE_TARGET_QUERY_VARIABLE,
                targetCalls.any {
                    operation.world.resolverRegistry.resolver(it.field).variables.values.any { it is VariableDefinition.FromField && it.providerFragment == ProviderFragment.QUERY }
                }
            )
            hit(ResolverBenchmarkFeature.REFERENCE_ENUM, reference.reference.type is ViaductSchema.Enum)
            hit(ResolverBenchmarkFeature.REFERENCE_ABSTRACT, reference.reference.type is ViaductSchema.Interface || reference.reference.type is ViaductSchema.Union)
            hit(
                ResolverBenchmarkFeature.REFERENCE_OVERRIDE,
                reference.publicationPath.lastOrNull().let {
                    it is ObjectEngineResult.ObjectKey && it.field.containingDef.name == "CoverageReferences" && it.field.name == "object"
                } && ResolverOccurrenceId.at(reference.publicationRoot, reference.publicationPath) !in observer.invokedResolverOccurrences()
            )
        }
        val supplied = calls.singleOrNull { it.field.name == "coverageSupplied" }
        if (supplied != null) {
            val root = result.keys.singleOrNull { it.field.name == "coverageSupplied" }?.let { result.getCell(it).value.get() } as? ObjectEngineResult
            val branch = root?.keys?.singleOrNull { it.field.name == "branch" }?.let { root.getCell(it).value.get() }
            hit(ResolverBenchmarkFeature.SOMETIMES_PASSIVE, branch is ObjectEngineResult && calls.none { it.field.containingDef.name == "CoverageRoot" && it.field.name == "branch" })
        }
        checkerApplications.forEach { application ->
            val ownerId = ResolverOccurrenceId.at(application.logicalQueryRoot, application.occurrencePath)
            val inputs = checkerObserver.checkerInputs(application.checkedTarget, ownerId).single()
            val definition = if (application.checkerKind == CheckerKind.FIELD) {
                operation.world.resolverRegistry.fieldChecker(application.checkedCoordinate)!!
            } else {
                operation.world.resolverRegistry.typeChecker(application.checkedType)!!
            }
            val owner = application.logicalQueryRoot.resultAt(application.occurrencePath.dropLast(if (application.checkerKind == CheckerKind.FIELD) 1 else 0)) as ObjectEngineResult
            val outcome = if (application.checkerKind == CheckerKind.FIELD) {
                owner.getCell(
                    application.occurrencePath.last() as ObjectEngineResult.ObjectKey
                ).fieldCheckerResult.get()
            } else {
                owner.typeCheckerResult.get()
            }
            hit(
                when {
                    application.checkerKind == CheckerKind.FIELD && outcome is CheckerResult.Error -> ResolverBenchmarkFeature.FIELD_CHECK_DENIAL
                    application.checkerKind == CheckerKind.FIELD -> ResolverBenchmarkFeature.FIELD_CHECK_SUCCESS
                    outcome is CheckerResult.Error -> ResolverBenchmarkFeature.TYPE_CHECK_DENIAL
                    else -> ResolverBenchmarkFeature.TYPE_CHECK_SUCCESS
                }
            )
            hit(ResolverBenchmarkFeature.CHECKER_LIST_OCCURRENCE, application.occurrencePath.any { it is ListEngineResult.Index })
            hit(ResolverBenchmarkFeature.CHECKER_SYMBOLIC_KEY, application.occurrencePath.lastOrNull().let { it is ObjectEngineResult.ObjectKey && it !is ObjectEngineResult.GroundKey })
            hit(ResolverBenchmarkFeature.CHECKER_PASSIVE_FIELD, application.checkerKind == CheckerKind.FIELD && application.checkedCoordinate !in operation.world.resolverRegistry)
            hit(ResolverBenchmarkFeature.CHECKER_QUERY_OCCURRENCE, application.logicalQueryRoot !== result)
            val populated = inputs.values.filter { it.objectValue.getSelections().any() || it.queryValue.getSelections().any() }
            hit(
                ResolverBenchmarkFeature.CHECKER_DUPLICATE_INPUT,
                populated.withIndex().any {
                        (index, input) ->
                    populated.drop(index + 1).any {
                            other ->
                        input.objectValue.sameMaterializedValueAs(other.objectValue) && input.queryValue.sameMaterializedValueAs(other.queryValue)
                    }
                }
            )
            hit(ResolverBenchmarkFeature.CHECKER_PARENT_DEMAND, inputs.values.any { parentDepth(it.objectValue) > 0 })
            hit(ResolverBenchmarkFeature.CHECKER_ARGUMENT_ERROR, inputs.values.any { it.objectValue.getSelections().contains("other") && it.objectValue.outputValue("other") is EngineErrorData })
            hit(
                ResolverBenchmarkFeature.CHECKER_PROVIDER_BOTH_INPUTS,
                application.checkerKind == CheckerKind.TYPE && application.checkedType.name == "CoverageChecks" && inputs.values.any {
                    "other" in it.objectValue.getSelections() && "coverageNumber" in it.queryValue.getSelections()
                }
            )
            hit(ResolverBenchmarkFeature.CHECKER_COMPOSITE, inputs.size > 1)
            hit(
                ResolverBenchmarkFeature.CHECKER_RAW_RESOLVER_INPUT,
                inputs.values.any {
                    it.objectValue.getSelections().any {
                            name ->
                        (if (application.checkerKind == CheckerKind.FIELD) application.checkedCoordinate.containingDef else application.checkedType).field(
                            name
                        )?.let { it in operation.world.resolverRegistry } == true
                    }
                }
            )
            definition.fragmentTemplates.forEach { (name, templates) ->
                val input = inputs.getValue(name)
                hit(ResolverBenchmarkFeature.NAMED_EMPTY_CHECKER_INPUT, !input.objectValue.getSelections().any() && !input.queryValue.getSelections().any())
                templates.variables.values.forEach { variable ->
                    when (variable) {
                        is VariableDefinition.FromField -> {
                            hit(ResolverBenchmarkFeature.CHECKER_NESTED_PATH, variable.path.size > 1)
                            hit(
                                if (variable.providerFragment == ProviderFragment.OBJECT) ResolverBenchmarkFeature.CHECKER_OBJECT_VARIABLE else ResolverBenchmarkFeature.CHECKER_QUERY_VARIABLE,
                                input.queryValue.getSelections().any() || input.objectValue.getSelections().any()
                            )
                        }
                        VariableDefinition.FromProvider -> hit(ResolverBenchmarkFeature.CHECKER_PROVIDER_VARIABLE, input.queryValue.getSelections().any() || input.objectValue.getSelections().any())
                        is VariableDefinition.FromArgument -> hit(ResolverBenchmarkFeature.CHECKER_ARGUMENT_VARIABLE)
                    }
                }
            }
        }
        hits.forEach { activatedQueries.getValue(it).add(queryIndex) }
    }

    fun assertComplete(minimumQueries: Int = 3) {
        val missing = activatedQueries.filterValues { it.size < minimumQueries }.mapValues { it.value.size }
        check(missing.isEmpty()) { "Overhead corpus missed activated feature coverage (minimum $minimumQueries distinct queries): $missing" }
    }

    fun report(): String =
        buildString {
            appendLine("Resolver benchmark activated feature coverage (distinct queries):")
            activatedQueries.forEach { (feature, queries) -> appendLine("  $feature: ${queries.size}") }
        }
}

private fun ObjectEngineResult.resultAt(path: List<PathComponent>): EngineResult? =
    path.fold<PathComponent, EngineResult?>(this) { value, component ->
        when (component) {
            is ObjectEngineResult.ObjectKey -> (value as ObjectEngineResult).getCell(component).value.get()
            is ListEngineResult.Index -> (value as ListEngineResult)[component.index].value.get()
        }
    }
