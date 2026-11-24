@file:Suppress("MatchingDeclarationName")

package viaduct.graphql.test

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

enum class StreamErrorComparison { EXACT, PATHS }

/** This validates deferred paths, unique field delivery, IDs, and stream termination. */
fun List<Map<String, Any?>>.assertValidDeferStream() {
    summarizeStream(map { mapper.valueToTree(it) }, StreamErrorComparison.EXACT, validateMessages = true)
}

/**
 * This allows different batching, sibling order, and IDs.
 * [sharedFields] maps shared leaf paths to the expected pending IDs that may deliver them.
 */
fun List<Map<String, Any?>>.assertJsonStream(
    expectedJson: String,
    errors: StreamErrorComparison = StreamErrorComparison.EXACT,
    deliveryOrder: List<Pair<List<Any>, List<Any>>> = emptyList(),
    sharedFields: Map<List<Any>, Set<String>> = emptyMap(),
) {
    val expected = try {
        mapper.readTree(expectedJson).also { require(it.isArray) }
    } catch (e: Exception) {
        throw IllegalArgumentException("Cannot parse expected JSON stream", e)
    }
    val expectedStream = summarizeStream(expected.toList(), errors, validateMessages = false)
    val actualStream = summarizeStream(map { mapper.valueToTree(it) }, errors, validateMessages = true)
    assertEquals(
        expectedStream.summary,
        actualStream.summary,
        "Stream delivery (ignoring batching, sibling order, and IDs)",
    )
    assertDeliveryGroups(expectedStream, actualStream, sharedFields)
    deliveryOrder.forEach { (parent, child) ->
        assertTrue(parent in actualStream.deliveryPositions && child in actualStream.deliveryPositions, "Missing delivery: $parent or $child")
        assertTrue(
            actualStream.deliveryPositions.getValue(parent) <= actualStream.deliveryPositions.getValue(child),
            "Expected $parent to be delivered no later than $child",
        )
    }
}

private data class DeliveryGroup(val path: List<Any>, val label: String?, val initial: Boolean)

private data class DeliveredError(val location: String, val error: JsonNode)

private data class GroupObservation(
    val group: DeliveryGroup,
    val fields: Set<List<Any>>,
    val errors: Map<DeliveredError, Int>,
)

private data class StreamSummary(
    val incremental: Boolean,
    val initialData: JsonNode?,
    val initialExtensions: JsonNode?,
    val finalData: JsonNode?,
    val errors: Map<DeliveredError, Int>,
)

private data class StreamObservation(
    val summary: StreamSummary,
    val groups: Map<String, GroupObservation>,
    val owners: Map<List<Any>, String>,
    val deliveryPositions: Map<List<Any>, Int>,
)

private fun assertDeliveryGroups(
    expected: StreamObservation,
    actual: StreamObservation,
    sharedFields: Map<List<Any>, Set<String>>,
) {
    sharedFields.forEach { (path, owners) ->
        require(path in expected.owners) { "Shared field was not delivered by an expected pending group: $path" }
        require(expected.owners.getValue(path) in owners) { "Shared field must allow its expected owner: $path" }
        require(owners.all { it in expected.groups }) { "Shared field refers to an unknown expected pending ID: $path" }
    }

    fun DeliveredError.sharedPath(): List<Any>? = if (location == "incremental") error.get("path")?.responsePath()?.takeIf { it in sharedFields } else null

    val expectedGroups = expected.groups.entries.toList()
    val actualGroups = actual.groups.values.toList()
    assertEquals(expectedGroups.size, actualGroups.size, "Pending group count")
    val candidates = expectedGroups.map { (id, expectedGroup) ->
        actualGroups.indices.filter { index ->
            val actualGroup = actualGroups[index]
            expectedGroup.group == actualGroup.group &&
                expectedGroup.fields - sharedFields.keys == actualGroup.fields - sharedFields.keys &&
                expectedGroup.errors.filterKeys { it.sharedPath() == null } == actualGroup.errors.filterKeys { it.sharedPath() == null } &&
                (actualGroup.fields + actualGroup.errors.keys.mapNotNull { it.sharedPath() })
                    .filter { it in sharedFields }.all { id in sharedFields.getValue(it) }
        }
    }
    // Matching whole groups keeps one ID mapping consistent across all shared fields.
    val matched = mutableMapOf<Int, Int>()

    fun match(
        expectedIndex: Int,
        visited: MutableSet<Int>
    ): Boolean {
        for (actualIndex in candidates[expectedIndex]) {
            if (!visited.add(actualIndex)) continue
            val previous = matched[actualIndex]
            if (previous == null || match(previous, visited)) {
                matched[actualIndex] = expectedIndex
                return true
            }
        }
        return false
    }
    expectedGroups.indices.forEach { index ->
        assertTrue(
            match(index, mutableSetOf()),
            "Deferred group ownership differs. Expected: ${expected.groups}. Actual: ${actual.groups}",
        )
    }
}

private fun summarizeStream(
    chunks: List<JsonNode>,
    errorComparison: StreamErrorComparison,
    validateMessages: Boolean,
): StreamObservation {
    assertTrue(chunks.isNotEmpty(), "Execution must produce a result")
    val initial = chunks.first()
    val data = initial.get("data")?.deepCopy<JsonNode>()
    val initialData = data?.deepCopy<JsonNode>()
    val active = mutableMapOf<String, DeliveryGroup>()
    val groups = mutableMapOf<String, DeliveryGroup>()
    val errors = mutableListOf<DeliveredError>()
    val groupErrors = mutableMapOf<String, MutableList<DeliveredError>>()
    val owners = mutableMapOf<List<Any>, String>()
    val positions = mutableMapOf<List<Any>, Int>()
    var position = 0

    fun recordData(
        path: List<Any>,
        value: JsonNode,
        groupId: String?
    ) {
        positions[path] = position
        when {
            value.isObject && value.size() > 0 -> value.fields().forEach { (key, child) -> recordData(path + key, child, groupId) }
            value.isArray && value.size() > 0 -> value.forEachIndexed { index, child -> recordData(path + index, child, groupId) }
            groupId != null -> owners[path] = groupId
        }
    }
    data?.let { recordData(emptyList(), it, null) }

    fun readErrors(
        node: JsonNode,
        location: String,
        groupId: String? = null
    ) {
        node.path("errors").forEach { error ->
            if (validateMessages) assertTrue(error.path("message").asText().isNotBlank(), "Error must have a message: $error")
            val normalized = if (errorComparison == StreamErrorComparison.PATHS) {
                mapper.createObjectNode().also { it.replace("path", error.path("path")) }
            } else {
                error
            }
            val delivered = DeliveredError(location, normalized)
            errors += delivered
            if (groupId != null) groupErrors.getOrPut(groupId) { mutableListOf() } += delivered
        }
    }

    fun atPath(path: List<Any>): JsonNode {
        return path.fold(checkNotNull(data) { "Missing response data" }) { node, segment ->
            val child = when (segment) {
                is Int -> node.path(segment)
                else -> node.path(segment as String)
            }
            assertFalse(child.isMissingNode || child.isNull, "Path has not been delivered: $path")
            child
        }
    }

    readErrors(initial, "initial")
    val incremental = initial.has("hasNext")
    if (!incremental) {
        assertEquals(1, chunks.size, "A standard response must be the only result")
        assertFalse(initial.has("pending") || initial.has("incremental") || initial.has("completed"))
    } else {
        chunks.forEachIndexed { index, chunk ->
            assertTrue(chunk.path("hasNext").isBoolean, "Missing hasNext at result $index")
            if (index > 0) {
                assertFalse(chunk.has("data") || chunk.has("errors"), "Update cannot contain initial response fields")
                assertTrue(chunks[index - 1].path("hasNext").booleanValue(), "Result after terminal response")
            } else {
                assertFalse(chunk.has("incremental") || chunk.has("completed"), "Initial response cannot contain updates")
            }
            chunk.path("pending").forEach { entry ->
                val id = entry.requiredId()
                assertFalse(id in groups, "ID announced more than once: $id")
                val group = DeliveryGroup(entry.path("path").responsePath(), entry.get("label")?.textValue(), index == 0)
                groups[id] = group
                active[id] = group
            }
            chunk.path("incremental").forEach { entry ->
                position++
                val id = entry.requiredId()
                val group = active[id]
                assertTrue(group != null, "Patch for an unannounced or completed ID: $entry")
                val path = group!!.path + (entry.get("subPath")?.responsePath() ?: emptyList())
                val target = atPath(path)
                assertTrue(target.isObject, "Patch target must be an object: $path")
                assertTrue(entry.path("data").isObject, "Expected an object-defer patch: $entry")
                entry.path("data").fields().forEach { (name, value) ->
                    assertFalse(target.has(name), "Field delivered more than once at $path: $name")
                    (target as ObjectNode).replace(name, value.deepCopy<JsonNode>())
                    // A later patch can turn an empty object into a shared container.
                    owners.remove(path)
                    recordData(path + name, value, id)
                }
                readErrors(entry, "incremental", id)
            }
            // A pending object's path may be introduced by a patch in the same message.
            chunk.path("pending").forEach { entry ->
                val path = entry.path("path").responsePath()
                assertTrue(atPath(path).isObject, "Pending path must refer to a delivered object: $path")
            }
            chunk.path("completed").forEach { entry ->
                val id = entry.requiredId()
                val group = active.remove(id)
                assertTrue(group != null, "Completion for an unannounced or completed ID: $entry")
                readErrors(entry, "completed", id)
            }
            assertEquals(active.isNotEmpty(), chunk.path("hasNext").booleanValue(), "Pending IDs after result $index")
        }
        assertTrue(active.isEmpty(), "All pending IDs must complete before the stream closes: $active")
    }
    return StreamObservation(
        StreamSummary(incremental, initialData, initial.get("extensions"), data, errors.groupingBy { it }.eachCount()),
        groups.mapValues { (id, group) ->
            GroupObservation(group, owners.filterValues { it == id }.keys, groupErrors[id].orEmpty().groupingBy { it }.eachCount())
        },
        owners,
        positions,
    )
}

private fun JsonNode.requiredId(): String {
    assertTrue(path("id").isTextual, "ID must be a string: $this")
    return path("id").textValue()
}

private fun JsonNode.responsePath(): List<Any> {
    assertTrue(isArray, "Path must be an array: $this")
    return map {
        assertTrue(it.isTextual || (it.isIntegralNumber && it.canConvertToInt() && it.intValue() >= 0), "Invalid path segment: $it")
        if (it.isTextual) it.textValue() else it.intValue()
    }
}
