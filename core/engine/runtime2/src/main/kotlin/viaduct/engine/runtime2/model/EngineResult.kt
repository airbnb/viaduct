package viaduct.engine.runtime2.model

import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/**
 * A finite field-resolution result whose only reference edges are distinguished parent fields.
 *
 * The semantic union contains Int, finite Double, Boolean, String, [EngineIDResult],
 * [ViaductSchema.EnumValue], [ObjectEngineResult], [ListEngineResult], or [ErrorEngineResult]. Nullable
 * uses additionally represent GraphQL null. Membership and schema compatibility are enforced by
 * result constructors and cell completion boundaries. Ordinary value containment is well-founded;
 * a [ObjectEngineResult.ParentKey] cell may additionally point to an existing ancestor OER. This
 * union is equality-heterogeneous; equality is defined only after narrowing to a member or
 * homogeneous subset.
 */
typealias EngineResult = Any

/** A structurally equal GraphQL ID result value. */
sealed interface EngineIDResult {
    val value: String

    companion object {
        fun of(value: String): EngineIDResult = EngineIDResultImpl(value)
    }
}

private data class EngineIDResultImpl(
    override val value: String,
) : EngineIDResult

/**
 * One step in an exact path through an engine-result tree.
 *
 * An [ObjectEngineResult.ObjectKey] selects an object field, while a [ListEngineResult.Index]
 * selects a list element. Equality is structural within each variant.
 */
sealed interface PathComponent

/**
 * Returns this exact OER path as an object-key-only selection path.
 *
 * A null path or any path containing a [ListEngineResult.Index] has no corresponding selection
 * path and yields null.
 */
internal fun List<PathComponent>?.toSelectionPath(): List<ObjectEngineResult.ObjectKey>? = this?.map { component -> component as? ObjectEngineResult.ObjectKey ?: return null }

/**
 * One result occurrence with one write-once activation decision and independent write-once value
 * and field-checker-result promises.
 *
 * Pending cells permit their value promise to be claimed, but neither promise can be read or
 * completed until the cell is activated. A negative activation decision permanently prohibits both
 * promises. Direct value setting activates a pending cell before completing it. The value contains
 * [EngineResult] or GraphQL null. Each checker-result slot contains [CheckerResult] or null, where
 * null means that no checker applies to that slot. Cells use reference equality and stable identity
 * hashing because their activation or any slot may be completed after publication.
 */
sealed interface EngineResultCell {
    /** Whether activation and both the value and field-checker-result promises have completed. */
    val isCompleted: Boolean

    /** Atomically completes this cell's activation decision and reports whether this call won. */
    fun setActivated(activated: Boolean): Boolean

    /** Suspends until activation is decided and throws when this cell is not activated. */
    suspend fun awaitActivated()

    /** Suspends until activation is decided and returns that decision. */
    suspend fun fetchActivated(): Boolean

    /** Throws unless this cell has already been activated. */
    fun checkActivated()

    /** The reservable raw-value promise owned by this cell. */
    val value: ReservablePromise<EngineResult?>

    /** The field-checker-result promise owned by this cell. */
    val fieldCheckerResult: Promise<CheckerResult?>
}

/**
 * The field-resolution error variant.
 *
 * ViaductSchema conformance admits this sibling result variant at every output type expression. It is
 * not a Kotlin bottom subtype and exposes no simple, object, or list result properties. Instances
 * use reference equality and preserve the complete metadata-bearing [errorData] represented at this
 * result location.
 */
sealed interface ErrorEngineResult {
    val errorData: EngineErrorData

    companion object {
        /** Creates the result-domain representation of [errorData]. */
        fun of(errorData: EngineErrorData): ErrorEngineResult = ErrorEngineResultImpl(errorData)
    }
}

private class ErrorEngineResultImpl(
    override val errorData: EngineErrorData,
) : ErrorEngineResult

/**
 * A finite object result whose exact cells are installed once.
 *
 * Every present key belongs to [type], may contain instantiated variables, and its cell value
 * completes only with a result conforming to the field's type expression. [getCell] is a strict read.
 * [reserveCell] explicitly installs an unclaimed reader placeholder on a mutable object. A writer
 * claims the value placeholder through [ReservablePromise.claim] or [ReservablePromise.set].
 * [freeze] seals the key set and freezes every present cell's value
 * slot. A claimed value promise may complete after freezing.
 *
 * Objects use reference equality and stable identity hashing, so they may be used as map keys while
 * cells are installed or their slots are completed.
 */
sealed interface ObjectEngineResult {
    /** Whether every present cell and the type-checker-result promise have completed. */
    val isCompleted: Boolean

    /** The type-checker-result promise owned by this object occurrence. */
    val typeCheckerResult: Promise<CheckerResult?>

    /**
     * An output-field coordinate consisting of a canonical field and its arguments.
     * Ordinary keys are alias-free; [MutationKey] additionally distinguishes the response key.
     *
     * ### Invariant: key-argument-definition
     *
     * [arguments] recursively conforms to [field]'s argument definition.
     *
     * ### Invariant: object-key-field-classification
     *
     * A key's [field] is a [ViaductSchema.ObjectField] exactly when the key is an [ObjectKey].
     *
     * Key equality is structural over [field] and [arguments], using canonical schema equality;
     * [MutationKey] also compares its response key.
     * Variable-instance identity is carried by variables recursively contained in [arguments].
     */
    sealed interface Key {
        val field: ViaductSchema.Field
        val arguments: Arguments

        companion object {
            /**
             * ### Invariant: map-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: ViaductSchema.Field,
                arguments: Map<String, Any?>,
            ): Key = of(field, Arguments.of(field, arguments))

            /** Constructs the precise key category for a field on a concrete object type. */
            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Map<String, Any?>,
            ): ObjectKey = ObjectKey.of(field, arguments)

            /**
             * ### Invariant: arguments-key-factory-schema-conformance
             *
             * Every result satisfies `result.conformsToSchema()` in its reasoning world.
             */
            fun of(
                field: ViaductSchema.Field,
                arguments: Arguments,
            ): Key {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Key arguments do not belong to its output field"
                }
                return when (field) {
                    is ViaductSchema.ObjectField -> ObjectKey.of(field, arguments)
                    else -> KeyImpl(field, arguments)
                }
            }

            /** Constructs the precise key category for a field on a concrete object type. */
            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Arguments,
            ): ObjectKey = ObjectKey.of(field, arguments)

            /** Constructs the precise ground key category. */
            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Arguments.Ground,
            ): GroundKey = GroundKey.of(field, arguments)
        }
    }

    /**
     * A key whose field belongs to a concrete object type.
     *
     * Every instance carries a [ViaductSchema.ObjectField] and [Arguments]. An object key may select
     * an exact OER cell and serve as an object path component even when its arguments contain
     * occurrence-specific variables.
     */
    sealed interface ObjectKey : Key, PathComponent {
        override val field: ViaductSchema.ObjectField
        override val arguments: Arguments

        companion object {
            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Map<String, Any?>,
            ): ObjectKey = of(field, Arguments.of(field, arguments))

            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Arguments,
            ): ObjectKey {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Key arguments do not belong to its output field"
                }
                return if (arguments is Arguments.Ground) {
                    GroundKey.of(field, arguments)
                } else {
                    ObjectKeyImpl(field, arguments)
                }
            }
        }
    }

    /**
     * A concrete-object key whose arguments have resolved.
     */
    sealed interface GroundKey : ObjectKey {
        override val arguments: Arguments.Ground

        companion object {
            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Map<String, Any?>,
            ): GroundKey {
                val grounded = Arguments.of(field, arguments)
                require(grounded is Arguments.Ground) {
                    "Ground-key arguments cannot contain variables"
                }
                return of(field, grounded)
            }

            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Arguments.Ground,
            ): GroundKey {
                require(arguments.conformsToArgumentDefinition(field)) {
                    "Key arguments do not belong to its output field"
                }
                return if (field.isParentField()) {
                    ParentKey.of(field, arguments)
                } else {
                    GroundKeyImpl(field, arguments)
                }
            }
        }
    }

    /** A mutation publication key, distinguished by its collected response key. */
    sealed interface MutationKey : GroundKey {
        val responseKey: String

        companion object {
            fun of(
                key: GroundKey,
                responseKey: String
            ): MutationKey {
                require(responseKey.isNotEmpty()) { "Mutation response keys must be nonempty" }
                return MutationKeyImpl(key.field, key.arguments, responseKey)
            }
        }
    }

    /**
     * A no-argument engine-provided field whose value references the containing object's parent.
     *
     * Parent keys remain ordinary selection and OER lookup keys. The refinement lets structural
     * result algorithms avoid recursively unfolding their ancestor backedges.
     */
    sealed interface ParentKey : GroundKey {
        override val arguments: Arguments.Resolved

        companion object {
            fun of(field: ViaductSchema.ObjectField): ParentKey = of(field, argumentsOfGround(emptyMap()))

            fun of(
                field: ViaductSchema.ObjectField,
                arguments: Arguments.Ground,
            ): ParentKey {
                require(arguments is Arguments.Resolved && arguments.fieldValues.isEmpty()) {
                    "Parent key field must have no arguments"
                }
                return ParentKeyImpl(field)
            }
        }
    }

    val type: ViaductSchema.Object

    val keys: Set<ObjectKey>

    fun isCellSet(field: ObjectKey): Boolean = field in keys

    /** @throws NoSuchElementException when [field] has no cell */
    fun getCell(field: ObjectKey): EngineResultCell

    /**
     * Returns the field cell, explicitly creating an unclaimed reader placeholder when this
     * mutable object is not frozen.
     *
     * @throws NoSuchElementException when this object is immutable or frozen and has no cell
     */
    fun reserveCell(field: ObjectKey): EngineResultCell

    /**
     * Installs an activated cell with a completed value, or completes a placeholder previously
     * reserved for [field].
     */
    fun setCellValue(
        field: ObjectKey,
        value: EngineResult?,
    ): EngineResultCell

    /**
     * Seals this object's cell-key set and freezes every present cell's value slot. Claimed
     * value promises may still complete.
     */
    fun freeze()

    companion object {
        /**
         * ### Invariant: object-engine-result-factory-schema-conformance
         *
         * Every initially present cell value satisfies its field's schema type. When [mutable]
         * is false, cell creation throws. When it is true, each absent exact cell may be
         * installed once and each slot of that cell may be installed once. Cells supplied in
         * [values] default field-checker results to completed nulls, meaning no checker applies.
         */
        fun of(
            type: ViaductSchema.Object,
            values: Map<ObjectKey, EngineResult?> = emptyMap(),
            fieldCheckerResults: Map<ObjectKey, CheckerResult?> =
                values.keys.associateWith { null },
            typeCheckerResult: Promise<CheckerResult?> = Promise.of(null),
            mutable: Boolean = false,
        ): ObjectEngineResult =
            objectEngineResultOf(
                type = type,
                values = values,
                fieldCheckerResults = fieldCheckerResults,
                typeCheckerResult = typeCheckerResult,
                mutable = mutable,
            )
    }
}

private fun objectEngineResultOf(
    type: ViaductSchema.Object,
    values: Map<ObjectEngineResult.ObjectKey, EngineResult?>,
    fieldCheckerResults: Map<ObjectEngineResult.ObjectKey, CheckerResult?>,
    typeCheckerResult: Promise<CheckerResult?>,
    mutable: Boolean,
): ObjectEngineResult {
    val fields = values.keys + fieldCheckerResults.keys
    fields.forEach { field -> validateObjectField(type, field) }
    values.forEach { (field, value) -> validateObjectValue(field, value) }
    return ObjectResultImpl(
        type = type,
        cells =
            fields.associateWith { field ->
                CellImpl(
                    initialValue = values[field],
                    initiallyValueSet = field in values,
                    fieldCheckerResult = fieldCheckerResults[field],
                    initiallyFieldCheckerResultSet = true,
                    mutable = mutable,
                    validateValue = { value -> validateObjectValue(field, value) },
                )
            },
        typeCheckerResult = typeCheckerResult,
        mutable = mutable,
    )
}

/**
 * A typed list result whose elements are cells.
 *
 * [typeExpr] is the expected type of each cell value, including its nullability and nested lists.
 * Lists use structural equality over [typeExpr] and positional cell equality; cells and object
 * values therefore compare by reference.
 *
 * Including [typeExpr] in equality is intentional. The factory validates every completed cell
 * value against it, so it acts as a retained type witness: assigning a list to a compatible list
 * position requires comparing type expressions, not recursively revalidating its contents.
 */
sealed interface ListEngineResult : List<EngineResultCell> {
    /** A non-negative position selecting one element of an engine-result list. */
    sealed interface Index : PathComponent {
        val index: Int

        companion object {
            fun of(index: Int): Index {
                require(index >= 0) { "List index must be non-negative" }
                return ListIndexImpl(index)
            }
        }
    }

    val typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>

    companion object {
        /**
         * Constructs a fixed-size list whose activated element cells each expose one uncompleted,
         * writable value promise. Their field-checker results are completed null because field
         * checkers belong to the containing field rather than to list-element cells.
         *
         * Every eventual element value must satisfy
         * `value.conformsToResultSchemaType(typeExpr)`. The list positions and their cell identities
         * are fixed at construction; each cell's value may be claimed and completed exactly once.
         */
        fun ofPendingValues(
            typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
            size: Int,
        ): ListEngineResult {
            require(size >= 0) { "List engine result size must be non-negative" }
            val cells =
                List(size) {
                    CellImpl(
                        initiallyActivated = true,
                        initiallyFieldCheckerResultSet = true,
                        mutable = true,
                        validateValue = { value -> validateListValue(typeExpr, value) },
                    )
                }
            return ListResultImpl(typeExpr, cells)
        }

        /**
         * ### Invariant: list-engine-result-factory-schema-conformance
         *
         * Every cell value satisfies `value.conformsToResultSchemaType(typeExpr)` in its reasoning
         * world. Omitted field-checker-result lists default to completed nulls, meaning no checker
         * applies.
         */
        fun of(
            typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
            values: List<EngineResult?>,
            fieldCheckerResults: List<CheckerResult?> =
                values.map { null },
            mutableCells: Boolean = false,
        ): ListEngineResult {
            require(values.all { value -> value.conformsToResultSchemaType(typeExpr) }) {
                "List engine result contains an element incompatible with $typeExpr"
            }
            require(fieldCheckerResults.size == values.size) {
                "List engine result field-checker results must match its value count"
            }
            val cells =
                values.mapIndexed { index, value ->
                    CellImpl(
                        initialValue = value,
                        initiallyValueSet = true,
                        fieldCheckerResult = fieldCheckerResults[index],
                        initiallyFieldCheckerResultSet = true,
                        mutable = mutableCells,
                        validateValue = { updated -> validateListValue(typeExpr, updated) },
                    )
                }
            return ListResultImpl(typeExpr, cells)
        }
    }
}

private fun validateListValue(
    typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    value: EngineResult?,
) {
    require(value.conformsToResultSchemaType(typeExpr)) {
        "List engine result contains an element incompatible with $typeExpr"
    }
}

/**
 * Returns whether two completed result trees contain the same values and checker results.
 *
 * This explicit extensional comparison is distinct from ordinary equality because
 * [EngineResultCell] and [ObjectEngineResult] use reference equality. Symbolic object keys compare
 * variable occurrences by their root-relative addresses so independently rooted executions of the
 * same construction can compare extensionally; ordinary key and variable equality remains fully
 * root-qualified. Both trees must be finite and every present promise they contain must be
 * completed. Its result is meaningful only after both trees are quiescent; the comparison does not
 * take an atomic snapshot while promises or cells are being mutated concurrently.
 * Two completed checker errors compare extensionally as errors without invoking their externally
 * supplied equality.
 *
 * @throws UncompletedPromiseException when either tree contains an uncompleted promise
 */
fun EngineResult?.sameCompletedResultAs(other: EngineResult?): Boolean {
    val same = CompletedResultComparison().same(this, other)
    if (!same) {
        requireCompleted()
        other.requireCompleted()
    }
    return same
}

private class CompletedResultComparison {
    private val rightByLeft = IdentityHashMap<ObjectEngineResult, ObjectEngineResult>()
    private val leftByRight = IdentityHashMap<ObjectEngineResult, ObjectEngineResult>()

    fun same(
        left: EngineResult?,
        right: EngineResult?,
    ): Boolean {
        if (left == null || right == null) return left == right

        return when (left) {
            is ErrorEngineResult -> right is ErrorEngineResult
            is ListEngineResult ->
                right is ListEngineResult &&
                    left.typeExpr == right.typeExpr &&
                    left.size == right.size &&
                    left.indices.all { index -> sameCell(left[index], right[index]) }
            is ObjectEngineResult -> right is ObjectEngineResult && sameObject(left, right)
            else -> left.isScalarResultMember() && right.isScalarResultMember() && left == right
        }
    }

    fun sameCell(
        left: EngineResultCell,
        right: EngineResultCell,
    ): Boolean =
        same(left.completedValue, right.completedValue) &&
            left.completedFieldCheckerResult.hasSameCompletedCheckerResultAs(
                right.completedFieldCheckerResult,
            )

    fun sameParentCell(
        left: EngineResultCell,
        right: EngineResultCell,
    ): Boolean {
        val leftValue = left.completedValue
        val rightValue = right.completedValue
        val sameValue =
            when {
                leftValue == null || rightValue == null -> leftValue == null && rightValue == null
                leftValue is ObjectEngineResult && rightValue is ObjectEngineResult ->
                    rightByLeft[leftValue] === rightValue && leftByRight[rightValue] === leftValue
                else -> false
            }
        return sameValue &&
            left.completedFieldCheckerResult.hasSameCompletedCheckerResultAs(
                right.completedFieldCheckerResult,
            )
    }

    private fun sameObject(
        left: ObjectEngineResult,
        right: ObjectEngineResult,
    ): Boolean {
        rightByLeft[left]?.let { mapped -> return mapped === right }
        if (leftByRight.containsKey(right)) return false
        rightByLeft[left] = right
        leftByRight[right] = left
        return left.sameCompletedObjectResultAs(right, this)
    }
}

private fun CheckerResult?.hasSameCompletedCheckerResultAs(other: CheckerResult?): Boolean =
    when {
        this is CheckerResult.Error -> other is CheckerResult.Error
        this === CheckerResult.Success -> other === CheckerResult.Success
        else -> other == null
    }

/**
 * Returns the structural union of this nullable result and [other].
 *
 * Two null values have a null union. A null and non-null value have no union. For two non-null
 * values, this partial mathematical function is defined only for results of the same variant. Two
 * error results additionally require the same [EngineErrorData] carrier so union cannot discard
 * error metadata.
 *
 * @throws IllegalArgumentException when the union is undefined
 */
internal fun EngineResult?.union(other: EngineResult?): EngineResult? {
    if (this == null) {
        require(other == null) { "Cannot union null and non-null engine results" }
        return null
    }
    require(other != null) { "Cannot union null and non-null engine results" }
    require(!containsParentBackedge() && !other.containsParentBackedge()) {
        "Cannot union engine-result graphs containing parent backedges"
    }

    return when (this) {
        is ErrorEngineResult -> {
            require(other is ErrorEngineResult) {
                "Cannot union error and non-error engine results"
            }
            require(errorData === other.errorData) {
                "Cannot union engine results containing distinct errors"
            }
            this
        }

        is ListEngineResult -> {
            require(other is ListEngineResult) {
                "Cannot union different engine-result variants"
            }
            union(other)
        }
        is ObjectEngineResult -> {
            require(other is ObjectEngineResult) {
                "Cannot union different engine-result variants"
            }
            union(other)
        }
        else -> {
            require(isScalarResultMember() && other.isScalarResultMember()) {
                "Cannot union different engine-result variants"
            }
            require(this == other) { "Cannot union unequal scalar engine results" }
            this
        }
    }
}

private fun EngineResult.containsParentBackedge(): Boolean =
    when (this) {
        is ObjectEngineResult ->
            keys.any { key -> key is ObjectEngineResult.ParentKey } ||
                keys.any { key ->
                    val cell = getCell(key)
                    cell.implementation.isActivated &&
                        cell.value.get()?.containsParentBackedge() == true
                }
        is ListEngineResult ->
            any { cell -> cell.value.get()?.containsParentBackedge() == true }
        else -> false
    }

/**
 * Returns the union of this completed cell and [other].
 *
 * @throws IllegalArgumentException when their values have no union or their checker results differ
 */
private fun CompletedCell.union(other: CompletedCell): CompletedCell =
    CompletedCell(
        value = value.union(other.value),
        fieldCheckerResult = unionCheckerResult(fieldCheckerResult, other.fieldCheckerResult),
    )

/**
 * Returns the object result containing the union of every cell present in either operand.
 *
 * @throws IllegalArgumentException when the object types differ or any shared cell has no union
 */
internal fun ObjectEngineResult.union(other: ObjectEngineResult): ObjectEngineResult {
    require(!containsParentBackedge() && !other.containsParentBackedge()) {
        "Cannot union engine-result graphs containing parent backedges"
    }
    require(type == other.type) {
        "Cannot union object engine results of different types"
    }

    val leftCells = implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val rightCells = other.implementation.completedCells.mapValues { (_, cell) -> cell.completed() }
    val cells = unionMaps(leftCells, rightCells, CompletedCell::union)
    val oerTypeCheckerResult =
        unionCheckerResult(
            typeCheckerResult.get(),
            other.typeCheckerResult.get(),
        )
    return objectEngineResultOf(
        type = type,
        values = cells.mapValues { (_, cell) -> cell.value },
        fieldCheckerResults = cells.mapValues { (_, cell) -> cell.fieldCheckerResult },
        typeCheckerResult = Promise.of(oerTypeCheckerResult),
        mutable = false,
    )
}

/**
 * Returns the position-wise union of this list and [other].
 *
 * The operands must have equal element type expressions and lengths.
 *
 * @throws IllegalArgumentException when the type expressions or lengths differ, or when any
 * corresponding cells have no union
 */
internal fun ListEngineResult.union(other: ListEngineResult): ListEngineResult {
    require(!containsParentBackedge() && !other.containsParentBackedge()) {
        "Cannot union engine-result graphs containing parent backedges"
    }
    require(typeExpr == other.typeExpr) {
        "Cannot union list engine results with different element types"
    }
    require(size == other.size) {
        "Cannot union list engine results of different lengths"
    }
    val cells = indices.map { index -> this[index].completed().union(other[index].completed()) }
    return ListResultImpl(
        typeExpr = typeExpr,
        cells =
            cells.map { cell ->
                CellImpl(
                    initialValue = cell.value,
                    initiallyValueSet = true,
                    fieldCheckerResult = cell.fieldCheckerResult,
                    initiallyFieldCheckerResultSet = true,
                    mutable = false,
                    validateValue = { value -> validateListValue(typeExpr, value) },
                )
            },
    )
}

private class CellImpl(
    initiallyActivated: Boolean = false,
    initialValue: EngineResult? = null,
    initiallyValueSet: Boolean = false,
    fieldCheckerResult: CheckerResult? = null,
    initiallyFieldCheckerResultSet: Boolean = false,
    private val mutable: Boolean,
    private val validateValue: (EngineResult?) -> Unit = {},
) : EngineResultCell {
    private val activationLock = Any()
    private val activation: Promise<Boolean> =
        if (
            initiallyActivated || initiallyValueSet ||
            initiallyFieldCheckerResultSet
        ) {
            Promise.of(true)
        } else {
            Promise.ofDeferred()
        }
    private val unguardedValue: ReservablePromise<EngineResult?> =
        if (initiallyValueSet) {
            completedReservablePromise(initialValue, mutable, validateValue)
        } else {
            reservablePromise(mutable, validateValue)
        }
    override val value: ReservablePromise<EngineResult?> =
        activationAwareReservablePromise(this, unguardedValue)
    override val fieldCheckerResult: Promise<CheckerResult?> =
        activationAwarePromise(
            this,
            if (initiallyFieldCheckerResultSet) {
                Promise.of(fieldCheckerResult)
            } else {
                Promise.ofDeferred()
            },
        )

    override val isCompleted: Boolean
        get() =
            activation.isCompleted &&
                value.isCompleted &&
                fieldCheckerResult.isCompleted

    override fun setActivated(activated: Boolean): Boolean {
        checkMutable()
        return synchronized(activationLock) {
            activation.complete(activated)
        }
    }

    override suspend fun awaitActivated() {
        check(activation.await()) { "Cell was not activated" }
    }

    override suspend fun fetchActivated(): Boolean = activation.await()

    override fun checkActivated() {
        synchronized(activationLock) {
            check(activation.isCompleted && activation.get()) { "Cell is not activated" }
        }
    }

    fun activateForCancellation(): Boolean {
        checkMutable()
        return synchronized(activationLock) {
            activation.complete(true)
            activation.get()
        }
    }

    fun freezeValue(cause: () -> Exception) {
        if (mutable) {
            value.freeze(cause)
        }
    }

    fun failActivation(cause: Exception) {
        synchronized(activationLock) {
            activation.fail(cause)
        }
    }

    fun requireCompleted() {
        val activated = activation.get()
        if (!activated) return
        value.get()
        fieldCheckerResult.get()
    }

    val isActivated: Boolean
        get() = activation.get()

    val completedValue: EngineResult?
        get() = value.get()

    val completedFieldCheckerResult: CheckerResult?
        get() = fieldCheckerResult.get()

    private fun checkMutable() = check(mutable) { "Cell is immutable" }

    fun checkMayWrite() {
        checkMutable()
        synchronized(activationLock) {
            check(!activation.isCompleted || activation.get()) { "Cell was not activated" }
        }
    }

    fun activateForWrite() {
        checkMutable()
        synchronized(activationLock) {
            activation.complete(true)
            check(activation.get()) { "Cell was not activated" }
        }
    }
}

private open class ObjectResultImpl(
    override val type: ViaductSchema.Object,
    cells: Map<ObjectEngineResult.ObjectKey, EngineResultCell>,
    override val typeCheckerResult: Promise<CheckerResult?>,
    private val mutable: Boolean,
) : ObjectEngineResult {
    private val cellStore =
        ObjectCellStore(
            type = type,
            cells = cells,
            mutable = mutable,
        )
    override val isCompleted: Boolean
        get() =
            cellStore.cellEntries.all { (_, cell) -> cell.isCompleted } &&
                typeCheckerResult.isCompleted

    override val keys: Set<ObjectEngineResult.ObjectKey>
        get() = cellStore.keys

    override fun isCellSet(field: ObjectEngineResult.ObjectKey): Boolean = cellStore.isSet(field)

    override fun getCell(field: ObjectEngineResult.ObjectKey): EngineResultCell {
        validateObjectField(type, field)
        return cellStore.readOrNull(field)
            ?: throw missingResultCell(type, field)
    }

    override fun reserveCell(field: ObjectEngineResult.ObjectKey): EngineResultCell {
        validateObjectField(type, field)
        return cellStore.reserve(field)
    }

    override fun setCellValue(
        field: ObjectEngineResult.ObjectKey,
        value: EngineResult?,
    ): EngineResultCell {
        validateObjectField(type, field)
        validateObjectValue(field, value)
        return cellStore.setValue(field, value)
    }

    override fun freeze() {
        cellStore.freeze()
    }

    val completedCells: Map<ObjectEngineResult.ObjectKey, EngineResultCell>
        get() = cellStore.completedCells()

    fun requireCompleted() {
        typeCheckerResult.get()
        cellStore.cellEntries.forEach { (key, cell) ->
            val implementation = cell.implementation
            implementation.requireCompleted()
            if (implementation.isActivated && key !is ObjectEngineResult.ParentKey) {
                cell.completedValue.requireCompleted()
            }
        }
    }
}

private class ObjectCellStore(
    private val type: ViaductSchema.Object,
    cells: Map<ObjectEngineResult.ObjectKey, EngineResultCell>,
    private val mutable: Boolean,
) {
    private val lock = Any()
    private val cells = cells.toMutableMap()
    private var keySnapshot = this.cells.keys.toSet()
    private var frozen = !mutable

    val keys: Set<ObjectEngineResult.ObjectKey>
        get() = synchronized(lock) { keySnapshot }

    val cellEntries: List<Map.Entry<ObjectEngineResult.ObjectKey, EngineResultCell>>
        get() = synchronized(lock) { cells.toMap().entries.toList() }

    fun isSet(field: ObjectEngineResult.ObjectKey): Boolean = synchronized(lock) { field in cells }

    fun readOrNull(field: ObjectEngineResult.ObjectKey): EngineResultCell? = synchronized(lock) { cells[field] }

    fun reserve(field: ObjectEngineResult.ObjectKey): EngineResultCell =
        synchronized(lock) {
            cells[field]
                ?: if (frozen) {
                    throw missingResultCell(type, field)
                } else {
                    mutableCell(field).also { cell ->
                        cells[field] = cell
                        keySnapshot = cells.keys.toSet()
                    }
                }
        }

    fun setValue(
        field: ObjectEngineResult.ObjectKey,
        value: EngineResult?,
    ): EngineResultCell {
        var installed = false
        val cell =
            synchronized(lock) {
                cells[field]
                    ?: if (frozen) {
                        throw missingResultCell(type, field)
                    } else {
                        completedValueCell(field, value).also { created ->
                            cells[field] = created
                            keySnapshot = cells.keys.toSet()
                            installed = true
                        }
                    }
            }
        if (!installed) check(cell.value.set(value))
        return cell
    }

    fun freeze() {
        val presentCells =
            synchronized(lock) {
                check(mutable) { "${type.name} result is immutable" }
                check(!frozen) { "${type.name} result is already frozen" }
                frozen = true
                cells.toMap()
            }
        presentCells.forEach { (field, cell) ->
            cell.implementation.freezeValue {
                missingResultCell(type, field)
            }
        }
    }

    fun completedCells(): Map<ObjectEngineResult.ObjectKey, EngineResultCell> =
        synchronized(lock) {
            cells
                .onEach { (_, cell) -> cell.implementation.requireCompleted() }
                .filterValues { cell -> cell.implementation.isActivated }
        }

    private fun mutableCell(field: ObjectEngineResult.ObjectKey): EngineResultCell =
        CellImpl(
            mutable = true,
            validateValue = { value -> validateObjectValue(field, value) },
        )

    private fun completedValueCell(
        field: ObjectEngineResult.ObjectKey,
        value: EngineResult?,
    ): EngineResultCell =
        CellImpl(
            initialValue = value,
            initiallyValueSet = true,
            mutable = true,
            validateValue = { updated -> validateObjectValue(field, updated) },
        )
}

private fun missingResultCell(
    type: ViaductSchema.Object,
    field: ObjectEngineResult.ObjectKey,
): NoSuchElementException =
    NoSuchElementException(
        "Missing engine-result cell: ${type.name}.${field.field.name}",
    )

private class ListResultImpl(
    override val typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
    private val cells: List<EngineResultCell>,
) : ListEngineResult,
    List<EngineResultCell> by cells {
    override fun equals(other: Any?): Boolean =
        other is ListEngineResult &&
            typeExpr == other.typeExpr &&
            cells == other

    override fun hashCode(): Int = 31 * typeExpr.hashCode() + cells.hashCode()
}

private data class ListIndexImpl(
    override val index: Int,
) : ListEngineResult.Index

private data class KeyImpl(
    override val field: ViaductSchema.Field,
    override val arguments: Arguments,
) : ObjectEngineResult.Key

private data class ObjectKeyImpl(
    override val field: ViaductSchema.ObjectField,
    override val arguments: Arguments,
) : ObjectEngineResult.ObjectKey

private data class GroundKeyImpl(
    override val field: ViaductSchema.ObjectField,
    override val arguments: Arguments.Ground,
) : ObjectEngineResult.GroundKey

private data class ParentKeyImpl(
    override val field: ViaductSchema.ObjectField,
) : ObjectEngineResult.ParentKey {
    override val arguments: Arguments.Resolved = argumentsOfGround(emptyMap())

    init {
        require(field.isParentField()) {
            "Parent key field must carry @$PARENT_DIRECTIVE_NAME"
        }
    }
}

private data class CompletedCell(
    val value: EngineResult?,
    val fieldCheckerResult: CheckerResult?,
)

private fun EngineResultCell.completed(): CompletedCell =
    CompletedCell(
        value = completedValue,
        fieldCheckerResult = completedFieldCheckerResult,
    )

private val EngineResultCell.implementation: CellImpl
    get() = this as CellImpl

private val EngineResultCell.completedValue: EngineResult?
    get() = implementation.completedValue

private val EngineResultCell.completedFieldCheckerResult: CheckerResult?
    get() = implementation.completedFieldCheckerResult

private val ObjectEngineResult.implementation: ObjectResultImpl
    get() = this as ObjectResultImpl

/**
 * The result is meaningful only after both object trees are quiescent. Store snapshots and
 * recursive reads do not form one atomic snapshot while promises or cells are being mutated.
 */
private fun ObjectEngineResult.sameCompletedObjectResultAs(
    other: ObjectEngineResult,
    comparison: CompletedResultComparison,
): Boolean {
    val leftCells = implementation.completedCells
    val rightCells = other.implementation.completedCells
    if (
        type != other.type ||
        leftCells.size != rightCells.size ||
        !typeCheckerResult.get().hasSameCompletedCheckerResultAs(
            other.typeCheckerResult.get(),
        )
    ) {
        return false
    }

    val unmatchedRightCells = rightCells.entries.toMutableList()
    return leftCells.all { (leftKey, leftCell) ->
        val matchIndex =
            unmatchedRightCells.indexOfFirst { (rightKey, _) ->
                leftKey.hasSameRootRelativeStructureAs(rightKey)
            }
        if (matchIndex < 0) {
            false
        } else {
            val rightCell = unmatchedRightCells.removeAt(matchIndex).value
            if (leftKey is ObjectEngineResult.ParentKey) {
                comparison.sameParentCell(leftCell, rightCell)
            } else {
                comparison.sameCell(leftCell, rightCell)
            }
        }
    }
}

private fun EngineResult?.requireCompleted() {
    when (this) {
        null,
        is ErrorEngineResult,
        -> Unit
        is ListEngineResult ->
            indices.forEach { index ->
                val cell = get(index)
                cell.implementation.requireCompleted()
                cell.completedValue.requireCompleted()
            }
        is ObjectEngineResult -> implementation.requireCompleted()
        else -> check(isScalarResultMember()) { "Value is not an engine result: $this" }
    }
}

private fun <K, V> unionMaps(
    first: Map<K, V>,
    second: Map<K, V>,
    union: (V, V) -> V,
): Map<K, V> =
    (first.keys + second.keys).associateWith { key ->
        when {
            key !in first -> second.getValue(key)
            key !in second -> first.getValue(key)
            else -> union(first.getValue(key), second.getValue(key))
        }
    }

private fun unionCheckerResult(
    first: CheckerResult?,
    second: CheckerResult?,
): CheckerResult? =
    first.also {
        require(first === second) {
            "Cannot union cells with unequal checker results"
        }
    }

private class ActivationAwarePromise<T>(
    private val cell: EngineResultCell,
    private val delegate: Promise<T>,
) : Promise<T> {
    override val isCompleted: Boolean
        get() = delegate.isCompleted

    override suspend fun await(): T {
        cell.awaitActivated()
        return delegate.await()
    }

    override fun get(): T {
        cell.checkActivated()
        return delegate.get()
    }

    override fun complete(value: T): Boolean {
        cell.checkActivated()
        return delegate.complete(value)
    }

    override fun cancel(cause: CancellationException): Boolean {
        if (!cell.implementation.activateForCancellation()) return false
        return delegate.cancel(cause)
    }

    override fun fail(cause: Exception): Boolean {
        cell.checkActivated()
        return delegate.fail(cause)
    }

    fun failWithoutActivation(cause: Exception): Boolean = delegate.fail(cause)
}

private fun <T> activationAwarePromise(
    cell: EngineResultCell,
    promise: Promise<T>,
): ActivationAwarePromise<T> = ActivationAwarePromise(cell, promise)

private class ActivationAwareReservablePromise<T>(
    private val cell: CellImpl,
    private val delegate: ReservablePromise<T>,
) : ReservablePromise<T> {
    override val isClaimed: Boolean
        get() = delegate.isClaimed

    override val isCompleted: Boolean
        get() = delegate.isCompleted

    override suspend fun await(): T {
        cell.awaitActivated()
        return delegate.await()
    }

    override fun get(): T {
        cell.checkActivated()
        return delegate.get()
    }

    override fun claim(): ReservablePromise<T> {
        cell.checkMayWrite()
        delegate.claim()
        return this
    }

    override fun set(value: T): Boolean {
        cell.checkMayWrite()
        cell.activateForWrite()
        return delegate.set(value)
    }

    override fun complete(value: T): Boolean {
        cell.checkActivated()
        return delegate.complete(value)
    }

    override fun fail(cause: Exception): Boolean {
        cell.checkActivated()
        return delegate.fail(cause)
    }

    override fun cancel(cause: CancellationException): Boolean {
        if (!cell.activateForCancellation()) return false
        return delegate.cancel(cause)
    }

    override fun freeze(cause: () -> Exception): Exception? = delegate.freeze(cause)?.also(cell::failActivation)
}

private fun <T> activationAwareReservablePromise(
    cell: CellImpl,
    promise: ReservablePromise<T>,
): ReservablePromise<T> = ActivationAwareReservablePromise(cell, promise)

private fun validateObjectField(
    type: ViaductSchema.Object,
    field: ObjectEngineResult.ObjectKey,
): Unit =
    require(field.field.containingDef == type) {
        "${type.name} result contains a field owned by another type"
    }

private fun validateObjectValue(
    field: ObjectEngineResult.ObjectKey,
    value: EngineResult?,
) {
    if (field.arguments == Arguments.Error) {
        require(value is ErrorEngineResult) {
            "A key with erroneous arguments must contain an error value"
        }
    }
    require(value.conformsToResultSchemaType(field.field.outputType)) {
        "${field.field.containingDef.name}/${field.field.name} result does not conform to " +
            field.field.type
    }
}

private fun EngineResult.isScalarResultMember(): Boolean =
    this is Int ||
        this is Double && isFinite() ||
        this is Boolean ||
        this is String ||
        this is EngineIDResult ||
        this is ViaductSchema.EnumValue

private data class MutationKeyImpl(
    override val field: ViaductSchema.ObjectField,
    override val arguments: Arguments.Ground,
    override val responseKey: String,
) : ObjectEngineResult.MutationKey

/** Compares fields and arguments modulo occurrence-root identity, retaining mutation response keys. */
internal fun ObjectEngineResult.ObjectKey.hasSameRootRelativeStructureAs(other: ObjectEngineResult.ObjectKey): Boolean =
    if (this is ObjectEngineResult.MutationKey || other is ObjectEngineResult.MutationKey) {
        // Mutation arguments are grounded, so ordinary equality already has these semantics.
        this == other
    } else {
        field == other.field && arguments.hasSameRootRelativeStructureAs(other.arguments)
    }

internal fun mutationObjectEngineResultOf(selections: MutationSelectionForest): MutationObjectEngineResult = MutationObjectEngineResultImpl(selections.type)

private class MutationObjectEngineResultImpl(type: ViaductSchema.Object) :
    ObjectResultImpl(type, emptyMap(), Promise.of(null), mutable = true), MutationObjectEngineResult {
    override val keys: Set<ObjectEngineResult.MutationKey>
        get() = super.keys.mapTo(linkedSetOf()) { it as ObjectEngineResult.MutationKey }

    override fun reserveCell(field: ObjectEngineResult.ObjectKey): EngineResultCell {
        require(field is ObjectEngineResult.MutationKey) { "Mutation objects require mutation publication keys" }
        return super.reserveCell(field)
    }

    override fun setCellValue(
        field: ObjectEngineResult.ObjectKey,
        value: EngineResult?
    ): EngineResultCell {
        require(field is ObjectEngineResult.MutationKey) { "Mutation objects require mutation publication keys" }
        return super.setCellValue(field, value)
    }
}
