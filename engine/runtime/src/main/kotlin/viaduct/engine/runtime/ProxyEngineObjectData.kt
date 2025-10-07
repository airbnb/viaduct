package viaduct.engine.runtime

import graphql.GraphQLError
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.ObjectEngineResult.Key.Companion.invoke
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.UnsetSelectionException
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.ACCESS_CHECK_SLOT
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.RAW_VALUE_SLOT

/**
 * A proxy that handles projecting the given tenant selection set onto an unprojected EngineObjectData.
 * @param objectEngineResult Parent engine result
 * @param selectionSet The selection set required by the resolver
 *
 * TODO (https://app.asana.com/1/150975571430/project/1203659453427089/task/1211379042763526?focus=true):
 * move errorMessage out and provide this information in the tenant API instead
 */
open class ProxyEngineObjectData(
    private val objectEngineResult: ObjectEngineResult,
    private val errorMessage: String,
    private val selectionSet: RawSelectionSet? = null,
) : EngineObjectData {
    override val graphQLObjectType = objectEngineResult.graphQLObjectType

    protected open fun createInstance(
        objectEngineResult: ObjectEngineResult,
        errorMessage: String,
        selectionSet: RawSelectionSet?
    ): ProxyEngineObjectData {
        return ProxyEngineObjectData(objectEngineResult, errorMessage, selectionSet)
    }

    /**
     * @param selection A field or alias name
     */
    override suspend fun fetch(selection: String): Any? {
        val selections = checkSelectionIsInSelectionSet(selection)
        val subselections = maybeSubselections(selection, selections)
        val key = buildOerKey(selection, selections)
        val value = objectEngineResult.fetchCheckedValue(key)

        return marshal(value, subselections)
    }

    /**
     * @param selection A field or alias name
     */
    override suspend fun fetchOrNull(selection: String): Any? {
        val selections = if (selectionSet == null || !selectionSet.containsSelection(graphQLObjectType.name, selection)) {
            return null
        } else {
            selectionSet
        }
        val subselections = maybeSubselections(selection, selections)
        val key = buildOerKey(selection, selections)
        val value = objectEngineResult.fetchCheckedValue(key)

        return marshal(value, subselections)
    }

    override suspend fun fetchSelections(): Iterable<String> {
        return if (selectionSet == null) {
            emptyList()
        } else {
            selectionSet
                .selectionSetForType(graphQLObjectType.name)
                .selections()
                .map { it.selectionName }
        }
    }

    /**
     * @param selection A field or alias name
     */
    private fun buildOerKey(
        selection: String,
        selections: RawSelectionSet
    ): ObjectEngineResult.Key {
        val rawSelection = selections.resolveSelection(objectEngineResult.graphQLObjectType.name, selection)
        val args = selections.argumentsOfSelection(rawSelection.typeCondition, rawSelection.selectionName)
            ?: emptyMap()
        return ObjectEngineResult.Key(rawSelection.fieldName, rawSelection.selectionName, args)
    }

    /** @throws UnsetSelectionException if the field is not in the selection set */
    private fun checkSelectionIsInSelectionSet(selection: String): RawSelectionSet {
        if (selectionSet == null || !selectionSet.containsSelection(graphQLObjectType.name, selection)) {
            throw UnsetSelectionException(
                selection,
                graphQLObjectType,
                errorMessage
            )
        }
        return selectionSet
    }

    /**
     * Returns the selection set for the given field if its base type is a composite
     * (object, interface, or union) type, otherwise null.
     */
    private fun maybeSubselections(
        resultKey: String,
        selections: RawSelectionSet
    ): RawSelectionSet? {
        val rawSelection = selections.resolveSelection(objectEngineResult.graphQLObjectType.name, resultKey)
        val field = objectEngineResult.graphQLObjectType.getField(rawSelection.fieldName)
        return if (GraphQLTypeUtil.unwrapAll(field.type) is GraphQLCompositeType) {
            selections.selectionSetForSelection(objectEngineResult.graphQLObjectType.name, resultKey)
        } else {
            null
        }
    }

    /**
     * Recursively convert engine data into a representation usable by a tenant
     */
    private suspend fun marshal(
        value: Any?,
        subselections: RawSelectionSet?
    ): Any? {
        return when (value) {
            null -> null
            is ObjectEngineResultImpl -> {
                val exception = value.resolvedExceptionOrNull()
                if (exception != null) throw exception
                createInstance(value, errorMessage, subselections)
            }
            is List<*> -> value.map { marshal(it, subselections) }
            is FieldResolutionResult -> {
                // if a field was resolved with field errors, throw a FieldErrorsException with those errors
                if (value.errors.isNotEmpty()) throw FieldErrorsException(value.errors)
                marshal(value.engineResult, subselections)
            }
            is Cell -> marshal(value.fetchCheckedValue(), subselections)
            else -> value
        }
    }

    protected open suspend fun ObjectEngineResult.fetchCheckedValue(key: ObjectEngineResult.Key): Any? {
        // Prioritize field fetch errors
        val rawValue = this.fetch(key, RAW_VALUE_SLOT)

        throwCheckerError(this.fetch(key, ACCESS_CHECK_SLOT))
        return rawValue
    }

    protected open suspend fun Cell.fetchCheckedValue(): Any? {
        // Prioritize field fetch errors
        val rawValue = this.fetch(RAW_VALUE_SLOT)

        throwCheckerError(this.fetch(ACCESS_CHECK_SLOT))
        return rawValue
    }

    private fun throwCheckerError(checkerResult: Any?) {
        checkerResult ?: return
        if (checkerResult !is CheckerResult) {
            throw IllegalStateException("Expected access check slot to contain a CheckerResult, got $checkerResult")
        }
        checkerResult.asError?.let { error ->
            // TODO: set the query directives for this field
            if (error.isErrorForResolver(CheckerResultContext())) {
                // TODO: rethrowing an exception here means the stack trace will be from
                // where the exception was initially constructed, which may be confusing
                throw error.error
            }
        }
    }
}

class FieldErrorsException(val graphQLErrors: List<GraphQLError>) : RuntimeException()
