@file:Suppress("ForbiddenImport", "MatchingDeclarationName")

package semantics.correctresolution

import kotlinx.coroutines.runBlocking
import model.Arguments
import model.EngineErrorData
import model.ObjectEngineResult
import model.PathComponent
import model.VariableBinding
import model.engineObjectDataOf
import model.merge
import model.outputValue
import model.registry.CheckerInput
import model.registry.CheckerResolverBase
import model.registry.ResolutionExecutionContext
import model.registry.ResolverFragments
import model.registry.VariableDefinition
import model.requireQueryTypeDef
import semantics.shared.CycleTask
import semantics.shared.SharedOperationContext
import semantics.shared.fieldCheckerCycleTask
import semantics.shared.groundedArguments
import semantics.shared.materializeResult
import semantics.shared.typeCheckerCycleTask
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData

/** One deterministic checker relation reconstructed from a completed result. */
internal class ReappliedChecker(
    val result: CheckerResult,
)

/** Reapplies the field checker for one exact checked occurrence. */
internal fun ObjectEngineResult.reapplyChecker(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
    key: ObjectEngineResult.ObjectKey,
    path: List<PathComponent>,
): ReappliedChecker? =
    resolverApplicationCache.getOrPutChecker(this, key) {
        val arguments = key.groundedArguments(operation) as? Arguments.Resolved ?: return@getOrPutChecker null
        val checker = operation.world.resolverRegistry.fieldChecker(key.field) ?: return@getOrPutChecker null
        val coordinate = path + key
        val fragments =
            checker.instantiateFragmentsAt(
                resolverApplicationCache.root,
                coordinate,
            )
        if (!fragments.bindingsAgreeWith(arguments, operation)) return@getOrPutChecker null
        val inputs = checker.rawInputs(
            operation,
            resolverApplicationCache,
            this,
            path,
            fragments,
            resolverApplicationCache.root.fieldCheckerCycleTask(coordinate),
        ) ?: return@getOrPutChecker null
        ReappliedChecker(
            runBlocking {
                checker.evaluateRelation(arguments, inputs, ResolutionExecutionContext.Unsupported)
            },
        )
    }

/** Reapplies a type checker once per concrete OER, including occurrences reached via parents. */
internal fun ObjectEngineResult.reapplyTypeChecker(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
    path: List<PathComponent>,
): ReappliedChecker? =
    resolverApplicationCache.getOrPutTypeChecker(this) {
        val checker = operation.world.resolverRegistry.typeChecker(type) ?: return@getOrPutTypeChecker null
        val fragments = checker.instantiateFragmentsAt(resolverApplicationCache.root, path)
        if (!fragments.bindingsAgreeWith(null, operation)) return@getOrPutTypeChecker null
        val inputs = checker.rawInputs(
            operation,
            resolverApplicationCache,
            this,
            path,
            fragments,
            resolverApplicationCache.root.typeCheckerCycleTask(path),
        ) ?: return@getOrPutTypeChecker null
        ReappliedChecker(
            runBlocking {
                checker.evaluateRelation(inputs, ResolutionExecutionContext.Unsupported)
            },
        )
    }

/** Reconstructs named raw projections without deriving them from runtime invocation inputs. */
private fun CheckerResolverBase<*>.rawInputs(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
    result: ObjectEngineResult,
    path: List<PathComponent>,
    fragments: ResolverFragments,
    reader: CycleTask,
): Map<String, CheckerInput>? {
    if (!result.conformsToSelectionsAt(operation, fragments.objectFragment.constructionSelections, path)) {
        return null
    }
    val occurrenceId = fragments.objectFragment.resolverOccurrenceId
    val objectInputs = instantiateObjectMaterializationSelections(occurrenceId).mapValues { (_, selections) ->
        runBlocking { result.materializeResult(operation, selections, reader = reader, checked = false) }
    }
    val queryInputs = checkerQueryInputs(operation, resolverApplicationCache, fragments, reader) ?: return null
    val inputs = fragmentTemplates.keys.associateWith { name ->
        CheckerInput(objectInputs.getValue(name), queryInputs.getValue(name))
    }
    // Read the original named response path, independently of the compiled provider guards.
    // Another owner's physical demand cannot make an excluded defining alias a provider.
    val definitions = (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions)
        .distinctBy { it.variable }
        .associateBy { it.variable.variableName }
    val bindingsAgree = fragmentTemplates.all { (name, templates) ->
        templates.variables.all { (variable, definition) ->
            if (definition !is VariableDefinition.FromField) {
                true
            } else {
                val input = inputs.getValue(name)
                val source = if (definition.providerFragment == model.registry.ProviderFragment.OBJECT) input.objectValue else input.queryValue
                val expected = source.bindingAtResponsePath(definition.responsePath)
                val instance = definitions.getValue("$name:${variable.variableName}").variable.instanceId!!
                operation.variableBindings.getBinding(instance) == expected
            }
        }
    }
    return inputs.takeIf { bindingsAgree }
}

private fun CheckerResolverBase<*>.checkerQueryInputs(
    operation: SharedOperationContext<*>,
    resolverApplicationCache: ResolverApplicationCache,
    fragments: ResolverFragments,
    reader: CycleTask,
): Map<String, EngineObjectData.Sync>? {
    val occurrenceId = fragments.queryFragment.resolverOccurrenceId
    val materializationSelections = instantiateQueryMaterializationSelections(occurrenceId)
    if (fragments.queryFragment.constructionSelections.isEmpty()) {
        val emptyQuery = engineObjectDataOf(operation.world.schema.requireQueryTypeDef())
        return materializationSelections.mapValues { emptyQuery }
    }

    val queryResult =
        (operation.checkerObserver as? CorrectnessCheckerObserver)
            ?.queryFragmentResults(target, occurrenceId)
            ?.singleOrNull()
            ?: return null
    val querySelections =
        fragments.queryFragment.constructionSelections
            .merge(operation.world.schema.requireQueryTypeDef())
    if (
        !resolverApplicationCache.queryResultConforms(
            operation,
            queryResult,
            querySelections,
            selectionsAreChecked = false,
        )
    ) {
        return null
    }
    return materializationSelections.mapValues { (_, selections) ->
        runBlocking {
            queryResult.materializeResult(
                operation = operation,
                selections = selections,
                reader = reader,
                checked = false,
            )
        }
    }
}

private fun ResolverFragments.bindingsAgreeWith(
    arguments: Arguments.Resolved?,
    operation: SharedOperationContext<*>,
): Boolean =
    (objectFragment.variableDefinitions + queryFragment.variableDefinitions)
        .distinctBy { definition -> definition.variable }
        .all { variableDefinition ->
            val instanceId = requireNotNull(variableDefinition.variable.instanceId)
            if (!operation.variableBindings.isBound(instanceId)) return@all false
            val definition = variableDefinition.definition
            definition !is VariableDefinition.FromArgument ||
                operation.variableBindings.getBinding(instanceId) ==
                VariableBinding.of(definition.read(arguments ?: return@all false))
        }

/** Checker results have semantic variants but no tenant-independent error equality. */
internal fun CheckerResult?.sameResultVariantAs(other: CheckerResult?): Boolean =
    when (this) {
        CheckerResult.Success -> other === CheckerResult.Success
        is CheckerResult.Error -> other is CheckerResult.Error
        null -> other == null
    }

/** Null covers both an excluded path component and a selected null intermediate or terminal. */
private fun EngineObjectData.Sync.bindingAtResponsePath(path: List<String>): VariableBinding {
    var value: Any? = this
    path.forEach { responseKey ->
        if (value == null) return VariableBinding.of(null)
        if (value is EngineErrorData) return VariableBinding.Error
        val objectValue = value as EngineObjectData.Sync
        if (!objectValue.isPresent(responseKey)) return VariableBinding.of(null)
        value = objectValue.outputValue(responseKey)
    }

    fun hasError(value: Any?): Boolean =
        when (value) {
            is EngineErrorData -> true
            is List<*> -> value.any(::hasError)
            else -> false
        }
    return if (hasError(value)) VariableBinding.Error else VariableBinding.of(value)
}
