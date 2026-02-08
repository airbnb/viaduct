@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.runtime.FieldErrorsException
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setCheckerValue
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setRawValue
import viaduct.engine.runtime.ObjectEngineResultTestHelper
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.SyncProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.execution.ProxyEngineObjectDataTest.Companion.mkOerWithListFieldError
import viaduct.engine.runtime.mkRss
import viaduct.engine.runtime.mkSchema
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl

class SyncEngineObjectDataFactoryTest {
    private inner class Fixture(sdl: String, test: suspend Fixture.() -> Unit) {
        val schema = mkSchema(sdl)
        private val selectionSetFactory = RawSelectionSetFactoryImpl(schema)

        fun mkSelectionSet(
            typename: String,
            fragment: String,
            variables: Map<String, Any?> = emptyMap()
        ) = selectionSetFactory.rawSelectionSet(typename, fragment, variables)

        fun mkOER(
            typename: String,
            resultMap: Map<String, Any?> = emptyMap(),
            errors: List<Pair<String, Throwable>> = emptyList(),
            variables: Map<String, Any?> = emptyMap(),
            selections: String = "id"
        ): ObjectEngineResultImpl =
            ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType(typename),
                resultMap,
                errors.toMutableList(),
                emptyList(),
                schema,
                mkRss(typename, selections, variables, schema)
            )

        init {
            runBlocking { test() }
        }
    }

    // ============================================================================
    // Basic functionality tests
    // ============================================================================

    @Test
    fun `resolve with null selection set returns empty data`() {
        Fixture("type Query { x: Int }") {
            val oer = mkOER("Query", mapOf("x" to 1), selections = "x")

            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", null)

            assertEquals(emptySet<String>(), syncData.getSelections().toSet())
        }
    }

    @Test
    fun `resolve simple scalar fields`() {
        Fixture("type Query { x: Int, y: String }") {
            val oer = mkOER("Query", mapOf("x" to 42, "y" to "hello"), selections = "x y")
            val selectionSet = mkSelectionSet("Query", "x y")

            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals(42, syncData.get("x"))
            assertEquals("hello", syncData.get("y"))
            assertEquals(setOf("x", "y"), syncData.getSelections().toSet())
        }
    }

    // ============================================================================
    // Nested object resolution tests
    // ============================================================================

    @Test
    fun `resolve nested object returns SyncProxyEngineObjectData`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { stringField: String, object2: O2 }
                type O2 { intField: Int }
            """.trimIndent()
        ) {
            val oer = mkOER(
                "O1",
                mapOf(
                    "stringField" to "hello",
                    "object2" to mapOf("intField" to 42)
                ),
                selections = "stringField object2 { intField }"
            )
            val selectionSet = mkSelectionSet("O1", "stringField object2 { intField }")

            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals("hello", syncData.get("stringField"))

            val nested = syncData.get("object2")
            assertInstanceOf(SyncProxyEngineObjectData::class.java, nested)
            assertEquals(42, (nested as EngineObjectData.Sync).get("intField"))
        }
    }

    @Test
    fun `resolve deeply nested objects`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { o2: O2 }
                type O2 { o3: O3 }
                type O3 { value: String }
            """.trimIndent()
        ) {
            val oer = mkOER(
                "O1",
                mapOf(
                    "o2" to mapOf(
                        "o3" to mapOf("value" to "deep")
                    )
                ),
                selections = "o2 { o3 { value } }"
            )
            val selectionSet = mkSelectionSet("O1", "o2 { o3 { value } }")

            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            val o2 = syncData.get("o2") as EngineObjectData.Sync
            val o3 = o2.get("o3") as EngineObjectData.Sync
            assertEquals("deep", o3.get("value"))
        }
    }

    @Test
    fun `resolve nested object with null value`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { intField: Int }
            """.trimIndent()
        ) {
            val oer = mkOER(
                "O1",
                mapOf("object2" to null),
                selections = "object2 { intField }"
            )
            val selectionSet = mkSelectionSet("O1", "object2 { intField }")

            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals(null, syncData.get("object2"))
        }
    }

    // ============================================================================
    // List error handling tests
    // ============================================================================

    @Test
    fun `resolve list throws on first element error`() {
        Fixture("type Query { listField: [String] }") {
            val (oer, err) = mkOerWithListFieldError(schema.schema.getObjectType("Query"))

            val selectionSet = mkSelectionSet("Query", "listField")
            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            // Accessing the list field should throw because element 1 has an error
            val exc = assertThrows<FieldErrorsException> {
                syncData.get("listField")
            }
            assertEquals(listOf(err), exc.graphQLErrors)
        }
    }

    // ============================================================================
    // Access check failure tests
    // ============================================================================

    @Test
    fun `resolve with access check failure stores exception`() {
        Fixture("type Query { stringField: String }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
            val accessError = IllegalAccessException("no access")

            oer.computeIfAbsent(ObjectEngineResult.Key("stringField")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            "foo",
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "foo"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(MockCheckerErrorResult(accessError)))
            }

            val selectionSet = mkSelectionSet("Query", "stringField")
            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            // The selection should be present
            assertTrue(syncData.getSelections().toList().contains("stringField"))

            // But accessing it should throw the access check error
            val thrown = assertThrows<IllegalAccessException> {
                syncData.get("stringField")
            }
            assertSame(accessError, thrown)
        }
    }

    @Test
    fun `resolve with successful access check returns value`() {
        Fixture("type Query { stringField: String }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))

            oer.computeIfAbsent(ObjectEngineResult.Key("stringField")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            "allowed",
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "allowed"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("Query", "stringField")
            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals("allowed", syncData.get("stringField"))
        }
    }

    // ============================================================================
    // Field argument tests
    // ============================================================================

    @Test
    fun `resolve with field arguments`() {
        Fixture("type Query { field(x: Int): Int }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))

            oer.computeIfAbsent(ObjectEngineResult.Key("field", null, mapOf("x" to 1))) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            42,
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "field"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("Query", "field(x: 1)")
            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals(42, syncData.get("field"))
        }
    }

    @Test
    fun `resolve with aliased and argumented selection`() {
        Fixture("type Query { field(x: Int): Int }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))

            oer.computeIfAbsent(ObjectEngineResult.Key("field", "f1", mapOf("x" to 1))) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            11,
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "f1"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            oer.computeIfAbsent(ObjectEngineResult.Key("field", "f2", mapOf("x" to 2))) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            22,
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "f2"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("Query", "f1: field(x: 1) f2: field(x: 2)")
            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals(11, syncData.get("f1"))
            assertEquals(22, syncData.get("f2"))
            assertEquals(setOf("f1", "f2"), syncData.getSelections().toSet())
        }
    }

    @Test
    fun `resolve with variable arguments`() {
        Fixture("type Query { field(x: Int): Int }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))

            oer.computeIfAbsent(ObjectEngineResult.Key("field", null, mapOf("x" to 99))) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            99,
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "field"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("Query", "field(x: \$varX)", mapOf("varX" to 99))
            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals(99, syncData.get("field"))
        }
    }
}
