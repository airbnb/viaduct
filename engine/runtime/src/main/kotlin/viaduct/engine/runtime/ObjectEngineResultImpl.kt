@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime

import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLEnumType
import graphql.schema.GraphQLInterfaceType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import graphql.schema.GraphQLScalarType
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnionType
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import viaduct.deferred.completedDeferred
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.gj

/**
 * Thread-safe data structure for memoizing field resolution results during GraphQL query execution.
 * See [Cell] for more about thread safety guarantees.
 *
 * It is designed to handle the following GraphQL execution patterns:
 *
 * 1. Multiple resolvers may attempt to resolve the same field concurrently
 * 2. Resolvers may request field values before they are computed
 * 3. Field values, once set, are immutable
 *
 * The [ObjectEngineResultImpl] has a "lazy resolution state". It can start in either a complete or incomplete state,
 * represented using a [Deferred] value.
 *
 * Usage example:
 * ```
 * // Writer:
 * ```
 * val result = engine.computeIfAbsent(key) { slotSetter ->
 *     slotSetter.setRaw(computeValue())
 *     slotSetter.setAccessCheck(computeAccessCheckValue())
 * }
 * ```
 *
 * // Reader:
 * val value = engine.fetch(key, ACCESS_CHECK_SLOT) // Suspends if value not yet available
 * ```
 *
 * @see fetch For reading values
 * @see computeIfAbsent For the combined claim/compute/complete operation
 */
class ObjectEngineResultImpl private constructor(
    override val graphQLObjectType: GraphQLObjectType,
    val pending: Boolean = false,
) : ObjectEngineResult {
    private val storage = ConcurrentHashMap<ObjectEngineResult.Key, Cell>()

    /**
     * The engine supports lazy nodes, which is are node-objects with an id field set
     * but no other fields set. When a resolver returns a lazy node, we "optimistically"
     * allocate an OER for it to store the id and to indicate that the node resolver still
     * needs to be called.
     *
     * During resolution the node resolver is called in parallel with field-resolvers on that
     * node, meaning fields of this this OER might get filled out with values even though,
     * eventually, if the node resolver fails, we're going to discard those results and treat
     * the entire field containing the OER as failed.
     *
     * A key observation here is that during resolution, it's okay to treat the OER as if it's
     * going to be successful (we're "optimistic" during resolution). However, during completion,
     * if the node resolver has failed, we need to treat the field containing the OER as if it
     * failed (and discard any results we optimistically resolved).
     *
     * To implement these semantics, OERs have a property called [lazyResolutionState], which is a CompletableDeferred.
     * While [lazyResolutionState] is uncompleted, we say that the OER is "pending", which means its in its "optimistic"
     * phase and fetches of its fields return normally. However, once the node resolver returns,
     * [lazyResolutionState] is completed either normally or exceptionally based on the success of the node resolver.
     * After that point, fetches of its values will fail if [lazyResolutionState] was completed exceptionally.
     */
    val lazyResolutionState: CompletableDeferred<Unit> = let {
        if (pending) {
            // create a new pending deferred
            CompletableDeferred()
        } else {
            // otherwise, create a pre-completed deferred
            completedDeferred(Unit)
        }
    }

    /**
     * A deferred that is completed when all fields of this object have been resolved
     * (fetched and cells populated), but not necessarily when all nested objects have
     * finished resolving.
     *
     * This acts as a barrier for [FieldCompleter]: completion of this object should
     * not start until this barrier is open, ensuring that synchronous values are
     * already present in [Cell]s, avoiding the need for [CompletableDeferred]s in
     * the [Cell] read path.
     */
    val fieldResolutionState: CompletableDeferred<Unit> = CompletableDeferred()

    /**
     * Fetches the value in slot number [slotNo] for the field, suspending if not yet available.
     *
     * @param key The key to fetch
     * @return The value for the field.
     */
    override suspend fun fetch(
        key: ObjectEngineResult.Key,
        slotNo: Int
    ): Any? {
        return getValue(key, slotNo).await()
    }

    /**
     * Gets the [Value] in slot number [slotNo] for the field.
     */
    internal fun getValue(
        key: ObjectEngineResult.Key,
        slotNo: Int
    ): Value<*> {
        if (lazyResolutionState.isCompleted) {
            lazyResolutionState.getCompletionExceptionOrNull()?.let {
                return Value.fromThrowable<Nothing>(it)
            }
        }
        // It's possible that [lazyResolutionState] is completed between the isCompleted check above
        // and here. It's only in the "optimistic" case during resolution that this can
        // occur. During field completion, we "pessimistically" wait for nodeResolutionState to complete
        // before retrieving the value, so there is no race condition.
        return maybeInitializeKey(key).getValue(slotNo)
    }

    /**
     * Computes a value if not already present, or returns the existing value.
     *
     * This method ensures that only one computation will occur for a given key,
     * even under concurrent access. If multiple coroutines attempt to compute
     * the same key simultaneously:
     * - One will claim the key and compute the value
     * - Others will wait for and return the computed value
     *
     * @param key The key to compute or fetch
     * @param block A function that sets each slot with the computed [Value]
     * @return A [Value] containing either the computed or existing value in the RAW_VALUE_SLOT
     */
    internal fun computeIfAbsent(
        key: ObjectEngineResult.Key,
        block: (SlotSetter) -> Unit
    ): Value<*> {
        return maybeInitializeKey(key)
            .computeIfAbsent(block)
            .getValue(RAW_VALUE_SLOT)
    }

    /**
     * Waits for this OER to finish resolving, and returns the error if it resolved exceptionally
     * or null if it resolved successfully.
     */
    internal suspend fun resolvedExceptionOrNull(): Throwable? {
        return runCatching { lazyResolutionState.await() }.exceptionOrNull()
    }

    /**
     * Initializes a field with an unwritten cell.
     *
     * @param key The key to initialize
     * @return Cell associated with this key (which may have been newly created).
     */
    private fun maybeInitializeKey(key: ObjectEngineResult.Key): Cell = storage.computeIfAbsent(key) { newCell() }

    /**
     * Resolves this OER exceptionally if it is in the pending state.
     * If already resolved with the same exception, this is a no-op.
     *
     * @throws IllegalStateException if this OER is already resolved normally or with a different exception
     */
    internal fun resolveExceptionally(exception: Throwable) {
        if (!lazyResolutionState.completeExceptionally(exception)) {
            val completionException = lazyResolutionState.getCompletionExceptionOrNull()
            if (completionException == exception) {
                return
            }
            // Otherwise it was resolved normally or with a different exception
            throw IllegalStateException("Invariant: already resolved", completionException)
        }
    }

    /**
     * Resolves this OER if it is in the pending state.
     * If already resolved normally, this is a no-op.
     *
     * @throws IllegalStateException if this OER is already resolved exceptionally
     */
    internal fun resolve() {
        if (!lazyResolutionState.complete(Unit)) {
            lazyResolutionState.getCompletionExceptionOrNull()?.let {
                throw IllegalStateException("Invariant: already resolved exceptionally", it)
            }
        }
    }

    companion object {
        private val DEFAULT_SLOT_COUNT = 2
        val RAW_VALUE_SLOT: Int = 0
        val ACCESS_CHECK_SLOT: Int = 1

        internal fun newCell() = Cell.create(DEFAULT_SLOT_COUNT)

        internal fun newCell(block: (SlotSetter) -> Unit) = Cell.create(DEFAULT_SLOT_COUNT, block)

        internal fun SlotSetter.setRawValue(value: Value<FieldResolutionResult>) {
            this.set(RAW_VALUE_SLOT, value)
        }

        internal fun SlotSetter.setCheckerValue(value: Value<out CheckerResult?>) {
            this.set(ACCESS_CHECK_SLOT, value)
        }

        /**
         * Creates a new ObjectEngineResult for the given type in the Resolved state.
         */
        fun newForType(type: GraphQLObjectType) = ObjectEngineResultImpl(type)

        /**
         * Creates a new ObjectEngineResult for the given type in the Pending state.
         */
        fun newPendingForType(type: GraphQLObjectType) = ObjectEngineResultImpl(type, pending = true)

        /**
         * Temporary helper to convert a fully resolved response from the ViaductClassicFragmentLoader to an ObjectEngineResult.
         *
         * @param type The GraphQLObjectType that the data represents. This is the type of the constructed ObjectEngineResult
         * @param data The data for this object
         * @param errors The errors from executing the fragment. Errors are represented as
         *                Pair(<path e.g. "nodes.0.name">, <the error as a Throwable>)
         * @param currentPath The current path of this object in the fragment response, e.g. "nodes.0"
         * @param graphqlSchema The schema that the query was executed against
         */
        fun newFromMap(
            type: GraphQLObjectType,
            data: Map<String, Any?>,
            errors: MutableList<Pair<String, Throwable>>,
            currentPath: List<String> = emptyList(),
            schema: ViaductSchema,
            selectionSet: RawSelectionSet,
        ): ObjectEngineResultImpl =
            newFromMap(
                type = type,
                data = data.rekey(type, selectionSet),
                errors = errors.map { ObjectEngineResult.Key(it.first) to it.second }.toMutableList(),
                currentPath = currentPath,
                schema = schema,
                selectionSet = selectionSet,
            )

        @JvmName("newFromMap2")
        fun newFromMap(
            type: GraphQLObjectType,
            data: Map<ObjectEngineResult.Key, Any?>,
            errors: MutableList<Pair<ObjectEngineResult.Key, Throwable>>,
            currentPath: List<String> = emptyList(),
            schema: ViaductSchema,
            selectionSet: RawSelectionSet
        ): ObjectEngineResultImpl {
            val result = newForType(type)
            // Since this OER is created from existing data, its resolution is already complete
            result.fieldResolutionState.complete(Unit)

            data.forEach { (key, value) ->
                val field = schema.schema.getFieldDefinition((type.name to key.name).gj)
                result.computeIfAbsent(key) { slotSetter ->
                    val rawValue =
                        if (value == null) {
                            val pathString = currentPath.joinToString(".")
                            // Find matching errors for this path
                            val matchingErrors = errors.filter {
                                it.first.name.startsWith(pathString)
                            }

                            if (matchingErrors.isNotEmpty()) { // Complete with first matching error
                                Value.fromThrowable<Nothing>(matchingErrors.first().second).also {
                                    errors.removeAll(matchingErrors)
                                }
                            } else { // Complete with null value
                                Value.fromValue(null)
                            }
                        } else {
                            Value.fromValue(
                                convertFieldValue(
                                    key,
                                    type,
                                    field.type,
                                    value,
                                    errors,
                                    currentPath + listOf(key.name),
                                    schema,
                                    selectionSet
                                )
                            )
                        }

                    // This function is actually used for completed values, which we'll
                    // store in the raw slot
                    slotSetter.set(RAW_VALUE_SLOT, rawValue)
                    slotSetter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
                }
            }
            return result
        }

        private fun convertFieldValue(
            key: ObjectEngineResult.Key,
            parentType: GraphQLOutputType,
            fieldType: GraphQLOutputType,
            value: Any?,
            errors: MutableList<Pair<ObjectEngineResult.Key, Throwable>>,
            currentPath: List<String>,
            schema: ViaductSchema,
            selectionSet: RawSelectionSet
        ): Any? {
            if (value == null) return null

            return when (val unwrappedType = GraphQLTypeUtil.unwrapNonNull(fieldType)) {
                // null, scalar and enum values pass through directly
                is GraphQLScalarType,
                is GraphQLEnumType -> value

                // Lists need each element converted
                is GraphQLList -> {
                    val elementType = GraphQLTypeUtil.unwrapOne(unwrappedType) as GraphQLOutputType
                    (value as List<*>).mapIndexed { idx, element ->
                        val rawValue = Value.fromValue(
                            convertFieldValue(
                                key,
                                parentType,
                                elementType,
                                element,
                                errors,
                                currentPath + listOf(idx.toString()),
                                schema,
                                selectionSet
                            )
                        )
                        newCell { slotSetter ->
                            slotSetter.set(RAW_VALUE_SLOT, rawValue)
                            slotSetter.set(ACCESS_CHECK_SLOT, Value.fromValue(null))
                        }
                    }
                }

                // Objects become nested ObjectEngineResults
                is GraphQLObjectType -> {
                    val subSelectionSet = selectionSet.selectionSetForSelection(
                        (parentType as GraphQLCompositeType).name,
                        key.alias ?: key.name
                    )
                    newFromMap(
                        unwrappedType,
                        (value as Map<*, Any?>).rekey(unwrappedType, subSelectionSet),
                        errors,
                        currentPath,
                        schema,
                        subSelectionSet
                    )
                }

                // Interfaces and unions need concrete type resolution
                is GraphQLInterfaceType,
                is GraphQLUnionType -> {
                    @Suppress("UNCHECKED_CAST")
                    val valueMap = value as Map<String, Any?>
                    val typeName = valueMap["__typename"] as String
                    val concreteType = schema.schema.getObjectType(typeName)
                    val subSelectionSet = selectionSet.selectionSetForSelection(
                        (parentType as GraphQLCompositeType).name,
                        key.alias ?: key.name
                    )

                    newFromMap(
                        concreteType,
                        valueMap.rekey(concreteType, subSelectionSet),
                        errors,
                        currentPath,
                        schema,
                        subSelectionSet
                    )
                }

                else -> throw IllegalStateException(
                    "Unexpected type ${GraphQLTypeUtil.simplePrint(unwrappedType)}"
                )
            }
        }

        /**
         * Rekeys a map of data to [ObjectEngineResult.Key]s based on the provided selection set and object type.
         * This allows us to handle field aliases and arguments correctly; ignoring this and simply using the key name
         * can lead to mismatched keys, which in turn can lead to the engine resolution hanging.
         *
         * Note that this rekeys just the top-level keys of the map; nested objects will not be rekeyed.
         */
        private fun Map<*, Any?>.rekey(
            type: GraphQLObjectType,
            selectionSet: RawSelectionSet
        ): Map<ObjectEngineResult.Key, Any?> {
            if (keys.all { it is ObjectEngineResult.Key }) {
                @Suppress("UNCHECKED_CAST")
                return this as Map<ObjectEngineResult.Key, Any?>
            }

            val selectionsByName = selectionSet.selections().associateBy { it.selectionName }

            val map = mutableMapOf<ObjectEngineResult.Key, Any?>()
            forEach { (key, value) ->
                val keyString = requireNotNull(key as? String) {
                    "Cannot rekey a map with keys of type ${key?.javaClass?.name}"
                }
                selectionsByName[keyString]?.let { sel ->
                    val arguments = selectionSet.argumentsOfSelection(type.name, sel.selectionName) ?: emptyMap()
                    val objectEngineResultKey = ObjectEngineResult.Key(name = sel.fieldName, alias = sel.selectionName, arguments = arguments)
                    map[objectEngineResultKey] = value
                }
            }
            return map.toMap()
        }
    }
}
