@file:Suppress("DEPRECATION")

package viaduct.engine.runtime.mat

import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.api.spi.MaterializedFieldValueReader.ReadResult
import viaduct.engine.runtime.result.ObjectEngineResult

/** Reads exact field keys prepared for one Mat-backed object traversal. */
internal sealed interface LedgerReader {
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
        private val rootNodeId: String?,
        private val fieldValueReader: MaterializedFieldValueReader,
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
            val source = when (val resolved = ledger.resolveSource(path, key)) {
                MatLedger.Source.Missing -> return ReadResult(value = null, fieldIsMissing = true)
                is MatLedger.Source.Resolved ->
                    resolved.data
                        ?: return ReadResult(value = null, fieldIsMissing = false)
            }
            return fieldValueReader.read(source, key.name, key.responseKey)
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
            fieldValueReader: MaterializedFieldValueReader,
            rootNodeId: String? = null,
        ): LedgerReader = Impl(ledger, path, requestedShape, rootNodeId, fieldValueReader)

        /** Creates a reader that reports [error] from every attempted field read. */
        fun failed(error: Throwable): LedgerReader = Failed(error)
    }
}
