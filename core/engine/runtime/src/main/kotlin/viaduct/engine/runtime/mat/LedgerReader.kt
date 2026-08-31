package viaduct.engine.runtime.mat

import kotlinx.coroutines.CancellationException
import viaduct.engine.runtime.result.ObjectEngineResult

/** Reads exact field keys prepared for one Mat-backed object traversal. */
internal sealed interface LedgerReader {
    data class ReadResult(
        val value: Any?,
        val fieldIsMissing: Boolean,
    )

    fun canFetch(key: ObjectEngineResult.Key): Boolean

    suspend fun read(key: ObjectEngineResult.Key): ReadResult

    suspend fun fetchOrNull(key: ObjectEngineResult.Key): Any? = read(key).value

    /**
     * Reads fields from the materialization in [ledger] that covers each exact key.
     *
     * For example, the key for `displayName: name` selects the matching materialization and reads
     * `name` from the object returned by that materialization.
     */
    private class Impl(
        private val ledger: MatLedger,
        private val path: MatPath,
        requestedShape: KeyTree,
        private val rootNodeId: String? = null,
    ) : LedgerReader {
        private val fetchableKeys =
            requestedShape
                .subtreeAt(path)
                .keysByType()[path.terminalType]
                ?.keys
                .orEmpty()

        override fun canFetch(key: ObjectEngineResult.Key): Boolean = intrinsicRootNodeId(key) != null || key in fetchableKeys

        override suspend fun read(key: ObjectEngineResult.Key): ReadResult {
            intrinsicRootNodeId(key)?.let { return ReadResult(it, fieldIsMissing = false) }
            val source = ledger.resolveSource(path, key)
                ?: return ReadResult(value = null, fieldIsMissing = false)
            val value = source.fetchOrNull(key.name)
            if (value != null) {
                return ReadResult(value, fieldIsMissing = false)
            }
            val fieldIsMissing =
                try {
                    source.fetchSelections().none { it == key.name }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            return ReadResult(value = null, fieldIsMissing = fieldIsMissing)
        }

        private fun intrinsicRootNodeId(key: ObjectEngineResult.Key): String? =
            rootNodeId?.takeIf {
                key.name == "id" && path.segments.isEmpty()
            }
    }

    private class Failed(private val error: Throwable) : LedgerReader {
        override fun canFetch(key: ObjectEngineResult.Key): Boolean = true

        override suspend fun read(key: ObjectEngineResult.Key): Nothing = throw error
    }

    companion object {
        /**
         * Creates a reader backed by [ledger].
         *
         * @param path identifies the object being read.
         * @param requestedShape identifies the fields owned by the ledger.
         * @param rootNodeId is the intrinsic id of the ledger root when it represents a node reference.
         */
        operator fun invoke(
            ledger: MatLedger,
            path: MatPath,
            requestedShape: KeyTree,
            rootNodeId: String? = null,
        ): LedgerReader = Impl(ledger, path, requestedShape, rootNodeId)

        /** Creates a reader that reports [error] from every attempted field read. */
        fun failed(error: Throwable): LedgerReader = Failed(error)
    }
}
