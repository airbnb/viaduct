@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.FetchFunction
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.runtime.FieldErrorsException
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setCheckerValue
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setRawValue
import viaduct.engine.runtime.ObjectEngineResultTestHelper
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.SyncProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.createEngineSelectionSet
import viaduct.engine.runtime.createSchema
import viaduct.engine.runtime.execution.ProxyEngineObjectDataTest.Companion.mkOerWithListFieldError
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl

class SyncEngineObjectDataFactoryTest {
    private inner class Fixture(sdl: String, test: suspend Fixture.() -> Unit) {
        val schema = createSchema(sdl)
        private val selectionSetFactory = EngineSelectionSetFactoryImpl(schema)

        fun mkSelectionSet(
            typename: String,
            fragment: String,
            variables: Map<String, Any?> = emptyMap()
        ) = selectionSetFactory.engineSelectionSet(typename, fragment, variables)

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
                createEngineSelectionSet(typename, selections, variables, schema)
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

    // ============================================================================
    // Instrumentation context tests
    // ============================================================================

    @Test
    fun `resolveImpl instruments each selection when context is present`() {
        Fixture("type Query { x: Int, y: String }") {
            val oer = mkOER("Query", mapOf("x" to 42, "y" to "hello"), selections = "x y")
            val selectionSet = mkSelectionSet("Query", "x y")

            val recordedSelections = mutableListOf<String>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recordedSelections.add(parameters.selection)
                    return fetchFn
                }
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)
            }

            assertEquals(42, syncData.get("x"))
            assertEquals("hello", syncData.get("y"))
            assertEquals(setOf("x", "y"), recordedSelections.toSet())
        }
    }

    @Test
    fun `resolveImpl works without instrumentation context`() {
        Fixture("type Query { x: Int, y: String }") {
            val oer = mkOER("Query", mapOf("x" to 42, "y" to "hello"), selections = "x y")
            val selectionSet = mkSelectionSet("Query", "x y")

            val syncData = SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)

            assertEquals(42, syncData.get("x"))
            assertEquals("hello", syncData.get("y"))
        }
    }

    @Test
    fun `resolveImpl instruments nested object selections`() {
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

            val recordedSelections = mutableListOf<String>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recordedSelections.add(parameters.selection)
                    return fetchFn
                }
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)
            }

            assertEquals("hello", syncData.get("stringField"))
            val nested = syncData.get("object2") as EngineObjectData.Sync
            assertEquals(42, nested.get("intField"))
            // Should record selections at both levels: top-level and nested
            assertTrue(recordedSelections.contains("stringField"))
            assertTrue(recordedSelections.contains("object2"))
            assertTrue(recordedSelections.contains("intField"))
        }
    }

    @Test
    fun `resolveImpl passes resultPath in instrumentation parameters when parentPath provided`() {
        Fixture("type Query { x: Int, y: String }") {
            val oer = mkOER("Query", mapOf("x" to 42, "y" to "hello"), selections = "x y")
            val selectionSet = mkSelectionSet("Query", "x y")

            val recordedPaths = mutableMapOf<String, ResultPath?>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recordedPaths[parameters.selection] = parameters.resultPath
                    return fetchFn
                }
            }

            val parentPath = ResultPath.parse("/query/user")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet, parentPath = parentPath)
            }

            assertEquals(42, syncData.get("x"))
            assertEquals("hello", syncData.get("y"))

            // Each selection should have a resultPath that is parentPath + selectionName
            assertNotNull(recordedPaths["x"])
            assertNotNull(recordedPaths["y"])
            assertEquals(ResultPath.parse("/query/user/x"), recordedPaths["x"])
            assertEquals(ResultPath.parse("/query/user/y"), recordedPaths["y"])
        }
    }

    @Test
    fun `resolveImpl passes null resultPath when no parentPath provided`() {
        Fixture("type Query { x: Int }") {
            val oer = mkOER("Query", mapOf("x" to 42), selections = "x")
            val selectionSet = mkSelectionSet("Query", "x")

            val recordedPaths = mutableMapOf<String, ResultPath?>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recordedPaths[parameters.selection] = parameters.resultPath
                    return fetchFn
                }
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet)
            }

            assertEquals(42, syncData.get("x"))
            assertEquals(null, recordedPaths["x"], "resultPath should be null when no parentPath provided")
        }
    }

    @Test
    fun `resolveImpl propagates resultPath through nested object selections`() {
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

            val recordedPaths = mutableMapOf<String, ResultPath?>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recordedPaths[parameters.selection] = parameters.resultPath
                    return fetchFn
                }
            }

            val parentPath = ResultPath.parse("/query/user")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet, parentPath = parentPath)
            }

            assertEquals("hello", syncData.get("stringField"))
            val nested = syncData.get("object2") as EngineObjectData.Sync
            assertEquals(42, nested.get("intField"))

            // Top-level selections should have parentPath + selectionName
            assertEquals(ResultPath.parse("/query/user/stringField"), recordedPaths["stringField"])
            assertEquals(ResultPath.parse("/query/user/object2"), recordedPaths["object2"])
            // Nested selection should have parentPath + object2 + intField
            assertEquals(ResultPath.parse("/query/user/object2/intField"), recordedPaths["intField"])
        }
    }

    @Test
    fun `resolveImpl propagates resultPath through FieldResolutionResult wrapping nested object`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { value: String }
            """.trimIndent()
        ) {
            // Build OER manually with FieldResolutionResult wrapping, which is the
            // real-world structure: Cell -> FieldResolutionResult -> ObjectEngineResultImpl
            val nestedOer = mkOER(
                "O2",
                mapOf("value" to "deep"),
                selections = "value"
            )
            val outerOer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("O1"))
            outerOer.computeIfAbsent(ObjectEngineResult.Key("object2")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            nestedOer,
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "object2"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("O1", "object2 { value }")

            val recordedPaths = mutableMapOf<String, ResultPath?>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recordedPaths[parameters.selection] = parameters.resultPath
                    return fetchFn
                }
            }

            val parentPath = ResultPath.parse("/query/parent")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(outerOer, "error", selectionSet, parentPath = parentPath)
            }

            val nested = syncData.get("object2") as EngineObjectData.Sync
            assertEquals("deep", nested.get("value"))

            // object2 selection gets parentPath + object2
            assertEquals(ResultPath.parse("/query/parent/object2"), recordedPaths["object2"])
            // value selection inside the FieldResolutionResult-wrapped nested object
            // gets parentPath + object2 + value
            assertEquals(ResultPath.parse("/query/parent/object2/value"), recordedPaths["value"])
        }
    }

    @Test
    fun `resolveImpl propagates resultPath through deeply nested FieldResolutionResult chain`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { name: String, o2: O2 }
                type O2 { label: String, o3: O3 }
                type O3 { value: Int }
            """.trimIndent()
        ) {
            // Build a 3-level deep structure with FieldResolutionResult wrapping at each level:
            //   O1 (Cell -> FRR -> O2 (Cell -> FRR -> O3))
            // This exercises the full unwrap chain: Cell -> FieldResolutionResult -> ObjectEngineResultImpl -> resolveImpl

            // Innermost: O3
            val o3Oer = mkOER("O3", mapOf("value" to 99), selections = "value")

            // Middle: O2, with o3 wrapped in FieldResolutionResult + Cell
            val o2Oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("O2"))
            o2Oer.computeIfAbsent(ObjectEngineResult.Key("label")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            "mid",
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "label"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            o2Oer.computeIfAbsent(ObjectEngineResult.Key("o3")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            o3Oer,
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "o3"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            // Outermost: O1, with o2 wrapped in FieldResolutionResult + Cell
            val o1Oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("O1"))
            o1Oer.computeIfAbsent(ObjectEngineResult.Key("name")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            "top",
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "name"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            o1Oer.computeIfAbsent(ObjectEngineResult.Key("o2")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            o2Oer,
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "o2"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("O1", "name o2 { label o3 { value } }")

            // Record both selection name and resultPath for each instrumented fetch
            data class Recorded(val selection: String, val parentType: String?, val path: ResultPath?)
            val recorded = mutableListOf<Recorded>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recorded.add(Recorded(parameters.selection, parameters.parentTypeName, parameters.resultPath))
                    return fetchFn
                }
            }

            val parentPath = ResultPath.parse("/query/root")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(o1Oer, "error", selectionSet, parentPath = parentPath)
            }

            // Verify values resolved correctly through the chain
            assertEquals("top", syncData.get("name"))
            val o2 = syncData.get("o2") as EngineObjectData.Sync
            assertEquals("mid", o2.get("label"))
            val o3 = o2.get("o3") as EngineObjectData.Sync
            assertEquals(99, o3.get("value"))

            // Verify resultPaths at every level
            val pathsBySelection = recorded.associateBy { it.selection }

            // Level 1: O1 selections
            assertEquals(ResultPath.parse("/query/root/name"), pathsBySelection["name"]?.path)
            assertEquals("O1", pathsBySelection["name"]?.parentType)
            assertEquals(ResultPath.parse("/query/root/o2"), pathsBySelection["o2"]?.path)
            assertEquals("O1", pathsBySelection["o2"]?.parentType)

            // Level 2: O2 selections (path extends through o2)
            assertEquals(ResultPath.parse("/query/root/o2/label"), pathsBySelection["label"]?.path)
            assertEquals("O2", pathsBySelection["label"]?.parentType)
            assertEquals(ResultPath.parse("/query/root/o2/o3"), pathsBySelection["o3"]?.path)
            assertEquals("O2", pathsBySelection["o3"]?.parentType)

            // Level 3: O3 selections (path extends through o2/o3)
            assertEquals(ResultPath.parse("/query/root/o2/o3/value"), pathsBySelection["value"]?.path)
            assertEquals("O3", pathsBySelection["value"]?.parentType)
        }
    }

    @Test
    fun `resolveImpl propagates resultPath with list index segments through nested objects`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { items: [Item] }
                type Item { name: String }
            """.trimIndent()
        ) {
            val oer = mkOER(
                "O1",
                mapOf(
                    "items" to listOf(
                        mapOf("name" to "first"),
                        mapOf("name" to "second"),
                        mapOf("name" to "third")
                    )
                ),
                selections = "items { name }"
            )
            val selectionSet = mkSelectionSet("O1", "items { name }")

            // Record ALL instrumentation calls (not just by selection name, since "name"
            // appears once per list element with different paths)
            data class Recorded(val selection: String, val parentType: String?, val path: ResultPath?)
            val recorded = mutableListOf<Recorded>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun <T> instrumentFetchSelection(
                    fetchFn: FetchFunction<T>,
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): FetchFunction<T> {
                    recorded.add(Recorded(parameters.selection, parameters.parentTypeName, parameters.resultPath))
                    return fetchFn
                }
            }

            val parentPath = ResultPath.parse("/query/root")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = withContext(ctx) {
                SyncEngineObjectDataFactory.resolve(oer, "error", selectionSet, parentPath = parentPath)
            }

            // Verify data resolved correctly
            @Suppress("UNCHECKED_CAST")
            val items = syncData.get("items") as List<EngineObjectData.Sync>
            assertEquals(3, items.size)
            assertEquals("first", items[0].get("name"))
            assertEquals("second", items[1].get("name"))
            assertEquals("third", items[2].get("name"))

            // Top-level "items" selection gets parentPath/items
            val itemsRecord = recorded.first { it.selection == "items" }
            assertEquals(ResultPath.parse("/query/root/items"), itemsRecord.path)
            assertEquals("O1", itemsRecord.parentType)

            // Each list element's "name" selection gets parentPath/items[index]/name
            val nameRecords = recorded.filter { it.selection == "name" }
            assertEquals(3, nameRecords.size, "Should have one 'name' instrumentation call per list element")

            val namePaths = nameRecords.map { it.path }.toSet()
            assertTrue(namePaths.contains(ResultPath.parse("/query/root/items[0]/name")))
            assertTrue(namePaths.contains(ResultPath.parse("/query/root/items[1]/name")))
            assertTrue(namePaths.contains(ResultPath.parse("/query/root/items[2]/name")))

            // All should have Item as parent type
            nameRecords.forEach { assertEquals("Item", it.parentType) }
        }
    }
}
