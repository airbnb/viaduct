@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.execution.ResultPath
import graphql.schema.GraphQLObjectType
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.CheckerResultContext
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.instrumentation.resolver.ResolverInstrumentationContext
import viaduct.engine.api.instrumentation.resolver.ViaductResolverInstrumentation
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.runtime.FieldErrorsException
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.newCell
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setCheckerValue
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setRawValue
import viaduct.engine.runtime.ObjectEngineResultTestHelper
import viaduct.engine.runtime.SyncEngineObjectDataFactory
import viaduct.engine.runtime.SyncProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.createEngineSelectionSet
import viaduct.engine.runtime.createSchema
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl

class SyncEngineObjectDataFactoryTest {
    private class BypassAwareCheckerErrorResult(override val error: Exception) : CheckerResult.Error {
        override fun isErrorForResolver(ctx: CheckerResultContext): Boolean = ctx.fieldDirectives?.hasDirective("bypassPolicyCheck") != true

        override fun combine(fieldResult: CheckerResult.Error): CheckerResult.Error = fieldResult
    }

    private suspend fun resolveSyncData(
        objectEngineResult: ObjectEngineResult,
        errorMessage: String,
        selectionSet: viaduct.engine.api.EngineSelectionSet? = null,
        parentPath: ResultPath? = null,
        instrumentationContext: ResolverInstrumentationContext? = null,
    ): SyncProxyEngineObjectData {
        return SyncEngineObjectDataFactory.resolve(
            objectEngineResult = objectEngineResult,
            errorMessage = errorMessage,
            selectionSet = selectionSet,
            parentPath = parentPath,
            instrumentationContext = instrumentationContext,
        )
    }

    private fun recordingFetchSelectionInstrumentation(record: (ViaductResolverInstrumentation.InstrumentFetchSelectionParameters) -> Unit): ViaductResolverInstrumentation =
        object : ViaductResolverInstrumentation {
            override fun beginFetchSelection(
                parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                state: ViaductResolverInstrumentation.InstrumentationState?,
            ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                record(parameters)
                return ViaductResolverInstrumentation.FetchSelectionInstrumentation.NOOP
            }
        }

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

            val syncData = resolveSyncData(oer, "error", null)

            assertEquals(emptySet<String>(), syncData.getSelections().toSet())
        }
    }

    @Test
    fun `resolve simple scalar fields`() {
        Fixture("type Query { x: Int, y: String }") {
            val oer = mkOER("Query", mapOf("x" to 42, "y" to "hello"), selections = "x y")
            val selectionSet = mkSelectionSet("Query", "x y")

            val syncData = resolveSyncData(oer, "error", selectionSet)

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

            val syncData = resolveSyncData(oer, "error", selectionSet)

            assertEquals("hello", syncData.get("stringField"))

            val nested = syncData.get("object2")
            nested.shouldBeInstanceOf<SyncProxyEngineObjectData>()
            assertEquals(42, (nested as EngineObjectData.Sync).get("intField"))
        }
    }

    @Test
    fun `resolve nested object`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { intField: Int }
            """.trimIndent()
        ) {
            val selectionSet = mkSelectionSet("O1", "object2 { intField }")
            val nestedOer = mkOER("O2", mapOf("intField" to 1), selections = "intField")
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

            val syncData = resolveSyncData(
                outerOer,
                "error",
                selectionSet,
            )
            val nested = syncData.get("object2") as EngineObjectData.Sync

            assertEquals(1, nested.get("intField"))
        }
    }

    @Test
    fun `resolve preserves exact alias match when lookup selection set contains ambiguous candidates`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { bar: Int }
            """.trimIndent()
        ) {
            val selectionSet = mkSelectionSet("O1", "bar aliasedBar: bar")
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("O1"),
                mapOf(
                    ObjectEngineResult.Key("bar") to 1,
                    ObjectEngineResult.Key("bar", alias = "aliasedBar") to 2,
                ),
                mutableListOf(),
                emptyList(),
                schema,
                selectionSet
            )

            val syncData = resolveSyncData(
                oer,
                "error",
                selectionSet,
            )

            assertEquals(1, syncData.get("bar"))
            assertEquals(2, syncData.get("aliasedBar"))
        }
    }

    @Test
    fun `resolve preserves exact alias match when visible selection is narrower than lookup selection set`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { bar: Int }
            """.trimIndent()
        ) {
            val visibleSelectionSet = mkSelectionSet("O1", "aliasedBar: bar")
            val lookupSelectionSet = mkSelectionSet("O1", "bar aliasedBar: bar")
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("O1"),
                mapOf(
                    ObjectEngineResult.Key("bar") to 1,
                    ObjectEngineResult.Key("bar", alias = "aliasedBar") to 2,
                ),
                mutableListOf(),
                emptyList(),
                schema,
                lookupSelectionSet
            )

            val syncData = resolveSyncData(
                oer,
                "error",
                visibleSelectionSet,
            )

            assertEquals(2, syncData.get("aliasedBar"))
            assertEquals(setOf("aliasedBar"), syncData.getSelections().toSet())
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

            val syncData = resolveSyncData(oer, "error", selectionSet)

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

            val syncData = resolveSyncData(oer, "error", selectionSet)

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
            val syncData = resolveSyncData(oer, "error", selectionSet)

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
            val syncData = resolveSyncData(oer, "error", selectionSet)

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
    fun `resolve with bypassPolicyCheck directive suppresses access check failure`() {
        Fixture(
            """
                directive @bypassPolicyCheck on FIELD
                type Query { stringField: String }
            """.trimIndent()
        ) {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))

            oer.computeIfAbsent(ObjectEngineResult.Key("stringField")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            "allowed",
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "stringField"
                        )
                    )
                )
                slotSetter.setCheckerValue(
                    Value.fromValue(BypassAwareCheckerErrorResult(IllegalAccessException("no access")))
                )
            }

            val selectionSet = mkSelectionSet("Query", "stringField @bypassPolicyCheck")
            val syncData = resolveSyncData(oer, "error", selectionSet)

            assertEquals("allowed", syncData.get("stringField"))
        }
    }

    @Test
    fun `resolve list uses field directive context for element access check failures`() {
        Fixture(
            """
                directive @bypassPolicyCheck on FIELD
                type Query { listField: [String] }
            """.trimIndent()
        ) {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
            val itemCell = newCell { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            "allowed",
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "listField"
                        )
                    )
                )
                slotSetter.setCheckerValue(
                    Value.fromValue(BypassAwareCheckerErrorResult(IllegalAccessException("no access")))
                )
            }

            oer.computeIfAbsent(ObjectEngineResult.Key("listField")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(
                            listOf(itemCell),
                            emptyList(),
                            CompositeLocalContext.empty,
                            emptyMap(),
                            "listField"
                        )
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("Query", "listField @bypassPolicyCheck")
            val syncData = resolveSyncData(oer, "error", selectionSet)

            assertEquals(listOf("allowed"), syncData.get("listField"))
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
            val syncData = resolveSyncData(oer, "error", selectionSet)

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
            val syncData = resolveSyncData(oer, "error", selectionSet)

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
            val syncData = resolveSyncData(oer, "error", selectionSet)

            assertEquals(11, syncData.get("f1"))
            assertEquals(22, syncData.get("f2"))
            assertEquals(setOf("f1", "f2"), syncData.getSelections().toSet())
        }
    }

    @Test
    fun `resolve preserves exact alias and arguments match when visible selection is narrower than lookup selection set`() {
        Fixture("type Query { field(x: Int): Int }") {
            val visibleSelectionSet = mkSelectionSet("Query", "aliasedField: field(x: 1)")
            val lookupSelectionSet = mkSelectionSet(
                "Query",
                "field(x: 1) aliasedField: field(x: 1) otherArg: field(x: 2)"
            )
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("Query"),
                mapOf(
                    ObjectEngineResult.Key("field", null, mapOf("x" to 1)) to 1,
                    ObjectEngineResult.Key("field", "aliasedField", mapOf("x" to 1)) to 2,
                    ObjectEngineResult.Key("field", "otherArg", mapOf("x" to 2)) to 3,
                ),
                mutableListOf(),
                emptyList(),
                schema,
                lookupSelectionSet
            )

            val syncData = resolveSyncData(
                oer,
                "error",
                visibleSelectionSet,
            )

            assertEquals(2, syncData.get("aliasedField"))
            assertEquals(setOf("aliasedField"), syncData.getSelections().toSet())
        }
    }

    @Test
    fun `resolve nested proxy succeeds when visible and stored selection shapes match`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { field(x: Int): Int }
            """.trimIndent()
        ) {
            val lookupSelectionSet = mkSelectionSet(
                "O1",
                "object2 { aliasedField: field(x: 1) }"
            )
            val childLookupEngineSelectionSet = lookupSelectionSet.selectionSetForSelection("O1", "object2")
            val nestedOer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("O2"),
                mapOf(
                    ObjectEngineResult.Key("field", "aliasedField", mapOf("x" to 1)) to 2,
                ),
                mutableListOf(),
                emptyList(),
                schema,
                childLookupEngineSelectionSet
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

            val syncData = resolveSyncData(
                outerOer,
                "error",
                lookupSelectionSet,
            )
            val object2 = syncData.get("object2") as EngineObjectData.Sync

            assertEquals(2, object2.get("aliasedField"))
            assertEquals(setOf("aliasedField"), object2.getSelections().toSet())
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
            val syncData = resolveSyncData(oer, "error", selectionSet)

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
            val instrumentation = recordingFetchSelectionInstrumentation {
                recordedSelections.add(it.selection)
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(oer, "error", selectionSet, instrumentationContext = ctx)

            assertEquals(42, syncData.get("x"))
            assertEquals("hello", syncData.get("y"))
            assertEquals(setOf("x", "y"), recordedSelections.toSet())
        }
    }

    @Test
    fun `resolveImpl finishes selection instrumentation after slot values complete`() {
        Fixture("type Query { x: Int }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
            val rawValue = CompletableDeferred<FieldResolutionResult>()
            oer.computeIfAbsent(ObjectEngineResult.Key("x")) { slotSetter ->
                slotSetter.setRawValue(Value.fromDeferred(rawValue))
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            val selectionSet = mkSelectionSet("Query", "x")

            val beginSignal = CompletableDeferred<Unit>()
            val finishedSelections = mutableListOf<String>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun beginFetchSelection(
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                    beginSignal.complete(Unit)
                    return ViaductResolverInstrumentation.FetchSelectionInstrumentation { cause ->
                        assertEquals(null, cause)
                        finishedSelections.add(parameters.selection)
                    }
                }
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            coroutineScope {
                val resolveResult = async {
                    resolveSyncData(oer, "error", selectionSet, instrumentationContext = ctx)
                }
                beginSignal.await()
                assertTrue(finishedSelections.isEmpty())

                rawValue.complete(
                    FieldResolutionResult(
                        42,
                        emptyList(),
                        CompositeLocalContext.empty,
                        emptyMap(),
                        "x"
                    )
                )

                val syncData = resolveResult.await()
                assertEquals(42, syncData.get("x"))
            }

            assertEquals(listOf("x"), finishedSelections)
        }
    }

    @Test
    fun `resolveImpl finishes selection instrumentation with the throwable when the slot completes exceptionally`() {
        Fixture("type Query { x: Int }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
            val rawValue = CompletableDeferred<FieldResolutionResult>()
            oer.computeIfAbsent(ObjectEngineResult.Key("x")) { slotSetter ->
                slotSetter.setRawValue(Value.fromDeferred(rawValue))
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            val selectionSet = mkSelectionSet("Query", "x")

            val slotError = RuntimeException("slot failed")
            val beginSignal = CompletableDeferred<Unit>()
            var capturedCause: Throwable? = null
            var finishCalled = false
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun beginFetchSelection(
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                    beginSignal.complete(Unit)
                    return ViaductResolverInstrumentation.FetchSelectionInstrumentation { cause ->
                        finishCalled = true
                        capturedCause = cause
                    }
                }
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            coroutineScope {
                val resolveResult = async {
                    resolveSyncData(oer, "error", selectionSet, instrumentationContext = ctx)
                }
                beginSignal.await()
                assertTrue(!finishCalled)

                // Complete the raw value slot exceptionally. The batched awaitOrElse in
                // resolveImpl swallows slot errors (they stay in the cell for access-time
                // throwing), so resolveSyncData itself returns normally.
                rawValue.completeExceptionally(slotError)

                val syncData = resolveResult.await()
                // The error is stored in the field and thrown on access, not during resolve().
                val thrown = assertThrows<Exception> { syncData.get("x") }
                assertSame(slotError, thrown)
            }

            // finish() must have received the slot's throwable, not null.
            assertTrue(finishCalled)
            assertSame(slotError, capturedCause)
        }
    }

    @Test
    fun `resolveImpl finishes selection instrumentation with a CancellationException when the slot is cancelled`() {
        Fixture("type Query { x: Int }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
            val rawValue = CompletableDeferred<FieldResolutionResult>()
            oer.computeIfAbsent(ObjectEngineResult.Key("x")) { slotSetter ->
                slotSetter.setRawValue(Value.fromDeferred(rawValue))
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            val selectionSet = mkSelectionSet("Query", "x")

            val beginSignal = CompletableDeferred<Unit>()
            var capturedCause: Throwable? = null
            var finishCalled = false
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = object : ViaductResolverInstrumentation {
                override fun beginFetchSelection(
                    parameters: ViaductResolverInstrumentation.InstrumentFetchSelectionParameters,
                    state: ViaductResolverInstrumentation.InstrumentationState?,
                ): ViaductResolverInstrumentation.FetchSelectionInstrumentation {
                    beginSignal.complete(Unit)
                    return ViaductResolverInstrumentation.FetchSelectionInstrumentation { cause ->
                        finishCalled = true
                        capturedCause = cause
                    }
                }
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            coroutineScope {
                val resolveResult = async {
                    resolveSyncData(oer, "error", selectionSet, instrumentationContext = ctx)
                }
                beginSignal.await()
                assertTrue(!finishCalled)

                // Cancel the raw value slot. This guards the fix that switched from thenApply
                // (which swallows CancellationException via Deferred.handle) to
                // asDeferred().invokeOnCompletion (which fires on cancellation). Cancelling the
                // slot cancels the batched await, so resolveSyncData's async throws
                // CancellationException — swallow it here so the test itself does not fail.
                rawValue.cancel(CancellationException("slot cancelled"))

                try {
                    resolveResult.await()
                } catch (_: CancellationException) {
                    // Expected: the batched await surfaces cancellation to the resolve coroutine.
                }
            }

            // finish() must have fired and received a CancellationException, not null.
            assertTrue(finishCalled)
            assertNotNull(capturedCause)
            capturedCause.shouldBeInstanceOf<CancellationException>()
        }
    }

    @Test
    fun `resolveImpl works without instrumentation context`() {
        Fixture("type Query { x: Int, y: String }") {
            val oer = mkOER("Query", mapOf("x" to 42, "y" to "hello"), selections = "x y")
            val selectionSet = mkSelectionSet("Query", "x y")

            val syncData = resolveSyncData(oer, "error", selectionSet)

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
            val instrumentation = recordingFetchSelectionInstrumentation {
                recordedSelections.add(it.selection)
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(oer, "error", selectionSet, instrumentationContext = ctx)

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
            val instrumentation = recordingFetchSelectionInstrumentation {
                recordedPaths[it.selection] = it.resultPath
            }

            val parentPath = ResultPath.parse("/query/user")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(oer, "error", selectionSet, parentPath = parentPath, instrumentationContext = ctx)

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
            val instrumentation = recordingFetchSelectionInstrumentation {
                recordedPaths[it.selection] = it.resultPath
            }

            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(oer, "error", selectionSet, instrumentationContext = ctx)

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
            val instrumentation = recordingFetchSelectionInstrumentation {
                recordedPaths[it.selection] = it.resultPath
            }

            val parentPath = ResultPath.parse("/query/user")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(oer, "error", selectionSet, parentPath = parentPath, instrumentationContext = ctx)

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
            val selectionSet = mkSelectionSet("O1", "object2 { value }")
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

            val recordedPaths = mutableMapOf<String, ResultPath?>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = recordingFetchSelectionInstrumentation {
                recordedPaths[it.selection] = it.resultPath
            }

            val parentPath = ResultPath.parse("/query/parent")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(outerOer, "error", selectionSet, parentPath = parentPath, instrumentationContext = ctx)

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
            val selectionSet = mkSelectionSet("O1", "name o2 { label o3 { value } }")
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

            // Record both selection name and resultPath for each instrumented fetch
            data class Recorded(val selection: String, val parentType: String?, val path: ResultPath?)
            val recorded = mutableListOf<Recorded>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = recordingFetchSelectionInstrumentation {
                recorded.add(Recorded(it.selection, it.parentTypeName, it.resultPath))
            }

            val parentPath = ResultPath.parse("/query/root")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(o1Oer, "error", selectionSet, parentPath = parentPath, instrumentationContext = ctx)

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

    // ============================================================================
    // Introspection field tests
    // ============================================================================

    @Test
    fun `resolve with __typename does not throw`() {
        Fixture("type Query { x: Int }") {
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("Query"),
                mapOf(ObjectEngineResult.Key("__typename") to "Query", ObjectEngineResult.Key("x") to 1),
                mutableListOf(),
                emptyList(),
                schema,
                createEngineSelectionSet("Query", "__typename x", emptyMap(), schema)
            )
            val selectionSet = mkSelectionSet("Query", "__typename x")

            val syncData = resolveSyncData(oer, "error", selectionSet)

            assertEquals("Query", syncData.get("__typename"))
            assertEquals(1, syncData.get("x"))
        }
    }

    @Test
    fun `resolve nested object with __typename does not throw`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { child: O2 }
                type O2 { value: String }
            """.trimIndent()
        ) {
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("O1"),
                mapOf(ObjectEngineResult.Key("child") to mapOf("__typename" to "O2", "value" to "hello")),
                mutableListOf(),
                emptyList(),
                schema,
                createEngineSelectionSet("O1", "child { __typename value }", emptyMap(), schema)
            )
            val selectionSet = mkSelectionSet("O1", "child { __typename value }")

            val syncData = resolveSyncData(oer, "error", selectionSet)

            val nested = syncData.get("child") as EngineObjectData.Sync
            assertEquals("O2", nested.get("__typename"))
            assertEquals("hello", nested.get("value"))
        }
    }

    // ============================================================================
    // Batched await tests
    // ============================================================================

    @Test
    fun `resolve awaits all incomplete cell values concurrently before assembling results`() {
        Fixture("type Query { a: String, b: String, c: String }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))

            // Create three cells backed by CompletableDeferred — not yet complete when resolve() starts.
            val deferredA = CompletableDeferred<FieldResolutionResult>()
            val deferredB = CompletableDeferred<FieldResolutionResult>()
            val deferredC = CompletableDeferred<FieldResolutionResult>()

            fun makeResult(
                value: String,
                fieldName: String
            ) = FieldResolutionResult(value, emptyList(), CompositeLocalContext.empty, emptyMap(), fieldName)

            oer.computeIfAbsent(ObjectEngineResult.Key("a")) { slotSetter ->
                slotSetter.setRawValue(Value.fromDeferred(deferredA))
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            oer.computeIfAbsent(ObjectEngineResult.Key("b")) { slotSetter ->
                slotSetter.setRawValue(Value.fromDeferred(deferredB))
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }
            oer.computeIfAbsent(ObjectEngineResult.Key("c")) { slotSetter ->
                slotSetter.setRawValue(Value.fromDeferred(deferredC))
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("Query", "a b c")

            // Complete all three deferreds concurrently while resolve() is suspended on waitAll.
            // coroutineScope runs the launcher and resolve() concurrently; resolve() suspends on
            // waitAll until the launcher completes the deferreds.
            lateinit var syncData: SyncProxyEngineObjectData
            coroutineScope {
                launch {
                    deferredA.complete(makeResult("alpha", "a"))
                    deferredB.complete(makeResult("beta", "b"))
                    deferredC.complete(makeResult("gamma", "c"))
                }
                syncData = SyncEngineObjectDataFactory.resolve(
                    objectEngineResult = oer,
                    errorMessage = "error",
                    selectionSet = selectionSet,
                )
            }

            assertEquals("alpha", syncData.get("a"))
            assertEquals("beta", syncData.get("b"))
            assertEquals("gamma", syncData.get("c"))
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
            val selectionSet = mkSelectionSet("O1", "items { name }")
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("O1"),
                mapOf(
                    ObjectEngineResult.Key("items") to listOf(
                        mapOf("name" to "first"),
                        mapOf("name" to "second"),
                        mapOf("name" to "third")
                    )
                ),
                mutableListOf(),
                emptyList(),
                schema,
                selectionSet
            )

            // Record ALL instrumentation calls (not just by selection name, since "name"
            // appears once per list element with different paths)
            data class Recorded(val selection: String, val parentType: String?, val path: ResultPath?)
            val recorded = mutableListOf<Recorded>()
            val state = object : ViaductResolverInstrumentation.InstrumentationState {}
            val instrumentation = recordingFetchSelectionInstrumentation {
                recorded.add(Recorded(it.selection, it.parentTypeName, it.resultPath))
            }

            val parentPath = ResultPath.parse("/query/root")
            val ctx = ResolverInstrumentationContext(instrumentation, state)
            val syncData = resolveSyncData(oer, "error", selectionSet, parentPath = parentPath, instrumentationContext = ctx)

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

    @Test
    fun `resolve does not throw when both RAW_VALUE_SLOT and ACCESS_CHECK_SLOT are exceptional`() {
        // Regression: when a resolver throws, combineWithTypeCheck can leave both slots exceptional.
        // Previously, awaitOrElse folded the raw exception but then value.fetch(ACCESS_CHECK_SLOT)
        // threw, escaping resolveImpl. The fix returns early when cellRaw is already an exception.
        Fixture("type Query { failingField: String succeedingField: String }") {
            val fieldError = RuntimeException("intentional field error")
            val checkerError = RuntimeException("checker also failed")

            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
            oer.fieldResolutionState.complete(Unit)

            oer.computeIfAbsent(ObjectEngineResult.Key("failingField")) { slotSetter ->
                slotSetter.setRawValue(Value.fromThrowable(fieldError))
                slotSetter.setCheckerValue(Value.fromThrowable(checkerError))
            }
            oer.computeIfAbsent(ObjectEngineResult.Key("succeedingField")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(FieldResolutionResult("ok", emptyList(), CompositeLocalContext.empty, emptyMap(), "succeedingField"))
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            val selectionSet = mkSelectionSet("Query", "failingField succeedingField")

            val syncData = resolveSyncData(oer, "error", selectionSet)

            assertEquals("ok", syncData.get("succeedingField"))

            val thrown = assertThrows<Exception> { syncData.get("failingField") }
            assertSame(fieldError, thrown)
        }
    }

    @Test
    fun `resolve does not throw when RAW_VALUE_SLOT contains SyncThrow -- error stored in backing map`() {
        Fixture("type Query { failingField: String succeedingField: String }") {
            val fieldError = RuntimeException("intentional field error")

            // mkOER with a null value + matching error entry → Value.fromThrowable in RAW_VALUE_SLOT
            // (matching how NodeWithoutDataImpl.getFetchedObject() throws, producing SyncThrow in the cell)
            val oer = mkOER(
                "Query",
                resultMap = mapOf("failingField" to null, "succeedingField" to "ok"),
                errors = listOf("failingField" to fieldError),
                selections = "failingField succeedingField",
            )
            val selectionSet = mkSelectionSet("Query", "failingField succeedingField")

            // resolve() must NOT throw — errors are stored per-field
            val syncData = resolveSyncData(oer, "error", selectionSet)

            // succeedingField resolves normally
            assertEquals("ok", syncData.get("succeedingField"))

            // failingField error surfaces at access time, not during resolve()
            val thrown = assertThrows<Exception> { syncData.get("failingField") }
            assertSame(fieldError, thrown)
        }
    }

    // ============================================================================
    // skipAccessCheck tests
    // ============================================================================

    @Test
    fun `resolve with skipAccessCheck=true does not wait for ACCESS_CHECK_SLOT`(): Unit =
        // If skipAccessCheck=false, this would deadlock because ACCESS_CHECK_SLOT never completes.
        runBlocking {
            Fixture("type Query { field: String }") {
                val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
                oer.fieldResolutionState.complete(Unit)

                val neverCompletingChecker = CompletableDeferred<CheckerResult?>()
                oer.computeIfAbsent(ObjectEngineResult.Key("field")) { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(FieldResolutionResult("value", emptyList(), CompositeLocalContext.empty, emptyMap(), "field"))
                    )
                    // ACCESS_CHECK_SLOT is intentionally never completed
                    slotSetter.setCheckerValue(Value.fromDeferred(neverCompletingChecker))
                }

                val selectionSet = mkSelectionSet("Query", "field")

                val syncData = withTimeout(1000) {
                    SyncEngineObjectDataFactory.resolve(
                        oer,
                        "error",
                        selectionSet,
                        skipAccessCheck = true,
                    )
                }

                assertEquals("value", syncData.get("field"))
            }
        }

    @Test
    fun `resolve with skipAccessCheck=false waits for ACCESS_CHECK_SLOT and would deadlock if never completed`(): Unit =
        runBlocking {
            Fixture("type Query { field: String }") {
                val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
                oer.fieldResolutionState.complete(Unit)

                val neverCompletingChecker = CompletableDeferred<CheckerResult?>()
                oer.computeIfAbsent(ObjectEngineResult.Key("field")) { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(FieldResolutionResult("value", emptyList(), CompositeLocalContext.empty, emptyMap(), "field"))
                    )
                    slotSetter.setCheckerValue(Value.fromDeferred(neverCompletingChecker))
                }

                val selectionSet = mkSelectionSet("Query", "field")

                assertThrows<TimeoutCancellationException> {
                    withTimeout(200) {
                        SyncEngineObjectDataFactory.resolve(
                            oer,
                            "error",
                            selectionSet,
                            skipAccessCheck = false,
                        )
                    }
                }
            }
        }

    companion object {
        data class OerWithListFieldError(
            val oer: ObjectEngineResultImpl,
            val error: GraphQLError,
        )

        fun mkOerWithListFieldError(queryType: GraphQLObjectType): OerWithListFieldError {
            val oer = ObjectEngineResultImpl.newForType(queryType)
            val err =
                ValidationError.newValidationError()
                    .validationErrorType(ValidationErrorType.WrongType)
                    .description("Test error")
                    .build()

            val listWithError = listOf(
                newCell { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(
                            FieldResolutionResult("ok", emptyList(), CompositeLocalContext.empty, emptyMap(), "ok")
                        )
                    )
                    slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
                },
                newCell { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(
                            FieldResolutionResult(null, listOf(err), CompositeLocalContext.empty, emptyMap(), "error")
                        )
                    )
                    slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
                },
                newCell { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(
                            FieldResolutionResult("also ok", emptyList(), CompositeLocalContext.empty, emptyMap(), "also ok")
                        )
                    )
                    slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
                }
            )

            oer.computeIfAbsent(ObjectEngineResult.Key("listField")) { slotSetter ->
                slotSetter.setRawValue(
                    Value.fromValue(
                        FieldResolutionResult(listWithError, emptyList(), CompositeLocalContext.empty, emptyMap(), "listField")
                    )
                )
                slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
            }

            return OerWithListFieldError(oer, err)
        }
    }
}
