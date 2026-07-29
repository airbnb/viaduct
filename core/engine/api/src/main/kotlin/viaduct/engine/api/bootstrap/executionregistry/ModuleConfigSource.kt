package viaduct.engine.api.bootstrap.executionregistry

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import viaduct.service.api.spi.InputStreamSource

/**
 * Internal representation of a single tenant module's configuration input, prior to parsing.
 *
 * This is the unit of input for engine-owned, file-based bootstrapping. Each source pairs a
 * tenant name with a lazily-openable stream that yields the module's [ExecutionRegistryConfigFile]
 * JSON. The service layer (e.g. `StandardViaduct`) is responsible for discovering resource-backed
 * sources and for generating built-in sources via [ModuleConfigFactory]; the engine consumes the
 * resulting `List<ModuleConfigSource>` and parses each [source] into an
 * [ExecutionRegistryConfigFile].
 *
 * Unlike [ExecutionRegistryConfigFile.tenantName], which is nullable because it is deserialized
 * from JSON, [tenantName] here is required: a source without a tenant name cannot be bootstrapped.
 *
 * The primary constructor is private so that [tenantName] can only ever come from the [source]
 * itself: instances are created via [from], which reads the name out of the config JSON. This makes
 * it impossible to pair a [source] with a [tenantName] that disagrees with the JSON it contains.
 *
 * A single tenant may contribute more than one config source that share a [tenantName] — e.g. a
 * modern `<pkg>.json` and a classic `<pkg>.classic.json` naming different executor factories. Such
 * sources are distinguished by [executorFactory] so hotswap merging (see
 * `ViaductExecutionRegistryConfigSources.merged`) does not collapse them into one.
 *
 * @property tenantName Slash-separated tenant module name associated with this config source.
 * @property source Lazily-openable stream yielding the module's registry config JSON.
 * @property executorFactory FQN of the [ExecutionRegistryConfigFile.executorFactory] this source
 *   declares, when known. Used together with [tenantName] to identify a source during merge dedup.
 */
data class ModuleConfigSource private constructor(
    val tenantName: String,
    val source: InputStreamSource,
    val executorFactory: String? = null,
) {
    companion object {
        private val objectMapper = jacksonObjectMapper()

        /**
         * Parses [source] just enough to extract its [ExecutionRegistryConfigFile.tenantName] and
         * [ExecutionRegistryConfigFile.executorFactory], pairing them into a [ModuleConfigSource].
         * This is the single place that enforces the "a config source must name its tenant"
         * invariant for discovered sources.
         *
         * @throws IllegalArgumentException if the config JSON has no `tenantName`.
         */
        fun from(source: InputStreamSource): ModuleConfigSource {
            val config = source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it) }
            val tenantName = config.tenantName
                ?: throw IllegalArgumentException("Execution registry config source must include tenantName: $source")
            return ModuleConfigSource(
                tenantName = tenantName,
                source = source,
                executorFactory = config.executorFactory,
            )
        }
    }
}
