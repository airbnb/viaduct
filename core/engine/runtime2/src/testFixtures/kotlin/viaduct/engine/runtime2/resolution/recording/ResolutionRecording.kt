package viaduct.engine.runtime2.resolution.recording

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

data class RecordedTest(
    val className: String,
    val methodName: String,
)

data class RecordedResolutionSource(
    val schema: String,
    val query: String,
)

/** IDs are local to this recording; event payloads contain detached JSON facts. */
data class ResolutionRecording(
    val test: RecordedTest,
    val tasks: List<RecordedTask>,
    val oers: List<RecordedOER>,
    val events: List<RecordedObservation>,
    val completed: Boolean,
    val failure: RecordedFailure?,
    val formatVersion: Int = 1,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val initialDemand: JsonNode? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val source: RecordedResolutionSource? = null,
)

data class RecordedTask(
    val id: Int,
    val kind: String,
    val creatorId: Int?,
    val location: JsonNode?,
    val resolverOccurrenceId: Int?,
)

data class RecordedOER(
    val id: Int,
    val type: String,
)

data class RecordedObservation(
    val sequence: Long,
    val kind: String,
    val facts: JsonNode,
)

data class RecordedFailure(
    val type: String,
    val message: String?,
)
