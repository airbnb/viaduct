package viaduct.engine.runtime2.arbitrary

/**
 * Duplicate-preserving diagnostic storage shared by Qplan's generators and resolver tests.
 * Snapshots copy the list, not its entries. Clear only after the preceding run completes.
 * Pauses support sequential nesting; overlapping pause blocks on different threads are unsupported.
 */
class BoundedRecorder<Entry>(
    private val maxEntries: Int = ResolutionWitnessBounds().maxApplications,
    private val boundName: String = "application",
) {
    init {
        require(maxEntries > 0)
    }

    private val lock = Any()
    private val entries = mutableListOf<Entry>()

    @Volatile
    var isRecording: Boolean = true
        private set

    fun record(entry: Entry) {
        synchronized(lock) {
            if (!isRecording) return
            if (entries.size >= maxEntries) throw ResolutionWitnessBoundExceededException(boundName, maxEntries)
            entries += entry
        }
    }

    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    fun <R> withoutRecording(block: () -> R): R {
        val previous = synchronized(lock) { isRecording.also { isRecording = false } }
        return try {
            block()
        } finally {
            synchronized(lock) { isRecording = previous }
        }
    }
}
