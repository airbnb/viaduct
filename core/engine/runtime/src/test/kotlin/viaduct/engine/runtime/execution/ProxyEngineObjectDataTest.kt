@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime.execution

import graphql.GraphQLError
import graphql.schema.GraphQLObjectType
import graphql.validation.ValidationError
import graphql.validation.ValidationErrorType
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertContains
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.CheckerResult
import viaduct.engine.api.mocks.MockCheckerErrorResult
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldErrorsException
import viaduct.engine.runtime.FieldResolutionResult
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.IsResolverSelective
import viaduct.engine.runtime.ObjectEngineResult
import viaduct.engine.runtime.ObjectEngineResultImpl
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.newCell
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setCheckerValue
import viaduct.engine.runtime.ObjectEngineResultImpl.Companion.setRawValue
import viaduct.engine.runtime.ObjectEngineResultTestHelper
import viaduct.engine.runtime.ProxyEngineObjectData
import viaduct.engine.runtime.Value
import viaduct.engine.runtime.context.CompositeLocalContext
import viaduct.engine.runtime.createEngineSelectionSet
import viaduct.engine.runtime.createSchema
import viaduct.engine.runtime.select.EngineSelectionSetFactoryImpl
import viaduct.errors.UnsetFieldException

@OptIn(ExperimentalCoroutinesApi::class)
class ProxyEngineObjectDataTest {
    /**
     * Test implementation of [ObjectEngineResult.Selections] that maps response keys
     * to child selections. Uses referential equality for OER key matching.
     */
    private class TestSelections(
        private val children: Map<String, TestSelections> = emptyMap()
    ) : ObjectEngineResult.Selections {
        override fun selectionSetForSelection(
            parentType: GraphQLObjectType,
            responseKey: String
        ): TestSelections? = children[responseKey]
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

        fun mkProxy(
            fragment: String?,
            typename: String,
            resultMap: Map<String, Any?> = emptyMap(),
            errors: List<Pair<String, Throwable>> = emptyList(),
            variables: Map<String, Any?> = emptyMap(),
            isResolverSelective: IsResolverSelective = IsResolverSelective.Never,
            selections: ObjectEngineResult.Selections? = null,
        ): ProxyEngineObjectData {
            val selectionSet =
                fragment?.let {
                    selectionSetFactory.engineSelectionSet(typename, fragment, variables)
                }
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType(typename),
                resultMap,
                errors.toMutableList(),
                emptyList(),
                schema,
                selectionSet ?: createEngineSelectionSet(typename, "id", emptyMap(), schema)
            )
            return ProxyEngineObjectData(oer, "error msg", selectionSet, isResolverSelective, selections)
        }

        @JvmName("mkProxy2")
        fun mkProxy(
            fragment: String?,
            typename: String,
            resultMap: Map<ObjectEngineResult.Key, Any?> = emptyMap(),
            errors: List<Pair<ObjectEngineResult.Key, Throwable>> = emptyList(),
            variables: Map<String, Any?> = emptyMap(),
            isResolverSelective: IsResolverSelective = IsResolverSelective.Never,
            selections: ObjectEngineResult.Selections? = null,
        ): ProxyEngineObjectData {
            val selectionSet =
                fragment?.let {
                    selectionSetFactory.engineSelectionSet(typename, fragment, variables)
                }
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType(typename),
                resultMap,
                errors.toMutableList(),
                emptyList(),
                schema,
                selectionSet ?: createEngineSelectionSet(typename, "id", emptyMap(), schema)
            )
            return ProxyEngineObjectData(oer, "error", selectionSet, isResolverSelective, selections)
        }

        @JvmName("mkProxy3")
        fun mkProxy(
            fragment: String?,
            oer: ObjectEngineResult,
            variables: Map<String, Any?> = emptyMap(),
            isResolverSelective: IsResolverSelective = IsResolverSelective.Never,
            selections: ObjectEngineResult.Selections? = null,
        ): ProxyEngineObjectData {
            val selectionSet =
                fragment?.let {
                    selectionSetFactory.engineSelectionSet(oer.type.name, fragment, variables)
                }
            return ProxyEngineObjectData(oer, "error", selectionSet, isResolverSelective, selections)
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
            assertThrows<UnsetFieldException> { o1.fetch("listField") }
            assertEquals(1, (o1.fetch("object2") as ProxyEngineObjectData).fetch("intField"))
            assertThrows<UnsetFieldException> { (o1.fetch("object2") as ProxyEngineObjectData).fetch("object1") }

            // fetchSelections should return only the selected fields
            assertEquals(setOf("stringField", "object2"), o1.fetchSelections().toSet())
            val o2 = o1.fetch("object2") as ProxyEngineObjectData
            assertEquals(setOf("intField"), o2.fetchSelections().toSet())
        }
    }

    @Test
    fun `fetch selective nested object uses selection-set-aware key`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { intField: Int, otherField: Int }
            """.trimIndent()
        ) {
            // Two different selection identity objects for "object2" sub-selections
            val requestedSubSelections = TestSelections()
            val otherSubSelections = TestSelections()
            val parentSelections = TestSelections(mapOf("object2" to requestedSubSelections))

            val o1 = mkProxy(
                "fragment _ on O1 { object2 { intField } }",
                "O1",
                mapOf(
                    ObjectEngineResult.Key(
                        "object2",
                        selectionSet = requestedSubSelections
                    ) to mapOf("intField" to 1),
                    ObjectEngineResult.Key(
                        "object2",
                        selectionSet = otherSubSelections
                    ) to mapOf("otherField" to 2)
                ),
                isResolverSelective = IsResolverSelective.Always,
                selections = parentSelections,
            )

            val o2 = o1.fetch("object2") as ProxyEngineObjectData
            assertEquals(1, o2.fetch("intField"))
            assertThrows<UnsetFieldException> { o2.fetch("otherField") }
        }
    }

    @Test
    fun `fetch selective field times out when stored and read selection identities differ`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { otherField: Int }
            """.trimIndent()
        ) {
            // Store with one selection identity, but parent provides a different one
            val storedSubSelections = TestSelections()
            val readSubSelections = TestSelections()
            val parentSelections = TestSelections(mapOf("object2" to readSubSelections))

            val o1 = mkProxy(
                "object2 { otherField }",
                "O1",
                mapOf(
                    ObjectEngineResult.Key(
                        "object2",
                        selectionSet = storedSubSelections
                    ) to mapOf("clientField" to 2)
                ),
                isResolverSelective = IsResolverSelective.Always,
                selections = parentSelections,
            )

            assertThrows<TimeoutCancellationException> {
                withTimeout(1000) { o1.fetch("object2") }
            }
        }
    }

    @Test
    fun `fetch selective interface field uses concrete type coordinate for key selectivity`() {
        Fixture(
            """
                type Query { empty: Int }
                interface Container { profile: Profile }
                type ConcreteContainer implements Container { profile: Profile }
                type Profile { name: String }
            """.trimIndent()
        ) {
            val interfaceSelectionSet = mkSelectionSet("Container", "profile { name }")
            val profileSubSelections = TestSelections()
            val parentSelections = TestSelections(mapOf("profile" to profileSubSelections))
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("ConcreteContainer"),
                mapOf(
                    ObjectEngineResult.Key(
                        "profile",
                        selectionSet = profileSubSelections
                    ) to mapOf("name" to "Ada")
                ),
                mutableListOf(),
                emptyList(),
                schema,
                interfaceSelectionSet
            )
            val proxy = ProxyEngineObjectData(
                oer,
                "error",
                interfaceSelectionSet,
                isResolverSelective = IsResolverSelective.fromRegistry(
                    DispatcherRegistry.Impl(
                        fieldResolverDispatchers = mapOf(
                            ("ConcreteContainer" to "profile") to mockk<FieldResolverDispatcher> {
                                every { isSelective } returns true
                            }
                        ),
                        nodeResolverDispatchers = emptyMap(),
                        fieldCheckerDispatchers = emptyMap(),
                        typeCheckerDispatchers = emptyMap(),
                    ),
                    true,
                ),
                selections = parentSelections,
            )

            val profile = withTimeout(1000) { proxy.fetch("profile") as ProxyEngineObjectData }

            assertEquals("Ada", withTimeout(1000) { profile.fetch("name") })
        }
    }

    @Test
    fun `fetch selective interface field ignores abstract type condition for key selectivity`() {
        Fixture(
            """
                type Query { empty: Int }
                interface Container { profile: Profile }
                type ConcreteContainer implements Container { profile: Profile }
                type Profile { name: String }
            """.trimIndent()
        ) {
            val interfaceSelectionSet = mkSelectionSet("Container", "profile { name }")
            val profileSubSelections = TestSelections()
            val parentSelections = TestSelections(mapOf("profile" to profileSubSelections))
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("ConcreteContainer"),
                mapOf(
                    ObjectEngineResult.Key("profile") to mapOf("name" to "Ada")
                ),
                mutableListOf(),
                emptyList(),
                schema,
                interfaceSelectionSet
            )
            val proxy = ProxyEngineObjectData(
                oer,
                "error",
                interfaceSelectionSet,
                isResolverSelective = IsResolverSelective.fromRegistry(
                    DispatcherRegistry.Impl(
                        fieldResolverDispatchers = mapOf(
                            ("Container" to "profile") to mockk<FieldResolverDispatcher> {
                                every { isSelective } returns true
                            }
                        ),
                        nodeResolverDispatchers = emptyMap(),
                        fieldCheckerDispatchers = emptyMap(),
                        typeCheckerDispatchers = emptyMap(),
                    ),
                    true
                ),
                selections = parentSelections,
            )

            val profile = withTimeout(1000) { proxy.fetch("profile") as ProxyEngineObjectData }

            assertEquals("Ada", withTimeout(1000) { profile.fetch("name") })
        }
    }

    @Test
    fun `fetch selective field requires selections on proxy to match write-side key`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { intField: Int }
            """.trimIndent()
        ) {
            val object2SubSelections = TestSelections()

            // Write-side: store with selective key (non-null selections)
            val o1 = mkProxy(
                "fragment _ on O1 { object2 { intField } }",
                "O1",
                mapOf(
                    ObjectEngineResult.Key(
                        "object2",
                        selectionSet = object2SubSelections
                    ) to mapOf("intField" to 42)
                ),
                isResolverSelective = IsResolverSelective.Always,
                // Proxy WITHOUT selections — simulates queryValue today
                selections = null,
            )

            // Read-side: proxy has no selections, so it builds a key with null selections.
            // The OER only has an entry with non-null selections. This should time out.
            assertThrows<TimeoutCancellationException> {
                withTimeout(200) { o1.fetch("object2") }
            }
        }
    }

    @Test
    fun `fetch selective field succeeds when proxy has matching selections`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { intField: Int }
            """.trimIndent()
        ) {
            val object2SubSelections = TestSelections()
            val parentSelections = TestSelections(mapOf("object2" to object2SubSelections))

            val o1 = mkProxy(
                "fragment _ on O1 { object2 { intField } }",
                "O1",
                mapOf(
                    ObjectEngineResult.Key(
                        "object2",
                        selectionSet = object2SubSelections
                    ) to mapOf("intField" to 42)
                ),
                isResolverSelective = IsResolverSelective.Always,
                // Proxy WITH selections — simulates the fix
                selections = parentSelections,
            )

            val o2 = o1.fetch("object2") as ProxyEngineObjectData
            assertEquals(42, o2.fetch("intField"))
        }
    }

    @Test
    fun `fetch selective field at second nesting level traverses child selections`() {
        Fixture(
            """
                type Query { root: O1, empty: Int }
                type O1 { object2: O2 }
                type O2 { object3: O3 }
                type O3 { value: Int }
            """.trimIndent()
        ) {
            val object3Selections = TestSelections()
            val object2Selections = TestSelections(mapOf("object3" to object3Selections))
            val o1Selections = TestSelections(mapOf("object2" to object2Selections))

            val o1 = mkProxy(
                "fragment _ on O1 { object2 { object3 { value } } }",
                "O1",
                mapOf(
                    ObjectEngineResult.Key(
                        "object2",
                        selectionSet = object2Selections
                    ) to mapOf(
                        ObjectEngineResult.Key(
                            "object3",
                            selectionSet = object3Selections
                        ) to mapOf("value" to 99)
                    ),
                ),
                isResolverSelective = IsResolverSelective.Always,
                selections = o1Selections,
            )

            val o2 = o1.fetch("object2") as ProxyEngineObjectData
            val o3 = withTimeout(200) { o2.fetch("object3") } as ProxyEngineObjectData
            assertEquals(99, withTimeout(200) { o3.fetch("value") })
        }
    }

    @Test
    fun `fetch non-selective nested object ignores selection set in key`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { intField: Int }
            """.trimIndent()
        ) {
            val o1 = mkProxy(
                "fragment _ on O1 { object2 { intField } }",
                "O1",
                mapOf(
                    ObjectEngineResult.Key("object2") to mapOf("intField" to 1)
                ),
                isResolverSelective = IsResolverSelective.Never,
            )

            val o2 = o1.fetch("object2") as ProxyEngineObjectData
            assertEquals(1, o2.fetch("intField"))
        }
    }

    @Test
    fun `fetch preserves exact alias match when lookup selection set contains ambiguous candidates`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { bar: Int }
            """.trimIndent()
        ) {
            val proxy = mkProxy(
                "bar aliasedBar: bar",
                "O1",
                mapOf(
                    ObjectEngineResult.Key("bar") to 1,
                    ObjectEngineResult.Key("bar", alias = "aliasedBar") to 2,
                ),
            )

            assertEquals(1, proxy.fetch("bar"))
            assertEquals(2, proxy.fetch("aliasedBar"))
        }
    }

    @Test
    fun `fetch preserves exact alias match when visible selection is narrower than lookup selection set`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { bar: Int }
            """.trimIndent()
        ) {
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

            val proxy = mkProxy(
                "aliasedBar: bar",
                oer,
            )

            assertEquals(2, proxy.fetch("aliasedBar"))
            assertEquals(setOf("aliasedBar"), proxy.fetchSelections().toSet())
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
            assertThrows<UnsetFieldException> { obj1.fetch("listField") }
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
            assertThrows<UnsetFieldException> { o.fetch("x3") }
            // the unaliased "x" field is not selected
            assertThrows<UnsetFieldException> { o.fetch("x") }

            // fetchSelections should return the aliases, not the field name
            assertEquals(setOf("x1", "x2"), o.fetchSelections().toSet())
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

            // fetchSelections should include directives that evaluate to true
            assertEquals(setOf("f1", "f2"), o.fetchSelections().toSet())
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
            assertThrows<UnsetFieldException> { o.fetch("f1") }
            assertThrows<UnsetFieldException> { o.fetch("f2") }

            // fetchSelections should not include directives that evaluate to false
            assertEquals(emptySet<String>(), o.fetchSelections().toSet())
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

            // fetchSelections should include dynamic directives that evaluate to true
            assertEquals(setOf("f1", "f2"), o.fetchSelections().toSet())
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
            assertThrows<UnsetFieldException> { o.fetch("f1") }
            assertThrows<UnsetFieldException> { o.fetch("f2") }

            // fetchSelections should not include dynamic directives that evaluate to false
            assertEquals(emptySet<String>(), o.fetchSelections().toSet())
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
            assertThrows<UnsetFieldException> { o.fetch("field") }
        }
    }

    @Test
    fun `fetch preserves exact alias and arguments match when visible selection is narrower than lookup selection set`() {
        Fixture("type Query { field(x: Int): Int }") {
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

            val proxy = mkProxy(
                "aliasedField: field(x: 1)",
                oer,
            )

            assertEquals(2, proxy.fetch("aliasedField"))
            assertEquals(setOf("aliasedField"), proxy.fetchSelections().toSet())
        }
    }

    @Test
    fun `fetch nested proxy preserves exact alias and arguments match when visible and stored selection shapes match`() {
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
            val object2SubSelections = TestSelections()
            val parentSelections = TestSelections(mapOf("object2" to object2SubSelections))
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("O1"),
                mapOf(
                    ObjectEngineResult.Key("object2", selectionSet = object2SubSelections) to mapOf(
                        ObjectEngineResult.Key("field", "aliasedField", mapOf("x" to 1)) to 2,
                    )
                ),
                mutableListOf(),
                emptyList(),
                schema,
                lookupSelectionSet
            )

            val proxy = mkProxy(
                "object2 { aliasedField: field(x: 1) }",
                oer,
                isResolverSelective = IsResolverSelective.Always,
                selections = parentSelections,
            )

            val object2 = proxy.fetch("object2") as ProxyEngineObjectData

            assertEquals(2, object2.fetch("aliasedField"))
            assertEquals(setOf("aliasedField"), object2.fetchSelections().toSet())
        }
    }

    @Test
    fun `fetch nested proxy times out when visible and stored selection shapes differ`() {
        Fixture(
            """
                type Query { empty: Int }
                type O1 { object2: O2 }
                type O2 { field(x: Int): Int }
            """.trimIndent()
        ) {
            val lookupSelectionSet = mkSelectionSet(
                "O1",
                "object2 { field(x: 1) aliasedField: field(x: 1) otherArg: field(x: 2) }"
            )
            val storedObject2SubSelections = TestSelections()
            val readObject2SubSelections = TestSelections()
            val parentSelections = TestSelections(mapOf("object2" to readObject2SubSelections))
            val oer = ObjectEngineResultTestHelper.newFromMap(
                schema.schema.getObjectType("O1"),
                mapOf(
                    ObjectEngineResult.Key("object2", selectionSet = storedObject2SubSelections) to mapOf(
                        ObjectEngineResult.Key("field", null, mapOf("x" to 1)) to 1,
                        ObjectEngineResult.Key("field", "aliasedField", mapOf("x" to 1)) to 2,
                        ObjectEngineResult.Key("field", "otherArg", mapOf("x" to 2)) to 3,
                    )
                ),
                mutableListOf(),
                emptyList(),
                schema,
                lookupSelectionSet
            )

            val proxy = mkProxy(
                "object2 { aliasedField: field(x: 1) }",
                oer,
                isResolverSelective = IsResolverSelective.Always,
                selections = parentSelections,
            )

            assertThrows<TimeoutCancellationException> {
                withTimeout(1000) { proxy.fetch("object2") }
            }
        }
    }

    @Test
    fun `fetch invalid field`() {
        Fixture("type Query { x: Int }") {
            val o1 = mkProxy(null, "Query", emptyMap<String, Any>())
            val e = assertThrows<UnsetFieldException> { o1.fetch("invalidField") }
            assertContains(e.message!!, "error msg")

            // fetchSelections should return empty when no fragment is provided
            assertEquals(emptySet<String>(), o1.fetchSelections().toSet())
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
            val err =
                ValidationError.newValidationError()
                    .validationErrorType(ValidationErrorType.WrongType)
                    .description("Test error")
                    .build()

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
    fun `fetch list throws on first element error`() {
        Fixture("type Query { listField: [String] }") {
            val (oer, err) = mkOerWithListFieldError(schema.schema.getObjectType("Query"))

            val proxy = mkProxy("listField", oer)
            val exc = assertThrows<FieldErrorsException> {
                proxy.fetch("listField")
            }
            assertEquals(listOf(err), exc.graphQLErrors)
        }
    }

    @Test
    fun `regression -- can fetch introspection fields`() {
        Fixture("type Query { x:Int }") {
            // __typename
            mkProxy(
                "__typename, a:__typename",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("__typename") to "Query",
                    ObjectEngineResult.Key("__typename", "a") to "Query",
                )
            ).let { proxy ->
                assertEquals("Query", proxy.fetch("__typename"))
                assertEquals("Query", proxy.fetch("a"))
            }

            // __schema
            mkProxy(
                "__schema { __typename }, a:__schema { __typename }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key("__schema") to emptyMap<String, Any?>(),
                    ObjectEngineResult.Key("__schema", "a") to emptyMap<String, Any?>()
                )
            ).let { proxy ->
                assertInstanceOf(ProxyEngineObjectData::class.java, proxy.fetch("__schema"))
                assertInstanceOf(ProxyEngineObjectData::class.java, proxy.fetch("a"))
            }

            // __type
            mkProxy(
                "__type(name:\"__Schema\") { __typename  }, a:__type(name:\"__Schema\") { __typename  }",
                "Query",
                mapOf(
                    ObjectEngineResult.Key(
                        "__type",
                        arguments = mapOf("name" to "__Schema")
                    ) to emptyMap<String, Any?>(),
                    ObjectEngineResult.Key(
                        "__type",
                        "a",
                        mapOf("name" to "__Schema")
                    ) to emptyMap<String, Any?>()
                )
            ).let { proxy ->
                assertInstanceOf(ProxyEngineObjectData::class.java, proxy.fetch("__type"))
            }
        }
    }

    companion object {
        /**
         * Test data for list-with-error tests. Contains an OER with a "listField" where
         * element 1 (middle element) has a FieldResolutionResult error.
         */
        data class OerWithListFieldError(
            val oer: ObjectEngineResultImpl,
            val error: GraphQLError,
        )

        /**
         * Creates an OER with a "listField" containing 3 elements where the middle
         * element has an error. Used to verify that both ProxyEngineObjectData and
         * SyncEngineObjectDataFactory handle list element errors identically.
         */
        fun mkOerWithListFieldError(queryType: GraphQLObjectType): OerWithListFieldError {
            val oer = ObjectEngineResultImpl.newForType(queryType)
            val err =
                ValidationError.newValidationError()
                    .validationErrorType(ValidationErrorType.WrongType)
                    .description("Test error")
                    .build()

            // Create a list where element 1 (the middle one) has an error
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
