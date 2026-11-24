package viaduct.graphql.test

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResultStreamAssertionTest {
    private val expected = """
        [
          {data: {hero: {}}, pending: [
            {id: "0", path: ["hero"], label: "id"},
            {id: "1", path: ["hero"], label: "name"}
          ], hasNext: true},
          {incremental: [{id: "0", data: {id: "1"}}, {id: "1", data: {name: "Luke"}}],
           completed: [{id: "0"}, {id: "1"}], hasNext: false}
        ]
    """.trimIndent()

    private val initial = mapOf(
        "data" to mapOf("hero" to emptyMap<String, Any?>()),
        "pending" to listOf(
            mapOf("id" to "a", "path" to listOf("hero"), "label" to "name"),
            mapOf("id" to "b", "path" to listOf("hero"), "label" to "id"),
        ),
        "hasNext" to true,
    )

    private fun update(
        id: String,
        data: Map<String, Any?>,
        hasNext: Boolean
    ): Map<String, Any?> =
        mapOf(
            "incremental" to listOf(mapOf("id" to id, "data" to data)),
            "completed" to listOf(mapOf("id" to id)),
            "hasNext" to hasNext,
        )

    private fun stream() =
        listOf(
            initial,
            update("a", mapOf("name" to "Luke"), true),
            update("b", mapOf("id" to "1"), false),
        )

    @Test
    fun `allows different batching sibling order pending order and opaque IDs`() {
        stream().assertJsonStream(expected)
    }

    @Test
    fun `preserves labeled field ownership without depending on IDs`() {
        val swapped = stream().toMutableList().apply {
            this[1] = update("b", mapOf("name" to "Luke"), true)
            this[2] = update("a", mapOf("id" to "1"), false)
        }
        assertThrows<AssertionError> { swapped.assertJsonStream(expected) }
    }

    @Test
    fun `preserves unlabeled ownership at different pending paths`() {
        val expectedGroups = """
            [
              {data: {hero: {}}, pending: [
                {id: "outer", path: []}, {id: "inner", path: ["hero"]}
              ], hasNext: true},
              {incremental: [
                {id: "outer", subPath: ["hero"], data: {id: "1"}},
                {id: "inner", data: {name: "Luke"}}
              ], completed: [{id: "outer"}, {id: "inner"}], hasNext: false}
            ]
        """.trimIndent()
        val initialGroups = initial + (
            "pending" to listOf(
                mapOf("id" to "a", "path" to listOf("hero")),
                mapOf("id" to "b", "path" to emptyList<String>()),
            )
        )

        fun delivered(swapped: Boolean) =
            listOf(
                initialGroups,
                mapOf(
                    "incremental" to if (swapped) {
                        listOf(
                            mapOf("id" to "a", "data" to mapOf("id" to "1")),
                            mapOf("id" to "b", "subPath" to listOf("hero"), "data" to mapOf("name" to "Luke")),
                        )
                    } else {
                        listOf(
                            mapOf("id" to "a", "data" to mapOf("name" to "Luke")),
                            mapOf("id" to "b", "subPath" to listOf("hero"), "data" to mapOf("id" to "1")),
                        )
                    },
                    "completed" to listOf(mapOf("id" to "a"), mapOf("id" to "b")),
                    "hasNext" to false,
                ),
            )
        delivered(swapped = false).assertJsonStream(expectedGroups)
        assertThrows<AssertionError> { delivered(swapped = true).assertJsonStream(expectedGroups) }
    }

    @Test
    fun `preserves separate unlabeled groups at the same path`() {
        val anonymousExpected = expected.replace(", label: \"id\"", "").replace(", label: \"name\"", "")
        val anonymousInitial = initial + (
            "pending" to listOf(
                mapOf("id" to "a", "path" to listOf("hero")),
                mapOf("id" to "b", "path" to listOf("hero")),
            )
        )
        (listOf(anonymousInitial) + stream().drop(1)).assertJsonStream(anonymousExpected)
        val combined = listOf(
            anonymousInitial,
            mapOf(
                "incremental" to listOf(mapOf("id" to "a", "data" to mapOf("id" to "1", "name" to "Luke"))),
                "completed" to listOf(mapOf("id" to "a"), mapOf("id" to "b")),
                "hasNext" to false,
            ),
        )
        assertThrows<AssertionError> { combined.assertJsonStream(anonymousExpected) }
    }

    @Test
    fun `allows declared shared fields to change owners after a group fails`() {
        val expectedShared = """
            [
              {data: {hero: {}}, pending: [
                {id: "a", path: ["hero"], label: "name"},
                {id: "b", path: ["hero"], label: "id"}
              ], hasNext: true},
              {incremental: [{id: "a", data: {id: "1"}}], completed: [{id: "b"}], hasNext: true},
              {completed: [{id: "a", errors: [{message: "failed", path: ["hero", "broken"]}]}], hasNext: false}
            ]
        """.trimIndent()
        val alternative = listOf(
            initial,
            mapOf(
                "incremental" to listOf(mapOf("id" to "b", "data" to mapOf("id" to "1"))),
                "completed" to listOf(
                    mapOf("id" to "a", "errors" to listOf(mapOf("message" to "failed", "path" to listOf("hero", "broken")))),
                    mapOf("id" to "b"),
                ),
                "hasNext" to false,
            ),
        )
        assertThrows<AssertionError> { alternative.assertJsonStream(expectedShared) }
        alternative.assertJsonStream(expectedShared, sharedFields = mapOf(listOf("hero", "id") to setOf("a", "b")))
    }

    @Test
    fun `limits shared data and errors to declared owners`() {
        val expectedShared = """
            [
              {data: {hero: {}}, pending: [
                {id: "a", path: ["hero"], label: "A"},
                {id: "b", path: ["hero"], label: "B"},
                {id: "c", path: ["hero"], label: "C"}
              ], hasNext: true},
              {incremental: [
                {id: "a", data: {id: null}, errors: [{message: "failed", path: ["hero", "id"]}]},
                {id: "c", data: {name: "Luke"}}
              ], completed: [{id: "a"}, {id: "b"}, {id: "c"}], hasNext: false}
            ]
        """.trimIndent()
        val initialGroups = initial + (
            "pending" to listOf("A", "B", "C").map { mapOf("id" to it, "path" to listOf("hero"), "label" to it) }
        )
        val sharedFields = mapOf<List<Any>, Set<String>>(listOf("hero", "id") to setOf("a", "b"))

        fun delivered(
            dataOwner: String,
            errorOwner: String
        ) = listOf(
            initialGroups,
            mapOf(
                "incremental" to listOf("A", "B", "C").map { id ->
                    buildMap {
                        put("id", id)
                        put(
                            "data",
                            buildMap {
                                if (id == dataOwner) put("id", null)
                                if (id == "C") put("name", "Luke")
                            }
                        )
                        if (id == errorOwner) put("errors", listOf(mapOf("message" to "failed", "path" to listOf("hero", "id"))))
                    }
                },
                "completed" to listOf("A", "B", "C").map { mapOf("id" to it) },
                "hasNext" to false,
            ),
        )
        delivered("B", "B").assertJsonStream(expectedShared, sharedFields = sharedFields)
        assertThrows<AssertionError> { delivered("C", "C").assertJsonStream(expectedShared, sharedFields = sharedFields) }
        assertThrows<AssertionError> { delivered("B", "C").assertJsonStream(expectedShared, sharedFields = sharedFields) }
    }

    @Test
    fun `matches anonymous shared owners consistently across fields`() {
        val expectedShared = """
            [
              {data: {hero: {}}, pending: [
                {id: "a", path: ["hero"]}, {id: "b", path: ["hero"]}, {id: "c", path: ["hero"]}
              ], hasNext: true},
              {incremental: [
                {id: "a", data: {x: 1}}, {id: "b", data: {y: 2}}, {id: "c", data: {z: 3}}
              ], completed: [{id: "a"}, {id: "b"}, {id: "c"}], hasNext: false}
            ]
        """.trimIndent()
        val sharedFields = mapOf<List<Any>, Set<String>>(
            listOf("hero", "x") to setOf("a", "b"),
            listOf("hero", "y") to setOf("b", "c"),
            listOf("hero", "z") to setOf("a", "c"),
        )

        fun delivered(vararg fields: Map<String, Int>) =
            listOf(
                initial + ("pending" to fields.indices.map { mapOf("id" to "$it", "path" to listOf("hero")) }),
                mapOf(
                    "incremental" to fields.mapIndexed { index, data -> mapOf("id" to "$index", "data" to data) },
                    "completed" to fields.indices.map { mapOf("id" to "$it") },
                    "hasNext" to false,
                ),
            )
        delivered(mapOf("x" to 1, "y" to 2), mapOf("z" to 3), emptyMap())
            .assertJsonStream(expectedShared, sharedFields = sharedFields)
        assertThrows<AssertionError> {
            delivered(mapOf("x" to 1, "y" to 2, "z" to 3), emptyMap(), emptyMap())
                .assertJsonStream(expectedShared, sharedFields = sharedFields)
        }
    }

    @Test
    fun `rejects invalid shared field declarations`() {
        listOf<Map<List<Any>, Set<String>>>(
            mapOf(listOf("hero", "missing") to setOf("0", "1")),
            mapOf(listOf("hero", "id") to setOf("0", "missing")),
            mapOf(listOf("hero", "id") to setOf("1")),
        ).forEach { sharedFields ->
            assertThrows<IllegalArgumentException> { stream().assertJsonStream(expected, sharedFields = sharedFields) }
        }
    }

    @Test
    fun `preserves labeled ownership of empty containers`() {
        for ((json, value) in listOf("[]" to emptyList<Any?>(), "{}" to emptyMap<String, Any?>())) {
            val expectedEmpty = """
                [
                  {data: {hero: {}}, pending: [
                    {id: "0", path: ["hero"], label: "id"},
                    {id: "1", path: ["hero"], label: "name"}
                  ], hasNext: true},
                  {incremental: [{id: "0", data: {id: $json}}, {id: "1", data: {name: $json}}],
                   completed: [{id: "0"}, {id: "1"}], hasNext: false}
                ]
            """.trimIndent()
            listOf(
                initial,
                update("a", mapOf("name" to value), true),
                update("b", mapOf("id" to value), false),
            ).assertJsonStream(expectedEmpty)
            val swapped = listOf(
                initial,
                update("b", mapOf("name" to value), true),
                update("a", mapOf("id" to value), false),
            )
            assertThrows<AssertionError> { swapped.assertJsonStream(expectedEmpty) }
        }
    }

    @Test
    fun `enforces explicit delivery dependencies without ordering other siblings`() {
        stream().assertJsonStream(expected, deliveryOrder = listOf(listOf("hero", "name") to listOf("hero", "id")))
        assertThrows<AssertionError> {
            stream().assertJsonStream(expected, deliveryOrder = listOf(listOf("hero", "id") to listOf("hero", "name")))
        }
    }

    @Test
    fun `standard responses still compare data exactly`() {
        listOf(mapOf("data" to mapOf("hero" to "Luke"))).assertJsonStream("""[{data: {hero: "Luke"}}]""")
        assertThrows<AssertionError> {
            listOf(mapOf("data" to mapOf("hero" to "Leia"))).assertJsonStream("""[{data: {hero: "Luke"}}]""")
        }
        assertThrows<IllegalArgumentException> { stream().assertJsonStream("invalid json {") }
    }

    @Test
    fun `distinguishes standard and incremental response modes`() {
        val standard = mapOf("data" to mapOf("hero" to "Luke"))
        val incremental = standard + ("hasNext" to false)
        listOf(incremental).assertJsonStream("""[{data: {hero: "Luke"}, hasNext: false}]""")
        assertThrows<AssertionError> { listOf(incremental).assertJsonStream("""[{data: {hero: "Luke"}}]""") }
        assertThrows<AssertionError> { listOf(standard).assertJsonStream("""[{data: {hero: "Luke"}, hasNext: false}]""") }
    }

    @Test
    fun `allows an object patch to be split at an existing path`() {
        listOf(
            mapOf("data" to emptyMap<String, Any?>(), "pending" to listOf(mapOf("id" to "x", "path" to emptyList<String>(), "label" to "hero")), "hasNext" to true),
            mapOf("incremental" to listOf(mapOf("id" to "x", "data" to mapOf("hero" to emptyMap<String, Any?>()))), "hasNext" to true),
            mapOf(
                "incremental" to listOf(mapOf("id" to "x", "subPath" to listOf("hero"), "data" to mapOf("id" to "1"))),
                "completed" to listOf(mapOf("id" to "x")),
                "hasNext" to false,
            ),
        ).assertJsonStream(
            """[
              {data: {}, pending: [{id: "0", path: [], label: "hero"}], hasNext: true},
              {incremental: [{id: "0", data: {hero: {id: "1"}}}], completed: [{id: "0"}], hasNext: false}
            ]"""
        )
    }

    @Test
    fun `rejects unannounced completed or reused IDs`() {
        listOf(
            stream().toMutableList().apply { this[1] = update("unknown", mapOf("name" to "Luke"), true) },
            stream() + update("b", mapOf("extra" to 2), false),
            stream().toMutableList().apply { this[2] = update("a", mapOf("id" to "1"), false) },
            stream().toMutableList().apply { this[1] = this[1] + ("pending" to initial.getValue("pending")) },
            stream().toMutableList().apply { this[1] = this[1] + ("completed" to listOf(mapOf("id" to "unknown"))) },
        ).forEach { invalid -> assertThrows<AssertionError> { invalid.assertJsonStream(expected) } }
    }

    @Test
    fun `rejects duplicate missing extra or incorrect field delivery`() {
        listOf(
            stream().toMutableList().apply { this[2] = update("b", mapOf("name" to "Luke", "id" to "1"), false) },
            stream().toMutableList().apply { this[2] = update("b", emptyMap(), false) },
            stream().toMutableList().apply { this[2] = update("b", mapOf("id" to "1", "extra" to 2), false) },
            stream().toMutableList().apply { this[1] = update("a", mapOf("name" to "Leia"), true) },
            stream().toMutableList().apply { this[0] = initial + ("data" to mapOf("hero" to mapOf("id" to "1"))) },
        ).forEach { invalid -> assertThrows<AssertionError> { invalid.assertJsonStream(expected) } }
    }

    @Test
    fun `rejects invalid hasNext unfinished IDs and results after termination`() {
        listOf(
            stream().dropLast(1),
            stream().toMutableList().apply { this[1] = this[1] + ("hasNext" to false) },
            stream().toMutableList().apply { this[2] = this[2] + ("hasNext" to true) },
            stream().toMutableList().apply { this[2] = this[2] - "hasNext" },
            stream() + mapOf("hasNext" to false),
        ).forEach { invalid -> assertThrows<AssertionError> { invalid.assertJsonStream(expected) } }
    }

    @Test
    fun `rejects patches before their parent data and announcements at nonexistent or null paths`() {
        val rootInitial = initial + ("data" to emptyMap<String, Any?>())
        assertThrows<AssertionError> { (listOf(rootInitial) + stream().drop(1)).assertJsonStream(expected) }
        val nullInitial = initial + ("data" to mapOf("hero" to null))
        assertThrows<AssertionError> { (listOf(nullInitial) + stream().drop(1)).assertJsonStream(expected) }
        val patchBeforeParent = stream().toMutableList().apply {
            this[1] = this[1] + ("incremental" to listOf(mapOf("id" to "a", "subPath" to listOf("missing"), "data" to mapOf("name" to "Luke"))))
        }
        assertThrows<AssertionError> { patchBeforeParent.assertJsonStream(expected) }
    }

    @Test
    fun `preserves labels initial announcements and list order`() {
        val wrongLabel = initial + (
            "pending" to listOf(
                mapOf("id" to "a", "path" to listOf("hero"), "label" to "other"),
                mapOf("id" to "b", "path" to listOf("hero"), "label" to "id"),
            )
        )
        assertThrows<AssertionError> { (listOf(wrongLabel) + stream().drop(1)).assertJsonStream(expected) }
        assertThrows<AssertionError> {
            listOf(mapOf("data" to mapOf("names" to listOf("Leia", "Luke")))).assertJsonStream("""[{data: {names: ["Luke", "Leia"]}}]""")
        }
        val laterAnnouncement = stream().toMutableList().apply {
            this[0] = initial + ("pending" to emptyList<Any>()) + ("hasNext" to false)
            this[1] = this[1] + ("pending" to initial.getValue("pending"))
        }
        assertThrows<AssertionError> { laterAnnouncement.assertJsonStream(expected) }
    }

    @Test
    fun `error path matching preserves count location and nonempty messages`() {
        val error = mapOf("message" to "wording can change", "path" to listOf("hero", "name"))
        val withError = stream().toMutableList().apply {
            this[1] = this[1] + ("incremental" to listOf(mapOf("id" to "a", "data" to mapOf("name" to "Luke"), "errors" to listOf(error))))
        }
        val expectedError = expected.replace("{id: \"1\", data: {name: \"Luke\"}}", """{id: "1", data: {name: "Luke"}, errors: [{message: "original wording", path: ["hero", "name"]}]}""")
        withError.assertJsonStream(expectedError, StreamErrorComparison.PATHS)
        withError.assertJsonStream(expectedError.replace("original wording", "wording can change"))
        assertThrows<AssertionError> { withError.assertJsonStream(expectedError) }
        listOf(
            error + ("message" to ""),
            error + ("path" to listOf("hero", "id")),
        ).forEach { invalidError ->
            val invalid = withError.toMutableList().apply {
                this[1] = this[1] + ("incremental" to listOf(mapOf("id" to "a", "data" to mapOf("name" to "Luke"), "errors" to listOf(invalidError))))
            }
            assertThrows<AssertionError> { invalid.assertJsonStream(expectedError, StreamErrorComparison.PATHS) }
        }
        val completionError = stream().toMutableList().apply {
            this[1] = this[1] + ("completed" to listOf(mapOf("id" to "a", "errors" to listOf(error))))
        }
        assertThrows<AssertionError> { completionError.assertJsonStream(expectedError, StreamErrorComparison.PATHS) }
        val duplicateError = withError.toMutableList().apply {
            this[1] = this[1] + ("incremental" to listOf(mapOf("id" to "a", "data" to mapOf("name" to "Luke"), "errors" to listOf(error, error))))
        }
        assertThrows<AssertionError> { duplicateError.assertJsonStream(expectedError, StreamErrorComparison.PATHS) }
    }
}
