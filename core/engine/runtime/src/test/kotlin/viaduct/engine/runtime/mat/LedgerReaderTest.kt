@file:Suppress("ForbiddenImport", "DEPRECATION")

package viaduct.engine.runtime.mat

import graphql.schema.GraphQLObjectType
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asViaductSchema
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineSchema
import viaduct.engine.api.ResolvedEngineObjectData
import viaduct.engine.api.spi.MaterializedFieldValueReader
import viaduct.engine.api.spi.MaterializedFieldValueReader.ReadResult
import viaduct.engine.runtime.result.ObjectEngineResult

class LedgerReaderTest {
    @Nested
    inner class CanFetch {
        @Test
        fun `matches the exact response key and arguments`() {
            val schema = "type Root { value(x: Int!): String }".asViaductSchema
            val rootType = schema.objectType("Root")
            val selectedKey =
                ObjectEngineResult.Key(
                    "value",
                    alias = "selected",
                    arguments = mapOf("x" to 2),
                )
            val staleKey =
                ObjectEngineResult.Key(
                    "value",
                    alias = "selected",
                    arguments = mapOf("x" to 1),
                )
            val reader = LedgerReader(
                resolvingLedger { _, _ -> error("Unexpected source resolution") },
                MatPath(rootType),
                KeyTree.build(schema) {
                    field(rootType.name, selectedKey)
                },
                fieldValueReader = MaterializedFieldValueReader.Default,
            )

            assertTrue(reader.canFetch(selectedKey))
            assertFalse(reader.canFetch(staleKey))
        }

        @Test
        fun `matches fields at an embedded path`() {
            val schema = "type Root { child: Child } type Child { name: String }".asViaductSchema
            val rootType = schema.objectType("Root")
            val childType = schema.objectType("Child")
            val childKey = ObjectEngineResult.Key("child")
            val nameKey = ObjectEngineResult.Key("name")
            val path = MatPath(
                rootType,
                listOf(MatPath.Segment(childType, childKey)),
            )
            val reader = LedgerReader(
                resolvingLedger { _, _ -> error("Unexpected source resolution") },
                path,
                KeyTree.build(schema) {
                    field(rootType.name, childKey) {
                        field(childType.name, nameKey)
                    }
                },
                fieldValueReader = MaterializedFieldValueReader.Default,
            )

            assertTrue(reader.canFetch(nameKey))
            assertFalse(reader.canFetch(ObjectEngineResult.Key("name", alias = "other")))
        }
    }

    @Nested
    inner class FetchOrNull {
        @Test
        fun `reads the schema field when the response key has an alias`() =
            runTest {
                val schema = "type Root { name: String, displayName: String }".asViaductSchema
                val rootType = schema.objectType("Root")
                val path = MatPath(rootType)
                val key = ObjectEngineResult.Key("name", alias = "displayName")
                val ledger = resolvingLedger { _, _ ->
                    ResolvedEngineObjectData(
                        rootType,
                        mapOf("name" to "Ada"),
                    )
                }
                val reader = LedgerReader(
                    ledger,
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, key)
                    },
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals(
                    ReadResult(value = "Ada", fieldIsMissing = false),
                    reader.read(key),
                )
                assertEquals(listOf(MockLedger.Request(path, key)), ledger.resolveSourceRequests)
            }

        @Test
        fun `custom reader selects the response key from the matching materialization`() =
            runTest {
                val schema = "type Root { value(x: Int!): String }".asViaductSchema
                val rootType = schema.objectType("Root")
                val key = ObjectEngineResult.Key("value", alias = "selected", arguments = mapOf("x" to 2))
                val source = ResolvedEngineObjectData(rootType, mapOf("value" to "requested"))
                val ledger = resolvingLedger { _, requestedKey ->
                    assertEquals(key, requestedKey)
                    source
                }
                val reader = LedgerReader(
                    ledger,
                    MatPath(rootType),
                    KeyTree.build(schema) { field(rootType.name, key) },
                    fieldValueReader = MaterializedFieldValueReader { data, fieldName, responseKey ->
                        assertSame(source, data)
                        assertEquals("value", fieldName)
                        ReadResult("${data.fetch(fieldName)}:$responseKey", fieldIsMissing = false)
                    },
                )

                assertEquals(ReadResult("requested:selected", fieldIsMissing = false), reader.read(key))
            }

        @Test
        fun `default reader distinguishes a null field from a missing field`() =
            runTest {
                val schema = "type Root { present: String, missing: String }".asViaductSchema
                val rootType = schema.objectType("Root")
                val present = ObjectEngineResult.Key("present", alias = "renamed")
                val missing = ObjectEngineResult.Key("missing")
                val source = ResolvedEngineObjectData(rootType, mapOf("present" to null))
                val reader = LedgerReader(
                    resolvingLedger { _, _ -> source },
                    MatPath(rootType),
                    KeyTree.build(schema) {
                        field(rootType.name, present)
                        field(rootType.name, missing)
                    },
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals(ReadResult(null, fieldIsMissing = false), reader.read(present))
                assertEquals(ReadResult(null, fieldIsMissing = true), reader.read(missing))
            }

        @Test
        fun `schema field key does not collide with another response key`() =
            runTest {
                val schema = "type Root { displayName: String, id: ID }".asViaductSchema
                val rootType = schema.objectType("Root")
                val path = MatPath(rootType)
                val aliasedKey = ObjectEngineResult.Key("displayName", alias = "id")
                val schemaFieldKey = ObjectEngineResult.Key("id")
                val ledger = resolvingLedger { _, key ->
                    when (key) {
                        aliasedKey -> ResolvedEngineObjectData(
                            rootType,
                            mapOf("displayName" to "display name"),
                        )
                        schemaFieldKey -> ResolvedEngineObjectData(
                            rootType,
                            mapOf("id" to "Root:1"),
                        )
                        else -> error("Unexpected key: $key")
                    }
                }
                val reader = LedgerReader(
                    ledger,
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, schemaFieldKey)
                    },
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals("Root:1", reader.fetchOrNull(schemaFieldKey))
                assertEquals(
                    listOf(MockLedger.Request(path, schemaFieldKey)),
                    ledger.resolveSourceRequests,
                )
            }

        @Test
        fun `argument key selects the matching materialization`() =
            runTest {
                val schema = "type Root { value(x: Int!): String }".asViaductSchema
                val rootType = schema.objectType("Root")
                val path = MatPath(rootType)
                val key =
                    ObjectEngineResult.Key(
                        "value",
                        alias = "selected",
                        arguments = mapOf("x" to 2),
                    )
                val ledger = resolvingLedger { _, requestedKey ->
                    ResolvedEngineObjectData(
                        rootType,
                        mapOf("value" to requestedKey.arguments.getValue("x")),
                    )
                }
                val reader = LedgerReader(
                    ledger,
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, key)
                    },
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals(2, reader.fetchOrNull(key))
                assertEquals(listOf(MockLedger.Request(path, key)), ledger.resolveSourceRequests)
            }

        @Test
        fun `reads an embedded object`() =
            runTest {
                val schema = "type Root { child: Child } type Child { name: String }".asViaductSchema
                val rootType = schema.objectType("Root")
                val childType = schema.objectType("Child")
                val childKey = ObjectEngineResult.Key("child")
                val nameKey = ObjectEngineResult.Key("name")
                val path = MatPath(
                    rootType,
                    listOf(MatPath.Segment(childType, childKey)),
                )
                val ledger = resolvingLedger { _, _ ->
                    ResolvedEngineObjectData(
                        childType,
                        mapOf("name" to "Ada"),
                    )
                }
                val reader = LedgerReader(
                    ledger,
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, childKey) {
                            field(childType.name, nameKey)
                        }
                    },
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals("Ada", reader.fetchOrNull(nameKey))
                assertEquals(listOf(MockLedger.Request(path, nameKey)), ledger.resolveSourceRequests)
            }

        @Test
        fun `returns null for a null materialization`() =
            runTest {
                val schema = "type Root { name: String }".asViaductSchema
                val rootType = schema.objectType("Root")
                val path = MatPath(rootType)
                val key = ObjectEngineResult.Key("name")
                val ledger = resolvingLedger { _, _ -> null }
                val reader = LedgerReader(
                    ledger,
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, key)
                    },
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals(null, reader.fetchOrNull(key))
                assertEquals(listOf(MockLedger.Request(path, key)), ledger.resolveSourceRequests)
            }

        @Test
        fun `reports a missing source without reading the field`() =
            runTest {
                val schema = "type Root { name: String }".asViaductSchema
                val rootType = schema.objectType("Root")
                val path = MatPath(rootType)
                val key = ObjectEngineResult.Key("name")
                val reader = LedgerReader(
                    MockLedger { _, _ -> MatLedger.Source.Missing },
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, key)
                    },
                    fieldValueReader = MaterializedFieldValueReader { _, _, _ -> error("Unexpected field read") },
                )

                assertEquals(ReadResult(null, fieldIsMissing = true), reader.read(key))
            }
    }

    @Nested
    inner class Failed {
        @Test
        fun `claims fields so failures are reported when read`() {
            val reader = LedgerReader.failed(RuntimeException("materialization failed"))

            assertTrue(reader.canFetch(ObjectEngineResult.Key("name")))
        }

        @Test
        fun `rethrows the preparation failure`() =
            runTest {
                val failure = RuntimeException("materialization failed")
                val reader = LedgerReader.failed(failure)

                val thrown = runCatching {
                    reader.fetchOrNull(ObjectEngineResult.Key("name"))
                }.exceptionOrNull()

                assertSame(failure, thrown)
            }
    }

    @Nested
    inner class RootNodeId {
        @Test
        fun `is read without a ledger lookup`() =
            runTest {
                val schema = "type Root { id: ID }".asViaductSchema
                val rootType = schema.objectType("Root")
                val key = ObjectEngineResult.Key("id", alias = "nodeId")
                val ledger = resolvingLedger { _, _ -> error("Unexpected source resolution") }
                val reader = LedgerReader(
                    ledger,
                    MatPath(rootType),
                    KeyTree.empty,
                    rootNodeId = "Root:1",
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertTrue(reader.canFetch(key))
                assertEquals("Root:1", reader.fetchOrNull(key))
                assertEquals(emptyList<MockLedger.Request>(), ledger.resolveSourceRequests)
            }

        @Test
        fun `reads the aliased field instead of the root node id`() =
            runTest {
                val schema = "type Root { displayName: String, id: ID }".asViaductSchema
                val rootType = schema.objectType("Root")
                val path = MatPath(rootType)
                val key = ObjectEngineResult.Key("displayName", alias = "id")
                val ledger = resolvingLedger { _, _ ->
                    ResolvedEngineObjectData(
                        rootType,
                        mapOf("displayName" to "display name"),
                    )
                }
                val reader = LedgerReader(
                    ledger,
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, key)
                    },
                    rootNodeId = "Root:1",
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals(
                    ReadResult(value = "display name", fieldIsMissing = false),
                    reader.read(key),
                )
                assertEquals(listOf(MockLedger.Request(path, key)), ledger.resolveSourceRequests)
            }

        @Test
        fun `is not used for embedded objects`() =
            runTest {
                val schema = "type Root { child: Child } type Child { id: ID }".asViaductSchema
                val rootType = schema.objectType("Root")
                val childType = schema.objectType("Child")
                val childKey = ObjectEngineResult.Key("child")
                val idKey = ObjectEngineResult.Key("id")
                val path = MatPath(
                    rootType,
                    listOf(MatPath.Segment(childType, childKey)),
                )
                val ledger = resolvingLedger { _, _ ->
                    ResolvedEngineObjectData(
                        childType,
                        mapOf("id" to "Child:1"),
                    )
                }
                val reader = LedgerReader(
                    ledger,
                    path,
                    KeyTree.build(schema) {
                        field(rootType.name, childKey) {
                            field(childType.name, idKey)
                        }
                    },
                    rootNodeId = "Root:1",
                    fieldValueReader = MaterializedFieldValueReader.Default,
                )

                assertEquals("Child:1", reader.fetchOrNull(idKey))
                assertEquals(listOf(MockLedger.Request(path, idKey)), ledger.resolveSourceRequests)
            }
    }

    private fun EngineSchema.objectType(name: String): GraphQLObjectType = schema.getObjectType(name)!!

    private fun resolvingLedger(sourceFor: (MatPath, ObjectEngineResult.Key) -> EngineObjectData?): MockLedger = MockLedger { path, key -> MatLedger.Source.Resolved(sourceFor(path, key)) }

    private class MockLedger(
        private val sourceFor: (MatPath, ObjectEngineResult.Key) -> MatLedger.Source,
    ) : MatLedger {
        data class Request(
            val path: MatPath,
            val key: ObjectEngineResult.Key,
        )

        val resolveSourceRequests = mutableListOf<Request>()

        override suspend fun ensureCoverage(
            requested: KeyTree,
            selectionHandle: EngineExecutionContext.ExecutionHandle,
        ) = error("Unexpected coverage request")

        override suspend fun resolveSource(
            path: MatPath,
            key: ObjectEngineResult.Key,
        ): MatLedger.Source {
            resolveSourceRequests += Request(path, key)
            return sourceFor(path, key)
        }

        override fun subtreeAt(path: MatPath): KeyTree = error("Unexpected subtree request")
    }
}
