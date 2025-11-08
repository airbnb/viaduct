package viaduct.engine.runtime

import java.lang.IllegalArgumentException
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import viaduct.engine.api.ObjectEngineResult

/**
 * A thread-safe, lock-free data structure for memoizing field values during
 * GraphQL query execution, with support for concurrent reads and single-writer semantics.
 *
 * A "cell" consists of one or more "slots."  Each slot holds a Value<*>.
 * Reading the slots of a cell is straightforward:
 *
 * ```
 * val value = cell.fetch(0)
 * ```
 *
 * This code will read the value of slot 0, suspending until the value
 * is available.
 *
 * Cells are written by calling [computeIfAbsent]. This takes a lambda function that
 * is responsible for setting the slots of the cell:
 *
 * ```
 * val result = cell.computeIfAbsent { slotSetter ->
 *     slotSetter.set(0, Value.fromValue(10))
 *     slotSetter.set(1, Value.fromDeferred(async { delay(10); 20 }))
 * }
 * ```
 * As illustrated, this lambda function takes a "slot setter" which
 * gives it write access to the slots of a cell.  This block is
 * responsible for setting _all_ slots of the cell exactly once,
 * and an error is raised if it does not.
 *
 * Only one writer can set the value of a cell.  If another coroutine
 * has already written a cell, then [computeIfAbsent] does nothing.
 *
 * Implementation note: elements of [slots] are all either Value<*> when the
 * slot is set before it's read, or CompletableDeferred<Value<*>> when the
 * slot is read before it's claimed. Except the last element, which is set
 * to true once it's claimed.
 */
@JvmInline
value class Cell private constructor(private val slots: AtomicReferenceArray<Any?>) {
    companion object {
        internal const val MAX_SLOTS = 32

        /**
         * Creates a new cell with the given number of slots
         */
        fun create(slotCount: Int): Cell {
            if (slotCount < 1 || slotCount > MAX_SLOTS) {
                throw IllegalArgumentException("Cells must have 1 - $MAX_SLOTS slots.")
            }
            return Cell(AtomicReferenceArray<Any?>(slotCount + 1))
        }

        /**
         * Creates a new cell with its slots set using [block]
         */
        fun create(
            slotCount: Int,
            block: (SlotSetter) -> Unit
        ): Cell {
            return create(slotCount).also {
                it.computeIfUnclaimed("Newly created cell should be unclaimed", block)
            }
        }
    }

    /**
     * Returns the unwrapped value in slot number [slotNo], suspending until that value is ready.
     * If the value completed exceptionally or the block used to compute the value threw
     * an exception, this will throw.
     */
    suspend fun fetch(slotNo: Int): Any? {
        return getValue(slotNo).await()
    }

    /**
     * Returns the [Value] stored in slot number [slotNo]. If the slot contains a CompletableDeferred<Value<T>>,
     * because the slot was read before it was claimed, this will convert that to a Value<T>.
     */
    fun getValue(slotNo: Int): Value<*> {
        val result = maybeInitializeSlot(slotNo)
        return when (result) {
            is Value<*> -> result
            is Deferred<*> -> {
                Value.fromDeferred(result).flatMap { it as Value<*> }
            }
            else -> throw IllegalStateException("Unexpected type found in slot #$slotNo: $result, expected Value<*> or Deferred<Value<*>>")
        }
    }

    private fun maybeInitializeSlot(slotNo: Int): Any? {
        slots.assertWithinBounds(slotNo)
        slots.compareAndSet(slotNo, null, CompletableDeferred<Value<*>>())
        return slots.get(slotNo)
    }

    /**
     * If the cell hasn't previously been written, then call [block] which
     * is responsible for setting all the slots of the cell. If [block] fails
     * (e.g., it threw an exception, did not set all slots, or set a slot more
     * than once), then all slots will have an exceptional [Value]. This is
     * true even if some slots could have been successfully set.
     *
     * @returns this Cell
     * @throws IllegalStateException when [block] does not set all slots or
     *         sets a slot more than once.
     */
    fun computeIfAbsent(block: (SlotSetter) -> Unit): Cell {
        compute(block)
        return this
    }

    /**
     * Similar to [computeIfAbsent] except throws an exception if the
     * cell has already been claimed for writing.
     */
    internal fun computeIfUnclaimed(
        message: String,
        block: (SlotSetter) -> Unit
    ): Cell {
        check(compute(block)) { message }
        return this
    }

    /**
     * @return a boolean indicating whether the cell was successfully claimed and
     *         written using [block]
     */
    @Suppress("Detekt.TooGenericExceptionCaught")
    private fun compute(block: (SlotSetter) -> Unit): Boolean {
        if (slots.compareAndSet(slots.length() - 1, null, true)) {
            // Successfully claimed
            val setter = SlotSetterImpl(slots)
            try {
                block(setter)
                setter.assertAllSlotsSet()
            } catch (t: Exception) {
                val wrappedException = RuntimeException("Cell.compute block failed", t)
                setter.completeExceptionally(wrappedException)
                throw t
            }
            // The compute block executed successfully, now write all slots
            setter.complete()
            return true
        } else {
            // Already claimed
            return false
        }
    }

    /**
     * Implementation of [SlotSetter] with "all-or-nothing" semantics, meaning slots cannot be partially set.
     */
    private class SlotSetterImpl(private val slots: AtomicReferenceArray<Any?>) : SlotSetter {
        // Bit vector of slots that have been set
        private var setSlots: Int = 0

        // Used to temporarily hold slot values
        private val tempSlots = arrayOfNulls<Value<*>>(slots.length() - 1)

        /**
         * Collects slot values into [tempSlots] without actually writing them into the slots.
         * Slots must be written by calling either [complete] or [completeExceptionally].
         */
        override fun set(
            slotNo: Int,
            value: Value<*>
        ) {
            slots.assertWithinBounds(slotNo)
            val myBit = 1 shl slotNo
            if (setSlots and myBit != 0) {
                throw IllegalStateException("Slot $slotNo has been set more than once.")
            }
            setSlots = setSlots or myBit
            tempSlots[slotNo] = value
        }

        fun assertAllSlotsSet() {
            val mask = ((1L shl tempSlots.size) - 1).toInt()
            if ((setSlots and mask) != mask) {
                throw IllegalStateException("Not all ${tempSlots.size} slots are set. Set slots: ${setSlots.toString(2)}")
            }
        }

        /**
         * Completes all slots with the values held in [tempSlots].
         */
        fun complete() {
            for (slotNo in 0 until tempSlots.size) {
                val value = tempSlots[slotNo] as Value<*>
                completeSlot(slotNo, value)
            }
        }

        /**
         * Completes all slots with the exceptional value, including any previously set slots
         */
        fun completeExceptionally(t: Throwable) {
            val value = Value.fromThrowable<Nothing>(t)
            for (slotNo in 0 until tempSlots.size) {
                completeSlot(slotNo, value)
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun completeSlot(
            slotNo: Int,
            value: Value<*>
        ) {
            if (!slots.compareAndSet(slotNo, null, value)) {
                // A reader attempted to read this value before it was claimed
                (slots.get(slotNo) as CompletableDeferred<Value<*>>).complete(value)
            }
        }
    }
}

/**
 * Provides write-access to the slots in a cell.  Passed to
 * "computeIfAbsent" functions on [ObjectEngineResult]s and
 * under the covers [Cell]s to allow engine code to write to
 * the slots of a cell that has been freshly claimed for
 * writing.
 */
interface SlotSetter {
    /**
     * Slots are containers for [Value]s.
     * [set] allows you to set the values of those [Value]s.
     *
     * @throws IllegalStateException on an attempt to set a slot more than once.
     */
    fun set(
        slotNo: Int,
        value: Value<*>
    )
}

private fun AtomicReferenceArray<Any?>.assertWithinBounds(slotNo: Int) {
    val size = this.length() - 1
    if (slotNo < 0 || slotNo >= size) {
        throw IndexOutOfBoundsException("Invalid slotNo $slotNo provided for cell with $size slots")
    }
}
