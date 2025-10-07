@file:Suppress("ForbiddenImport")

package viaduct.api.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ExceptionsForTesting
import viaduct.api.ViaductFrameworkException
import viaduct.api.ViaductTenantException
import viaduct.api.ViaductTenantUsageException
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.E1
import viaduct.api.testschema.I1
import viaduct.api.testschema.O1
import viaduct.api.testschema.O2
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.EngineObjectDataBuilder
import viaduct.engine.api.NodeReference
import viaduct.engine.api.UnsetSelectionException

@OptIn(ExperimentalCoroutinesApi::class)
class ObjectBaseTest {
    private val gqlSchema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.mk(gqlSchema, "viaduct.api.testschema")
    private val executionContext = internalContext.executionContext

    // Use viaduct.api.testschema.E1 for the actual E1 value. This is for a regression test and represents a different version (classic)
    // of the GRT for the same GraphQL enum type.
    private enum class BadE1 { A }

    @Test
    fun `basic test with builder`(): Unit =
        runBlocking {
            val o1 =
                O1.Builder(executionContext)
                    .stringField("hello")
                    .objectField(
                        O2.Builder(executionContext)
                            .intField(1)
                            .build()
                    )
                    .enumField(E1.A)
                    .build()

            assertEquals("hello", o1.getStringField())
            assertEquals(1, o1.getObjectField()!!.getIntField())
            assertEquals(E1.A, o1.getEnumField())
            assertThrows<ViaductTenantUsageException> {
                runBlocking {
                    o1.getObjectField()!!.getObjectField()
                }
            }
            assertInstanceOf(EngineObjectData::class.java, (o1.engineObject as EngineObjectData).fetch("objectField"))
        }

    @Test
    fun `fetch -- aliased scalar`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                    .put("aliasedStringField", "ALIASED")
                    .build()
            )
            assertEquals("ALIASED", o1.getStringField("aliasedStringField"))
        }

    @Test
    fun `fetch -- aliased object field`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                    .put(
                        "aliasedObjectField",
                        EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                            .put("aliasedIntField", 42)
                            .build()
                    )
                    .build()
            )
            assertEquals(42, o1.getObjectField("aliasedObjectField")?.getIntField("aliasedIntField"))
        }

    @Test
    fun `fetch -- aliased list of objects`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                    .put(
                        "aliasedListField",
                        listOf(
                            listOf(
                                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                    .put("intField", 42)
                                    .build()
                            )
                        )
                    )
                    .build()
            )
            assertEquals(42, o1.getListField("aliasedListField")?.get(0)?.get(0)?.getIntField())
        }

    @Test
    fun `test list of object with builder`(): Unit =
        runBlocking {
            val o1 =
                O1.Builder(executionContext)
                    .listField(
                        listOf(
                            null,
                            listOf(
                                O2.Builder(executionContext)
                                    .intField(5)
                                    .build(),
                                null
                            )
                        )
                    )
                    .build()

            val listField = o1.getListField()!!
            assertEquals(null, listField[0])
            val innerList = listField[1]!!
            assertEquals(5, innerList[0]!!.getIntField())
            assertEquals(null, innerList[1])
        }

    @Test
    fun `test wrap scalar and object`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("stringField", "hi")
                        .put(
                            "objectField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("intField", 1)
                                .build()
                        )
                        .build()
                )
            assertEquals("hi", o1.getStringField())
            assertEquals(1, o1.getObjectField()!!.getIntField())
        }

    @Test
    fun `test wrap scalar - wrong type string`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("stringField", 1)
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getStringField()
                }
            }
            assertTrue(exception.message!!.contains("O1.stringField"))
            assertEquals("Expected a String input, but it was a 'Integer'", exception.cause!!.message)
        }

    @Test
    fun `test wrap scalar - wrong type int`(): Unit =
        runBlocking {
            val o2 =
                O2(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                        .put("intField", "hi")
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o2.getIntField()
                }
            }
            assertTrue(exception.message!!.contains("O2.intField"))
            assertEquals("Expected a value that can be converted to type 'Int' but it was a 'String'", exception.cause!!.message)
        }

    @Test
    fun `test wrap enum`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("enumField", "A")
                        .build()
                )
            assertEquals(E1.A, o1.getEnumField())
        }

    @Test
    fun `test wrap enum - different enum type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("enumField", BadE1.A)
                        .build()
                )
            assertEquals(E1.A, o1.getEnumField())
        }

    @Test
    fun `test wrap enum - wrong value`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("enumField", 1)
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getEnumField()
                }
            }
            assertTrue(exception.message!!.contains("O1.enumField"))
            assertTrue(exception.cause!!.message!!.contains("No enum constant"))
        }

    @Test
    fun `test wrap enum - wrong type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "enumField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("intField", 1)
                                .build()
                        )
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getEnumField()
                }
            }
            assertTrue(exception.message!!.contains("O1.enumField"))
            assertTrue(exception.cause!!.message!!.contains("No enum constant"))
        }

    @Test
    fun `test wrap interface`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "interfaceField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                .put("commonField", "from I1")
                                .build()
                        )
                        .build()
                )
            assertEquals("from I1", (o1.getInterfaceField() as I1).getCommonField())
        }

    @Test
    fun `test wrap interface - wrong object type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "interfaceField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("commonField", "from I1")
                                .build()
                        )
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getInterfaceField()
                }
            }
            assertTrue(exception.message!!.contains("O1.interfaceField"))
            assertEquals("Expected value to be a subtype of I0, got O2", exception.cause!!.message)
        }

    @Test
    fun `test wrap interface - wrong type not EngineObjectData`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("interfaceField", "hi")
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getInterfaceField()
                }
            }
            assertTrue(exception.message!!.contains("O1.interfaceField"))
            assertEquals("Expected value to be an instance of EngineObjectData, got hi", exception.cause!!.message)
        }

    @Test
    fun `test wrap object - wrong type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "objectField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                .put("commonField", "from I1")
                                .build()
                        )
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getObjectField()
                }
            }
            assertTrue(exception.message!!.contains("O1.objectField"))
            assertEquals("Expected value with GraphQL type O2, got I1", exception.cause!!.message)
        }

    @Test
    fun `test wrap list - wrong base type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listField",
                            listOf(
                                listOf(
                                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                        .put("commonField", "from I1")
                                        .build()
                                )
                            )
                        )
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getListField()
                }
            }
            assertTrue(exception.message!!.contains("O1.listField"))
            assertEquals("Expected value with GraphQL type O2, got I1", exception.cause!!.message)
        }

    @Test
    fun `test wrap list - wrong type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listField",
                            listOf(
                                EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("I1"))
                                    .put("commonField", "from I1")
                                    .build()
                            )
                        )
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getListField()
                }
            }
            assertTrue(exception.message!!.contains("O1.listField"))
            assertTrue(exception.cause!!.message!!.contains("Got non-list value"))
        }

    @Test
    fun `test wrap list - wrong with null`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listFieldNonNullBaseType",
                            listOf(
                                null
                            )
                        )
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getListFieldNonNullBaseType()
                }
            }
            assertTrue(exception.message!!.contains("O1.listFieldNonNullBaseType"))
            assertEquals("Got null value for non-null type [O2!]!", exception.cause!!.message)
        }

    @Test
    fun `test wrap list - wrong with null base type`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "listFieldNonNullBaseType",
                            listOf(
                                listOf(null)
                            )
                        )
                        .build()
                )
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    o1.getListFieldNonNullBaseType()
                }
            }
            assertTrue(exception.message!!.contains("O1.listFieldNonNullBaseType"))
            assertEquals("Got null value for non-null type O2!", exception.cause!!.message)
        }

    @Test
    fun `test wrapping - framework error null value`(): Unit =
        runBlocking {
            val o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put(
                            "objectField",
                            EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                                .put("intField", null)
                                .build()
                        )
                        .build()
                )
            val objectField = o1.getObjectField()!!
            val exception = assertThrows<ViaductFrameworkException> {
                runBlocking {
                    objectField.getIntField()
                }
            }
            assertEquals("Got null value for non-null type Int!", exception.cause!!.message)
        }

    @Test
    fun `test unwrapping - framework errors`(): Unit =
        runBlocking {
            val builder = BuggyBuilder()
            val e1 = assertThrows<ViaductFrameworkException> { builder.intField(null) }
            assertEquals("Got null builder value for non-null type Int!", e1.cause!!.message)
            val e2 = assertThrows<ViaductFrameworkException> { builder.objectField(4) }
            assertEquals("Expected ObjectBase or EngineObjectData for builder value, got 4", e2.cause!!.message)
        }

    @Test
    fun `test wrap - backing data`(): Unit =
        runBlocking {
            var o2 =
                O2(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                        .put("backingDataField", "abc")
                        .build()
                )
            assertEquals("abc", o2.get("backingDataField", String::class))

            o2 =
                O2(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O2"))
                        .put("backingDataField", "abc")
                        .build()
                )
            val exception = assertThrows<IllegalArgumentException> {
                o2.get(
                    "backingDataField",
                    Int::class
                )
            }
            assertTrue(exception.message!!.contains("Expected backing data value to be of type Int, got String"))
        }

    @Test
    fun `test wrap - list backing data`(): Unit =
        runBlocking {
            var o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("backingDataList", listOf(1))
                        .build()
                )
            assertEquals(listOf(1), o1.get("backingDataList", Int::class))

            o1 =
                O1(
                    internalContext,
                    EngineObjectDataBuilder.from(gqlSchema.schema.getObjectType("O1"))
                        .put("backingDataList", listOf(1))
                        .build()
                )
            val exception = assertThrows<IllegalArgumentException> { o1.get("backingDataList", String::class) }
            assertTrue(exception.message!!.contains("Expected backing data value to be of type String, got Int"))
        }

    private abstract inner class NR : NodeReference {
        override val graphQLObjectType = gqlSchema.schema.getObjectType("O1")
    }

    @Test
    fun `test noderef - fetch`(): Unit =
        runBlocking {
            val o1 = O1(
                internalContext,
                object : NR() {
                    override val id = "O1:foo"
                }
            )
            assertEquals("O1:foo", o1.getId().toString())
            assertThrows<ViaductFrameworkException> { o1.get("thisFieldDoesNotExist", String::class) }
            assertInstanceOf(
                UnsetSelectionException::class.java,
                assertThrows<ViaductTenantUsageException> { o1.getStringField() }.cause
            )
        }

    fun `test various exceptions`(): Unit =
        runBlocking {
            val o11 = O1(
                internalContext,
                object : NR() {
                    override val id get() = ExceptionsForTesting.throwViaductTenantException("foo")
                }
            )
            val e11 = runCatching { o11.getId() }.exceptionOrNull()!!
            assertInstanceOf(ViaductTenantException::class.java, e11)
            assertEquals("foo", e11.message)

            val o12 = O1(
                internalContext,
                object : NR() {
                    override val id get() = throw ExceptionsForTesting.throwViaductFrameworkException("foo")
                }
            )
            assertEquals(
                "foo",
                assertThrows<ViaductFrameworkException> { o12.getId() }.message
            )

            val o13 = O1(
                internalContext,
                object : NR() {
                    override val id get() = throw RuntimeException("foo")
                }
            )
            val e13 = runCatching { o13.getId() }.exceptionOrNull()!!
            assertEquals("EngineObjectDataFetchException", e13::class.simpleName)
            assertEquals("foo", e13.cause!!.message)
        }

    inner class BuggyBuilder : ObjectBase.Builder<O2>(internalContext, gqlSchema.schema.getObjectType("O2")) {
        fun intField(v: Any?): BuggyBuilder {
            putInternal("intField", v)
            return this
        }

        fun objectField(v: Any?): BuggyBuilder {
            putInternal("objectField", v)
            return this
        }

        override fun build() = O2(context, buildEngineObjectData())
    }
}
