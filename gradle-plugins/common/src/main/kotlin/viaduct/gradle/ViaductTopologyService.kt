package viaduct.gradle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import viaduct.apiannotations.InternalApi

@InternalApi
data class ViaductApplicationMap(
    val applicationTopologies: Map<String, ViaductApplicationTopology>,
)

@InternalApi
data class ViaductApplicationTopology(
    val applicationProjectPath: String,
    val modulePackagePrefix: String,
    val modulePackageSuffixes: Map<String, String>,
)

@InternalApi
abstract class ViaductTopologyService : BuildService<ViaductTopologyService.Params> {
    interface Params : BuildServiceParameters {
        val topologyJson: Property<String>
    }

    private val topology: ViaductApplicationMap by lazy {
        ViaductTopologyJson.decode(parameters.topologyJson.get())
    }

    fun topologyFor(projectPath: String): ViaductApplicationTopology? = topology.applicationTopologies[projectPath]

    companion object {
        const val NAME = "ViaductTopologyService"
    }
}

@InternalApi
object ViaductTopologyJson {
    private val mapper = jacksonObjectMapper()

    fun encode(topology: ViaductApplicationMap): String = mapper.writeValueAsString(topology)

    fun decode(json: String): ViaductApplicationMap = mapper.readValue(json)
}
