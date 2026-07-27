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
 * @property tenantName Slash-separated tenant module name associated with this config source.
 * @property source Lazily-openable stream yielding the module's registry config JSON.
 */
data class ModuleConfigSource private constructor(
    val tenantName: String,
    val source: InputStreamSource,
) {
    companion object {
        private val objectMapper = jacksonObjectMapper()

        /**
         * Parses [source] just enough to extract its [ExecutionRegistryConfigFile.tenantName] and
         * pairs the two into a [ModuleConfigSource]. This is the single place that enforces the
         * "a config source must name its tenant" invariant for discovered sources.
         *
         * @throws IllegalArgumentException if the config JSON has no `tenantName`.
         */
        fun from(source: InputStreamSource): ModuleConfigSource {
            val tenantName = source.openStream().use { objectMapper.readValue<ExecutionRegistryConfigFile>(it).tenantName }
                ?: throw IllegalArgumentException("Execution registry config source must include tenantName: $source")
            return ModuleConfigSource(tenantName = tenantName, source = source)
        }
    }
}
