@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.validation.ValidationError
import kotlin.test.assertContains
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.ObjectEngineResult
import viaduct.engine.api.UnsetSelectionException
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.runtime.CheckerProxyEngineObjectData
import viaduct.engine.runtime.CompositeLocalContext
import viaduct.engine.runtime.FieldErrorsException
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setCheckerValue
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setRawValue
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.mkRss
import viaduct.engine.runtime.mkSchema
import viaduct.engine.runtime.select.RawSelectionSetFactoryImpl

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyEngineObjectDataTest {
    private inner class Fixture(sdl: String, test: suspend Fixture.() -> Unit) {
        val schema = mkSchema(sdl)

        private val selectionSetFactory = RawSelectionSetFactoryImpl(schema)

        fun mkOER(
            typename: String,
            resultMap: Map<String, Any?> = emptyMap(),
            errors: List<Pair<String, Throwable>> = emptyList(),
            variables: Map<String, Any?> = emptyMap(),
            selections: String = "id"
        ): ObjectEngineResultImpl =
            ObjectEngineResultImpl.newFromMap(
                schema.schema.getObjectType(typename),
                resultMap,
                errors.toMutableList(),
                emptyList(),
                schema,
                mkRss(typename, selections, variables, schema)
            )

        fun mkProxy(
            fragment: String?,
            typename: String,
            resultMap: Map<String, Any?> = emptyMap(),
            errors: List<Pair<String, Throwable>> = emptyList(),
            variables: Map<String, Any?> = emptyMap()
        ): ProxyEngineObjectData {
            val selectionSet =
                fragment?.let {
                    selectionSetFactory.rawSelectionSet(typename, fragment, variables)
                }
            val oer = ObjectEngineResultImpl.newFromMap(
                schema.schema.getObjectType(typename),
                resultMap,
                errors.toMutableList(),
                emptyList(),
                schema,
                selectionSet ?: mkRss(typename, "id", emptyMap(), schema)
            )
            return ProxyEngineObjectData(oer, "error msg", selectionSet)
        }

        @JvmName("mkProxy2")
        fun mkProxy(
            fragment: String?,
            typename: String,
            resultMap: Map<ObjectEngineResult.Key, Any?> = emptyMap(),
            errors: List<Pair<ObjectEngineResult.Key, Throwable>> = emptyList(),
            variables: Map<String, Any?> = emptyMap()
        ): ProxyEngineObjectData {
            val selectionSet =
                fragment?.let {
                    selectionSetFactory.rawSelectionSet(typename, fragment, variables)
                }
            val oer = ObjectEngineResultImpl.newFromMap(
                schema.schema.getObjectType(typename),
                resultMap,
                errors.toMutableList(),
                emptyList(),
                schema,
                selectionSet ?: mkRss(typename, "id", emptyMap(), schema)
            )
            return ProxyEngineObjectData(oer, "error", selectionSet)
        }

        @JvmName("mkProxy3")
        fun mkProxy(
            fragment: String?,
            oer: ObjectEngineResult,
            variables: Map<String, Any?> = emptyMap(),
            applyAccessChecks: Boolean = true,
        ): ProxyEngineObjectData {
            val selectionSet =
                fragment?.let {
                    selectionSetFactory.rawSelectionSet(oer.graphQLObjectType.name, fragment, variables)
                }
            if (!applyAccessChecks) {
                return CheckerProxyEngineObjectData(oer, "error", selectionSet)
            }
            return ProxyEngineObjectData(oer, "error", selectionSet)
        }

        init {
            runBlocking {
                test()
            }
        }
    }

    @Test
    fun `test required selections`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { stringField: String, object2: O2, listField: [Int] }
                type O2 { intField: Int }
            """.trimIndent()
        ) {
            val o1 = mkProxy(
                """
                fragment _ on O1 {
                    stringField
                    object2 { intField }
                }
                """.trimIndent(),
                "O1",
                mapOf(
                    "stringField" to "hello",
                    "object2" to mapOf("intField" to 1)
                ),
            )
            assertEquals("hello", o1.fetch("stringField"))
            assertThrows<UnsetSelectionException> { o1.fetch("listField") }
            assertEquals(1, (o1.fetch("object2") as ProxyEngineObjectData).fetch("intField"))
            assertThrows<UnsetSelectionException> { (o1.fetch("object2") as ProxyEngineObjectData).fetch("object1") }
        }
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `fetch list required selections`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { stringField: String, listField: [[O2]] }
                type O2 { object1: O1 }
            """.trimIndent()
        ) {
            val o1 = mkProxy(
                """
                fragment _ on O1 {
                    listField {
                        object1 {
                            stringField
                        }
                    }
               }
                """.trimIndent(),
                "O1",
                mapOf(
                    "listField" to
                        listOf(
                            listOf(
                                null,
                                mapOf("object1" to mapOf("stringField" to "hello"))
                            ),
                            null
                        )
                )
            )

            val listField = o1.fetch("listField") as List<List<ProxyEngineObjectData?>?>
            val innerList1 = listField[0]!!
            assertEquals(null, innerList1[0])

            val obj2 = innerList1[1]!!
            val obj1 = obj2.fetch("object1") as ProxyEngineObjectData
            assertEquals("hello", obj1.fetch("stringField"))
            assertThrows<UnsetSelectionException> { obj1.fetch("listField") }
            assertEquals(null, listField[1])
        }
    }

    @Test
    fun `fetch aliased selections`() {
        Fixture("type Query { x: Int }") {
            val o = mkProxy(
                "fragment _ on Query { x1: x, x2: x }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("x", "x1") to 2,
                    ObjectEngineResult.Key("x", "x2") to 2,
                )
            )

            assertEquals(2, o.fetch("x1"))
            assertEquals(2, o.fetch("x2"))
            // alias x3 is not selected
            assertThrows<UnsetSelectionException> { o.fetch("x3") }
            // the unaliased "x" field is not selected
            assertThrows<UnsetSelectionException> { o.fetch("x") }
        }
    }

    @Test
    fun `fetch argumented selection`() {
        Fixture("type Query { field(x: Int): Int }") {
            val o = mkProxy(
                "fragment _ on Query { field(x: 1) }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("field", null, mapOf("x" to 1)) to 2,
                )
            )
            assertEquals(2, o.fetch("field"))
        }
    }

    @Test
    fun `fetch argumented selection -- default value`() {
        Fixture("type Query { field(x: Int = 1): Int }") {
            val o = mkProxy(
                "fragment _ on Query { field }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("field", null, mapOf("x" to 1)) to 2
                ),
            )
            assertEquals(2, o.fetch("field"))
        }
    }

    @Test
    fun `fetch argumented selection -- default value with explicit null`() {
        Fixture("type Query { field(x: Int = 1): Int }") {
            val o = mkProxy(
                "fragment _ on Query { field(x:null) }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("field", null, mapOf("x" to null)) to 2,
                )
            )
            assertEquals(2, o.fetch("field"))
        }
    }

    @Test
    fun `fetch argumented selection -- variable value`() {
        Fixture("type Query { field(x: Int): Int }") {
            val o = mkProxy(
                "fragment _ on Query { field(x:\$varx) }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("field", null, mapOf("x" to 1)) to 2,
                ),
                variables = mapOf("varx" to 1)
            )
            assertEquals(2, o.fetch("field"))
        }
    }

    @Test
    fun `fetch statically included selections`() {
        Fixture("type Query { f1:Int, f2:Int }") {
            val o = mkProxy(
                """
                    fragment _ on Query {
                      f1 @skip(if:false)
                      f2 @include(if:true)
                    }
                """.trimIndent(),
                "Query",
                mapOf("f1" to 1, "f2" to 2)
            )
            assertEquals(1, o.fetch("f1"))
            assertEquals(2, o.fetch("f2"))
        }
    }

    @Test
    fun `fetch statically excluded selections`() {
        Fixture("type Query { f1:Int, f2:Int }") {
            val o = mkProxy(
                """
                    fragment _ on Query {
                      f1 @skip(if:true)
                      f2 @include(if:false)
                    }
                """.trimIndent(),
                "Query",
                mapOf("f1" to 1, "f2" to 2)
            )
            assertThrows<UnsetSelectionException> { o.fetch("f1") }
            assertThrows<UnsetSelectionException> { o.fetch("f2") }
        }
    }

    @Test
    fun `fetch dynamically included selections`() {
        Fixture("type Query { f1:Int, f2:Int }") {
            val o = mkProxy(
                """
                    fragment _ on Query {
                      f1 @skip(if:${'$'}skipIf)
                      f2 @include(if:${'$'}includeIf)
                    }
                """.trimIndent(),
                "Query",
                mapOf("f1" to 1, "f2" to 2),
                emptyList(),
                mapOf("skipIf" to false, "includeIf" to true)
            )
            assertEquals(1, o.fetch("f1"))
            assertEquals(2, o.fetch("f2"))
        }
    }

    @Test
    fun `fetch dynamically excluded selections`() {
        Fixture("type Query { f1:Int, f2:Int }") {
            val o = mkProxy(
                """
                    fragment _ on Query {
                      f1 @skip(if:${'$'}skipIf)
                      f2 @include(if:${'$'}includeIf)
                    }
                """.trimIndent(),
                "Query",
                mapOf("f1" to 1, "f2" to 2),
                emptyList(),
                mapOf("skipIf" to true, "includeIf" to false)
            )
            assertThrows<UnsetSelectionException> { o.fetch("f1") }
            assertThrows<UnsetSelectionException> { o.fetch("f2") }
        }
    }

    @Test
    fun `fetch argumented selection -- aliases and variables`() {
        Fixture("type Query { field(x: Int): Int }") {
            val o = mkProxy(
                "fragment _ on Query { f1:field(x:\$x1), f2:field(x:\$x2) }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("field", "f1", mapOf("x" to 1)) to 11,
                    ObjectEngineResult.Key("field", "f2", mapOf("x" to 2)) to 12,
                ),
                emptyList(),
                variables = mapOf("x1" to 1, "x2" to 2)
            )
            assertEquals(11, o.fetch("f1"))
            assertEquals(12, o.fetch("f2"))
            // unaliased field is not selected
            assertThrows<UnsetSelectionException> { o.fetch("field") }
        }
    }

    @Test
    fun `fetch invalid field`() {
        Fixture("type Query { x: Int }") {
            val o1 = mkProxy(null, "Query", emptyMap<String, Any>())
            val e = assertThrows<UnsetSelectionException> { o1.fetch("invalidField") }
            assertContains(e.message, "error msg")
        }
    }

    @Test
    fun `fetch bubbles up exceptions`() {
        Fixture("type Query { stringField: String }") {
            val err = object : Exception() {}
            val proxy = mkProxy(
                "stringField",
                "Query",
                mapOf("stringField" to null),
                listOf("stringField" to err)
            )

            val e2 = assertThrows<Exception> {
                proxy.fetch("stringField")
            }
            assertSame(err, e2)
        }
    }

    @Test
    fun `fetch marshals a FieldResolutionResult`() {
        Fixture("type Query { x: String }") {
            val oer = mkOER("Query")
            ObjectEngineResult.Key("x").also { key ->
                oer.computeIfAbsent(key) { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(
                            FieldResolutionResult("foo", emptyList(), CompositeLocalContext.empty, emptyMap(), "foo")
                        )
                    )
                    slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
                }
            }
            val proxy = mkProxy("x", oer)
            assertEquals("foo", proxy.fetch("x"))
        }
    }

    @Test
    fun `fetch recursively marshals FieldResolutionResult values`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { intField: Int! }
            """.trimIndent()
        ) {
            val oer = mkOER("O1")

            ObjectEngineResult.Key("object2").also { key ->
                oer.computeIfAbsent(key) { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(
                            FieldResolutionResult(
                                mkOER(
                                    "O2",
                                    mapOf("intField" to 42),
                                    selections = "intField"
                                ),
                                emptyList(),
                                CompositeLocalContext.empty,
                                emptyMap(),
                                "object2"
                            )
                        )
                    )
                    slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
                }
            }
            val proxy = mkProxy("object2 { intField }", oer)
            val intField = (proxy.fetch("object2") as ProxyEngineObjectData)
                .fetch("intField")
            assertEquals(42, intField)
        }
    }

    @Test
    fun `fetch throws errors in FieldResolutionResult`() {
        Fixture("type Query { stringField: String }") {
            val oer = mkOER(typename = "Query")
            val err = ValidationError.newValidationError().build()

            ObjectEngineResult.Key("stringField").also { key ->
                oer.computeIfAbsent(key) { slotSetter ->
                    slotSetter.setRawValue(
                        Value.fromValue(
                            FieldResolutionResult(
                                null,
                                listOf(err),
                                CompositeLocalContext.empty,
                                emptyMap(),
                                "foo"
                            )
                        )
                    )
                    slotSetter.setCheckerValue(Value.fromValue(CheckerResult.Success))
                }
            }
            val proxy = mkProxy("stringField", oer)
            val exc = assertThrows<FieldErrorsException> {
                proxy.fetch("stringField")
            }
            assertEquals(listOf(err), exc.graphQLErrors)
        }
    }

    @Test
    fun `access checks applied when applyAccessChecks is true`() {
        Fixture("type Query { stringField: String }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
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
                slotSetter.setCheckerValue(Value.fromValue(MockCheckerErrorResult(IllegalAccessException("no access"))))
            }
            val proxy = mkProxy("stringField", oer)
            assertThrows<IllegalAccessException> {
                proxy.fetch("stringField")
            }
        }
    }

    @Test
    fun `access check slot not fetched when applyAccessChecks is false`() {
        Fixture("type Query { stringField: String intField: Int }") {
            val oer = ObjectEngineResultImpl.newForType(schema.schema.getObjectType("Query"))
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
                slotSetter.setCheckerValue(Value.fromThrowable(IllegalAccessException("no access")))
            }
            val proxy = mkProxy("stringField", oer, applyAccessChecks = false)
            // If fetch were called on the access check slot, this would throw
            assertEquals("foo", proxy.fetch("stringField"))
        }
    }
}
