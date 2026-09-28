package model

import kotlinx.coroutines.CancellationException
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

import java.util.IdentityHashMap

import model.invariants.conformsToResultSchemaType

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
internal fun List<PathComponent>?.toSelectionPath():
    List<ObjectEngineResult.ObjectKey>? =
    this?.map { component -> component as? ObjectEngineResult.ObjectKey ?: return null }

/**
 * One result occurrence with one write-once activation decision and independent write-once value
 * and field- and type-checker-result slots.
 *
 * Pending cells permit any slot's promise to be reserved, but those promises cannot be read or
 * completed until the cell is activated. A negative activation decision permanently prohibits all
 * slots. Direct slot setters activate a pending cell before completing it. The value slot contains
 * [EngineResult] or GraphQL null. Each checker-result slot contains [CheckerResult] or null, where
 * null means that no checker applies to that slot. Cells use reference equality and stable identity
 * hashing because their activation or any slot may be completed after publication.
 */
sealed interface EngineResultCell {
    /** Whether activation, the value slot, and every claimed checker-result slot have completed. */
    val isCompleted: Boolean

    /** Atomically completes this cell's activation decision and reports whether this call won. */
    fun setActivated(activated: Boolean): Boolean

    /** Suspends until activation is decided and throws when this cell is not activated. */
    suspend fun awaitActivated()

    /** Suspends until activation is decided and returns that decision. */
    suspend fun fetchActivated(): Boolean

    /** Throws unless this cell has already been activated. */
    fun checkActivated()

    /** @throws IllegalStateException when this cell has no value promise */
    fun getValue(): Promise<EngineResult?>

    /**
     * Returns the value promise, explicitly creating an unclaimed reader placeholder when this
     * mutable cell has no promise.
     */
    fun reserveValue(): Promise<EngineResult?>

    /** Claims and atomically completes the value slot, reporting whether completion succeeded. */
    fun setValue(value: EngineResult?): Boolean

    fun createValuePromise(): Promise<EngineResult?>

    /** Positively activates and atomically cancels the claimed value, reporting whether this call won. */
    fun cancelValue(cause: CancellationException): Boolean

    /** @throws IllegalStateException when this cell has no field-checker-result promise */
    fun getFieldCheckerResult(): Promise<CheckerResult?>

    /** Returns whether this cell has a field-checker-result promise. */
    fun isFieldCheckerResultSet(): Boolean

    fun setFieldCheckerResult(result: CheckerResult?)

    fun createFieldCheckerResultPromise(): Promise<CheckerResult?>

    /** Atomically completes the claimed field-checker result exceptionally. */
    fun failFieldCheckerResult(cause: Exception): Boolean

    /** Positively activates and atomically cancels the field-checker result. */
    fun cancelFieldCheckerResult(cause: CancellationException): Boolean

    /** @throws IllegalStateException when this cell has no type-checker-result promise */
    fun getTypeCheckerResult(): Promise<CheckerResult?>

    /** Returns whether this cell has a type-checker-result promise. */
    fun isTypeCheckerResultSet(): Boolean

    fun setTypeCheckerResult(result: CheckerResult?)

    fun createTypeCheckerResultPromise(): Promise<CheckerResult?>

    /** Positively activates and atomically cancels the type-checker result. */
    fun cancelTypeCheckerResult(cause: CancellationException): Boolean
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
 * claims the value placeholder through [EngineResultCell.createValuePromise] or
 * [EngineResultCell.setValue]. [freeze] seals the key set and freezes every present cell's value
 * slot. A claimed value promise may complete after freezing.
 *
 * Objects use reference equality and stable identity hashing, so they may be used as map keys while
 * cells are installed or their slots are completed.
 */
sealed interface ObjectEngineResult {
    /** Whether every present cell and the claimed type-checker-result slot have completed. */
    val isCompleted: Boolean

    /** @throws IllegalStateException when this object has no type-checker-result promise */
    fun getTypeCheckerResult(): Promise<CheckerResult?>

    /** Returns whether this object has a type-checker-result promise. */
    fun isTypeCheckerResultSet(): Boolean

    /** Claims and completes this object's type-checker result immediately. */
    fun setTypeCheckerResult(result: CheckerResult?)

    /** Claims this object's type-checker result for deferred completion. */
    fun createTypeCheckerResultPromise(): Promise<CheckerResult?>

    /** Atomically completes the claimed type-checker result exceptionally. */
    fun failTypeCheckerResult(cause: Exception): Boolean

    /** Atomically cancels the claimed type-checker result. */
    fun cancelTypeCheckerResult(cause: CancellationException): Boolean

    /**
     * One alias-free output-field coordinate consisting of a canonical field and its arguments.
     *
     * ### Invariant: key-argument-definition
     *
     * [arguments] recursively conforms to [field]'s argument definition.
     *
     * ### Invariant: object-key-field-classification
     *
     * A key's [field] is a [ViaductSchema.ObjectField] exactly when the key is an [ObjectKey].
     *
     * Key equality is structural over [field] and [arguments], using canonical schema equality.
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

    /**
     * A no-argument engine-provided field whose value references the containing object's parent.
     *
     * Parent keys remain ordinary selection and OER lookup keys. The refinement lets structural
     * result algorithms avoid recursively unfolding their ancestor backedges.
     */
    sealed interface ParentKey : GroundKey {
        override val arguments: Arguments.Resolved

        companion object {
            fun of(field: ViaductSchema.ObjectField): ParentKey =
                of(field, argumentsOfGround(emptyMap()))

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
         * [values] default both checker results to completed nulls, meaning no checker applies.
         */
        fun of(
            type: ViaductSchema.Object,
            values: Map<ObjectKey, EngineResult?> = emptyMap(),
            fieldCheckerResults: Map<ObjectKey, CheckerResult?> =
                values.keys.associateWith { null },
            typeCheckerResults: Map<ObjectKey, CheckerResult?> =
                values.keys.associateWith { null },
            mutable: Boolean = false,
        ): ObjectEngineResult =
            objectEngineResultOf(
                type = type,
                values = values,
                fieldCheckerResults = fieldCheckerResults,
                typeCheckerResults = typeCheckerResults,
                completedTypeCheckerResult = CompletedCheckerSlot(isSet = false, value = null),
                mutable = mutable,
            )

        /** Constructs an object with an initially completed OER-owned type-checker result. */
        fun of(
            type: ViaductSchema.Object,
            typeCheckerResult: CheckerResult?,
            values: Map<ObjectKey, EngineResult?> = emptyMap(),
            fieldCheckerResults: Map<ObjectKey, CheckerResult?> =
                values.keys.associateWith { null },
            typeCheckerResults: Map<ObjectKey, CheckerResult?> =
                values.keys.associateWith { null },
            mutable: Boolean = false,
        ): ObjectEngineResult =
            objectEngineResultOf(
                type = type,
                values = values,
                fieldCheckerResults = fieldCheckerResults,
                typeCheckerResults = typeCheckerResults,
                completedTypeCheckerResult =
                    CompletedCheckerSlot(isSet = true, value = typeCheckerResult),
                mutable = mutable,
            )
    }
}

private fun objectEngineResultOf(
    type: ViaductSchema.Object,
    values: Map<ObjectEngineResult.ObjectKey, EngineResult?>,
    fieldCheckerResults: Map<ObjectEngineResult.ObjectKey, CheckerResult?>,
    typeCheckerResults: Map<ObjectEngineResult.ObjectKey, CheckerResult?>,
    completedTypeCheckerResult: CompletedCheckerSlot,
    mutable: Boolean,
): ObjectEngineResult {
    val fields = values.keys + fieldCheckerResults.keys + typeCheckerResults.keys
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
                    initiallyFieldCheckerResultSet = field in fieldCheckerResults,
                    typeCheckerResult = typeCheckerResults[field],
                    initiallyTypeCheckerResultSet = field in typeCheckerResults,
                    mutable = mutable,
                    validateValue = { value -> validateObjectValue(field, value) },
                )
            },
        completedTypeCheckerResult = completedTypeCheckerResult,
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
         * writable value promise. Their checker-result slots remain unclaimed for future checker
         * resolution.
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
                        mutable = true,
                        validateValue = { value -> validateListValue(typeExpr, value) },
                    ).also { cell -> cell.reserveValue() }
                }
            return ListResultImpl(typeExpr, cells)
        }

        /**
         * ### Invariant: list-engine-result-factory-schema-conformance
         *
         * Every cell value satisfies `value.conformsToResultSchemaType(typeExpr)` in its reasoning
         * world. Omitted checker-result lists default to completed nulls, meaning no checker applies.
         */
        fun of(
            typeExpr: ViaductSchema.TypeExpr<ViaductSchema.OutputTypeDef>,
            values: List<EngineResult?>,
            fieldCheckerResults: List<CheckerResult?> =
                values.map { null },
            typeCheckerResults: List<CheckerResult?> =
                values.map { null },
            mutableCells: Boolean = false,
        ): ListEngineResult {
            require(values.all { value -> value.conformsToResultSchemaType(typeExpr) }) {
                "List engine result contains an element incompatible with $typeExpr"
            }
            require(fieldCheckerResults.size == values.size) {
                "List engine result field-checker results must match its value count"
            }
            require(typeCheckerResults.size == values.size) {
                "List engine result type-checker results must match its value count"
            }
            val cells =
                values.mapIndexed { index, value ->
                    CellImpl(
                        initialValue = value,
                        initiallyValueSet = true,
                        fieldCheckerResult = fieldCheckerResults[index],
                        initiallyFieldCheckerResultSet = true,
                        typeCheckerResult = typeCheckerResults[index],
                        initiallyTypeCheckerResultSet = true,
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
 * Unpublished checker slots differ from completed null slots. Two completed checker errors compare
 * extensionally as errors without invoking their externally supplied equality.
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
            left.completedFieldCheckerResult.hasSameCompletedCheckerSlotAs(
                right.completedFieldCheckerResult,
            ) &&
            left.completedTypeCheckerResult.hasSameCompletedCheckerSlotAs(
                right.completedTypeCheckerResult,
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
            left.completedFieldCheckerResult.hasSameCompletedCheckerSlotAs(
                right.completedFieldCheckerResult,
            ) &&
            left.completedTypeCheckerResult.hasSameCompletedCheckerSlotAs(
                right.completedTypeCheckerResult,
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

private fun CompletedCheckerSlot.hasSameCompletedCheckerSlotAs(
    other: CompletedCheckerSlot,
): Boolean =
    when {
        !isSet || !other.isSet -> isSet == other.isSet
        value is CheckerResult.Error -> other.value is CheckerResult.Error
        value === CheckerResult.Success -> other.value === CheckerResult.Success
        else -> other.value == null
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
                        cell.getValue().get()?.containsParentBackedge() == true
                }
        is ListEngineResult ->
            any { cell -> cell.getValue().get()?.containsParentBackedge() == true }
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
        typeCheckerResult = unionCheckerResult(typeCheckerResult, other.typeCheckerResult),
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
            implementation.completedTypeCheckerResult,
            other.implementation.completedTypeCheckerResult,
        )
    return objectEngineResultOf(
        type = type,
        values = cells.mapValues { (_, cell) -> cell.value },
        fieldCheckerResults =
            cells
                .filterValues { cell -> cell.fieldCheckerResult.isSet }
                .mapValues { (_, cell) -> cell.fieldCheckerResult.value },
        typeCheckerResults =
            cells
                .filterValues { cell -> cell.typeCheckerResult.isSet }
                .mapValues { (_, cell) -> cell.typeCheckerResult.value },
        completedTypeCheckerResult = oerTypeCheckerResult,
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
                    fieldCheckerResult = cell.fieldCheckerResult.value,
                    initiallyFieldCheckerResultSet = cell.fieldCheckerResult.isSet,
                    typeCheckerResult = cell.typeCheckerResult.value,
                    initiallyTypeCheckerResultSet = cell.typeCheckerResult.isSet,
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
    typeCheckerResult: CheckerResult? = null,
    initiallyTypeCheckerResultSet: Boolean = false,
    private val mutable: Boolean,
    private val validateValue: (EngineResult?) -> Unit = {},
) : EngineResultCell {
    private val activationLock = Any()
    private val activation: Promise<Boolean> =
        if (
            initiallyActivated || initiallyValueSet ||
            initiallyFieldCheckerResultSet ||
            initiallyTypeCheckerResultSet
        ) {
            Promise.of(true)
        } else {
            Promise.ofDeferred()
        }
    private val valueStore =
        CellValueStore(
            cell = this,
            initialValue = initialValue,
            initiallySet = initiallyValueSet,
            mutable = mutable,
            validateValue = validateValue,
        )
    private val fieldCheckerResultStore =
        promiseStore(
            values =
                if (initiallyFieldCheckerResultSet) {
                    mapOf(Unit to fieldCheckerResult)
                } else {
                    emptyMap()
                },
            cell = this,
        )
    private val typeCheckerResultStore =
        promiseStore(
            values =
                if (initiallyTypeCheckerResultSet) {
                    mapOf(Unit to typeCheckerResult)
                } else {
                    emptyMap()
                },
            cell = this,
        )

    override val isCompleted: Boolean
        get() =
            activation.isCompleted &&
                valueStore.readOrNull()?.isCompleted == true &&
                fieldCheckerResultStore.snapshot().values.all(Promise<CheckerResult?>::isCompleted) &&
                typeCheckerResultStore.snapshot().values.all(Promise<CheckerResult?>::isCompleted)

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

    override fun getValue(): Promise<EngineResult?> =
        checkNotNull(valueStore.readOrNull()) {
            "Cell has no value"
        }

    override fun reserveValue(): Promise<EngineResult?> = valueStore.reserve()

    override fun setValue(value: EngineResult?): Boolean {
        checkMayWrite()
        validateValue(value)
        activateForWrite()
        return valueStore.claimAndComplete(value)
    }

    override fun createValuePromise(): Promise<EngineResult?> = valueStore.claim()

    override fun cancelValue(cause: CancellationException): Boolean {
        checkMutable()
        val mayCancel =
            synchronized(activationLock) {
                activation.complete(true)
                activation.get()
            }
        if (!mayCancel) return false
        return valueStore.cancelClaimed(cause)
    }

    override fun getFieldCheckerResult(): Promise<CheckerResult?> =
        checkNotNull(fieldCheckerResultStore.readOrNull(Unit)) {
            "Cell has no field-checker result"
        }

    override fun isFieldCheckerResultSet(): Boolean = fieldCheckerResultStore.isSet(Unit)

    override fun setFieldCheckerResult(result: CheckerResult?) {
        checkMayWrite()
        activateForWrite()
        fieldCheckerResultStore.set(Unit, result, this)
    }

    override fun createFieldCheckerResultPromise(): Promise<CheckerResult?> {
        checkMutable()
        return fieldCheckerResultStore.create(Unit, this)
    }

    override fun failFieldCheckerResult(cause: Exception): Boolean {
        checkMutable()
        return checkNotNull(fieldCheckerResultStore.readOrNull(Unit)) {
            "Cell has no field-checker-result writer"
        }.completeExceptionally(cause)
    }

    override fun cancelFieldCheckerResult(cause: CancellationException): Boolean =
        cancelCheckerResult(fieldCheckerResultStore, cause)

    override fun getTypeCheckerResult(): Promise<CheckerResult?> =
        checkNotNull(typeCheckerResultStore.readOrNull(Unit)) {
            "Cell has no type-checker result"
        }

    override fun isTypeCheckerResultSet(): Boolean = typeCheckerResultStore.isSet(Unit)

    override fun setTypeCheckerResult(result: CheckerResult?) {
        checkMayWrite()
        activateForWrite()
        typeCheckerResultStore.set(Unit, result, this)
    }

    override fun createTypeCheckerResultPromise(): Promise<CheckerResult?> {
        checkMutable()
        return typeCheckerResultStore.create(Unit, this)
    }

    override fun cancelTypeCheckerResult(cause: CancellationException): Boolean =
        cancelCheckerResult(typeCheckerResultStore, cause)

    private fun cancelCheckerResult(
        store: OnceStore<Unit, Promise<CheckerResult?>>,
        cause: CancellationException,
    ): Boolean {
        checkMutable()
        val mayCancel =
            synchronized(activationLock) {
                activation.complete(true)
                activation.get()
            }
        if (!mayCancel) return false
        return checkNotNull(store.readOrNull(Unit)) {
            "Cell has no checker-result writer"
        }.cancel(cause)
    }

    inline fun freezeValue(cause: () -> Exception) {
        if (mutable) {
            valueStore.freeze(cause)?.let { failure ->
                synchronized(activationLock) {
                    activation.completeExceptionally(failure)
                }
            }
        }
    }

    fun requireCompleted() {
        val activated = activation.get()
        if (!activated) return
        valueStore.readOrNull()?.get()
        fieldCheckerResultStore.snapshot().values.forEach { promise -> promise.get() }
        typeCheckerResultStore.snapshot().values.forEach { promise -> promise.get() }
    }

    val isActivated: Boolean
        get() = activation.get()

    val completedValue: EngineResult?
        get() = checkNotNull(valueStore.readOrNull()) { "Cell has no value" }.get()

    val completedFieldCheckerResult: CompletedCheckerSlot
        get() = fieldCheckerResultStore.completedCheckerSlot()

    val completedTypeCheckerResult: CompletedCheckerSlot
        get() = typeCheckerResultStore.completedCheckerSlot()

    private fun checkMutable() = check(mutable) { "Cell is immutable" }

    private fun checkMayWrite() {
        checkMutable()
        synchronized(activationLock) {
            check(!activation.isCompleted || activation.get()) { "Cell was not activated" }
        }
    }

    private fun activateForWrite() {
        checkMutable()
        synchronized(activationLock) {
            activation.complete(true)
            check(activation.get()) { "Cell was not activated" }
        }
    }
}

private class CellValueStore(
    private val cell: EngineResultCell,
    initialValue: EngineResult?,
    initiallySet: Boolean,
    private val mutable: Boolean,
    private val validateValue: (EngineResult?) -> Unit,
) {
    private val lock = Any()
    private var promise: ActivationAwarePromise<EngineResult?>? =
        if (initiallySet) activationAwarePromise(cell, Promise.of(initialValue)) else null
    private var claimed = initiallySet
    private var frozen = !mutable

    val isSet: Boolean
        get() = synchronized(lock) { promise != null }

    fun readOrNull(): Promise<EngineResult?>? = synchronized(lock) { promise }

    fun reserve(): Promise<EngineResult?> =
        synchronized(lock) {
            promise
                ?: if (frozen) {
                    error("Cell is immutable")
                } else {
                    Promise
                        .ofDeferred(validateValue)
                        .let { promise -> activationAwarePromise(cell, promise) }
                        .also { created -> promise = created }
                }
        }

    fun claim(): Promise<EngineResult?> =
        synchronized(lock) {
            check(!frozen) { "Cell value is frozen" }
            val existing = promise
            if (existing != null) {
                check(!claimed) { "Cell value already has a writer" }
                claimed = true
                existing
            } else {
                Promise
                    .ofDeferred(validateValue)
                    .let { promise -> activationAwarePromise(cell, promise) }
                    .also { created ->
                        promise = created
                        claimed = true
                    }
            }
        }

    fun claimAndComplete(value: EngineResult?): Boolean = claim().complete(value)

    fun cancelClaimed(cause: CancellationException): Boolean {
        val claimedPromise =
            synchronized(lock) {
                check(claimed) { "Cell value has no writer" }
                checkNotNull(promise) { "Cell has no value promise" }
            }
        return claimedPromise.cancel(cause)
    }

    inline fun freeze(cause: () -> Exception): Exception? {
        val unclaimed =
            synchronized(lock) {
                check(mutable) { "Cell is immutable" }
                check(!frozen) { "Cell value is already frozen" }
                frozen = true
                promise?.takeUnless { claimed }
            }
        return unclaimed?.let { promise ->
            cause().also { failure ->
                check(promise.completeExceptionallyWithoutActivation(failure))
            }
        }
    }
}

private class ObjectResultImpl(
    override val type: ViaductSchema.Object,
    cells: Map<ObjectEngineResult.ObjectKey, EngineResultCell>,
    completedTypeCheckerResult: CompletedCheckerSlot,
    private val mutable: Boolean,
) : ObjectEngineResult {
    private val cellStore =
        ObjectCellStore(
            type = type,
            cells = cells,
            mutable = mutable,
        )
    private val typeCheckerResultStore =
        OnceStore<Unit, Promise<CheckerResult?>>(
            if (completedTypeCheckerResult.isSet) {
                mapOf(Unit to Promise.of(completedTypeCheckerResult.value))
            } else {
                emptyMap()
            },
        )

    override val isCompleted: Boolean
        get() =
            cellStore.cellEntries.all { (_, cell) -> cell.isCompleted } &&
                typeCheckerResultStore
                    .snapshot()
                    .values
                    .all(Promise<CheckerResult?>::isCompleted)

    override fun getTypeCheckerResult(): Promise<CheckerResult?> =
        checkNotNull(typeCheckerResultStore.readOrNull(Unit)) {
            "Object has no type-checker result"
        }

    override fun isTypeCheckerResultSet(): Boolean = typeCheckerResultStore.isSet(Unit)

    override fun setTypeCheckerResult(result: CheckerResult?) {
        checkMutable()
        typeCheckerResultStore.write(Unit, Promise.of(result))
    }

    override fun createTypeCheckerResultPromise(): Promise<CheckerResult?> {
        checkMutable()
        return Promise.ofDeferred<CheckerResult?>().also { promise ->
            typeCheckerResultStore.write(Unit, promise)
        }
    }

    override fun failTypeCheckerResult(cause: Exception): Boolean {
        checkMutable()
        return getTypeCheckerResult().completeExceptionally(cause)
    }

    override fun cancelTypeCheckerResult(cause: CancellationException): Boolean {
        checkMutable()
        return getTypeCheckerResult().cancel(cause)
    }

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
        typeCheckerResultStore.snapshot().values.forEach { promise -> promise.get() }
        cellStore.cellEntries.forEach { (key, cell) ->
            val implementation = cell.implementation
            implementation.requireCompleted()
            if (implementation.isActivated && key !is ObjectEngineResult.ParentKey) {
                cell.completedValue.requireCompleted()
            }
        }
    }

    val completedTypeCheckerResult: CompletedCheckerSlot
        get() = typeCheckerResultStore.completedCheckerSlot()

    private fun checkMutable() = check(mutable) { "${type.name} result is immutable" }
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

    fun readOrNull(field: ObjectEngineResult.ObjectKey): EngineResultCell? =
        synchronized(lock) { cells[field] }

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
        if (!installed) check(cell.setValue(value))
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
    val fieldCheckerResult: CompletedCheckerSlot,
    val typeCheckerResult: CompletedCheckerSlot,
)

private data class CompletedCheckerSlot(
    val isSet: Boolean,
    val value: CheckerResult?,
)

private fun EngineResultCell.completed(): CompletedCell =
    CompletedCell(
        value = completedValue,
        fieldCheckerResult = completedFieldCheckerResult,
        typeCheckerResult = completedTypeCheckerResult,
    )

private val EngineResultCell.implementation: CellImpl
    get() = this as CellImpl

private val EngineResultCell.completedValue: EngineResult?
    get() = implementation.completedValue

private val EngineResultCell.completedFieldCheckerResult: CompletedCheckerSlot
    get() = implementation.completedFieldCheckerResult

private val EngineResultCell.completedTypeCheckerResult: CompletedCheckerSlot
    get() = implementation.completedTypeCheckerResult

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
        !implementation.completedTypeCheckerResult.hasSameCompletedCheckerSlotAs(
            other.implementation.completedTypeCheckerResult,
        )
    ) {
        return false
    }

    val unmatchedRightCells = rightCells.entries.toMutableList()
    return leftCells.all { (leftKey, leftCell) ->
        val matchIndex =
            unmatchedRightCells.indexOfFirst { (rightKey, _) ->
                leftKey.field == rightKey.field &&
                    leftKey.arguments.hasSameRootRelativeStructureAs(rightKey.arguments)
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
    first: CompletedCheckerSlot,
    second: CompletedCheckerSlot,
): CompletedCheckerSlot =
    when {
        !first.isSet -> second
        !second.isSet -> first
        else ->
            first.also {
                require(first.value === second.value) {
                    "Cannot union cells with unequal checker results"
                }
            }
    }

private class ActivationAwarePromise<T>(
    private val cell: EngineResultCell,
    private val delegate: Promise<T>,
) : Promise<T>, ExceptionallyCompletablePromise {
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
        cell.checkActivated()
        return delegate.cancel(cause)
    }

    override fun completeExceptionally(cause: Exception): Boolean {
        cell.checkActivated()
        return delegate.completeExceptionally(cause)
    }

    fun completeExceptionallyWithoutActivation(cause: Exception): Boolean =
        delegate.completeExceptionally(cause)
}

private fun <T> activationAwarePromise(
    cell: EngineResultCell,
    promise: Promise<T>,
): ActivationAwarePromise<T> = ActivationAwarePromise(cell, promise)

private fun <K : Any, V> promiseStore(
    values: Map<K, V>,
    cell: EngineResultCell,
): OnceStore<K, Promise<V>> =
    OnceStore(
        values.mapValues { (_, value) ->
            activationAwarePromise(cell, Promise.of(value))
        },
    )

private fun <K : Any, V> OnceStore<K, Promise<V>>.readOrNull(key: K): Promise<V>? =
    if (isSet(key)) read(key) else null

private fun OnceStore<Unit, Promise<CheckerResult?>>.completedCheckerSlot():
    CompletedCheckerSlot =
    readOrNull(Unit)?.let { promise ->
        CompletedCheckerSlot(isSet = true, value = promise.get())
    } ?: CompletedCheckerSlot(isSet = false, value = null)

private fun <K : Any, V> OnceStore<K, Promise<V>>.set(
    key: K,
    value: V,
    cell: EngineResultCell,
) = write(key, activationAwarePromise(cell, Promise.of(value)))

private fun <K : Any, V> OnceStore<K, Promise<V>>.create(
    key: K,
    cell: EngineResultCell,
    validate: (V) -> Unit = {},
): Promise<V> =
    Promise
        .ofDeferred(validate)
        .let { promise -> activationAwarePromise(cell, promise) }
        .also { write(key, it) }

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
