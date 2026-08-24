@file:Suppress("ForbiddenImport")

package viaduct.api

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.globalid.GlobalID
import viaduct.api.internal.ViaductObjectBuilder
import viaduct.api.mocks.MockInternalContext
import viaduct.api.testschema.ApiTestSchema
import viaduct.api.testschema.E1
import viaduct.api.testschema.I1
import viaduct.api.testschema.O1
import viaduct.api.testschema.O2
import viaduct.api.testschema.TestUser
import viaduct.errors.TenantUsageException

@OptIn(ExperimentalCoroutinesApi::class)
class ViaductObjectBuilderTest {
    private val context = MockInternalContext.create(ApiTestSchema.schema, "viaduct.api.testschema")

    @Test
    fun testBasicBuild(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            o1Builder.put("stringField", "hello")
            val o1 = o1Builder.build()

            assertEquals("hello", o1.getStringFieldOrThrow())
        }

    @Test
    fun testPutViaductObjectBuilderAsValue(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            val o2Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O2::class
            )
            val i1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                I1::class
            )
            i1Builder.put("commonField", "world")
            o2Builder.put("intField", 10)
            o1Builder.put("objectField", o2Builder)
            o1Builder.put("interfaceField", i1Builder)

            val o1 = o1Builder.build()

            assertNotNull(o1.getObjectFieldOrThrow())
            assertEquals(10, o1.getObjectFieldOrThrow()!!.getIntFieldOrThrow())
            assertEquals("world", o1.getInterfaceFieldOrThrow()!!.getCommonFieldOrThrow())
        }

    @Test
    fun testListType(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            val o2Builder1 = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O2::class
            )
            val o2Builder2 = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O2::class
            )
            o2Builder1.put("intField", 10)
            val o21 = o2Builder1.build()
            o2Builder2.put("intField", 20)
            val o22 = o2Builder2.build()

            o1Builder.put("listField", listOf(listOf(o21), listOf(o22)))
            val o1 = o1Builder.build()
            assertNotNull(o1.getListFieldOrThrow())
            assertEquals(2, o1.getListFieldOrThrow()?.size)
            assertEquals(1, o1.getListFieldOrThrow()?.get(0)?.size)
            assertEquals(1, o1.getListFieldOrThrow()?.get(1)?.size)
            assertEquals(10, o1.getListFieldOrThrow()?.get(0)?.get(0)?.getIntFieldOrThrow())
            assertEquals(20, o1.getListFieldOrThrow()?.get(1)?.get(0)?.getIntFieldOrThrow())
        }

    @Test
    fun testBadFieldName(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            val e = assertThrows<TenantUsageException> { o1Builder.put("tralala", 10) }
            assertEquals("Field tralala not found on type O1", e.message)
        }

    @Test
    fun testInvalidScalarType(): Unit =
        runBlocking {
            val o2Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O2::class
            )
            val e = assertThrows<IllegalArgumentException> { o2Builder.put("intField", "hello") }
            assertEquals("Expected value of type Int for field intField, got String", e.message)
        }

    @Test
    fun testInvalidScalarTypeDateTime(): Unit =
        runBlocking {
            val o2Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O2::class
            )
            val e = assertThrows<IllegalArgumentException> { o2Builder.put("dateTimeField", false) }
            assertEquals("Expected value of type Instant for field dateTimeField, got Boolean", e.message)
        }

    @Test
    fun testInvalidEnumType(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            val e = assertThrows<IllegalArgumentException> { o1Builder.put("enumField", 10) }
            assertEquals("Invalid enum value '10' for type ${E1::class.simpleName} for field enumField", e.message)
        }

    @Test
    fun testInvalidListType(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            val e = assertThrows<IllegalArgumentException> { o1Builder.put("listField", 10) }
            assertEquals("Got non-list builder value 10 for list type for field listField", e.message)
        }

    @Test
    fun testInvalidObjectType(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            val e = assertThrows<IllegalArgumentException> { o1Builder.put("objectField", 10) }
            assertEquals(
                "Expected O2 or ValueObjectBuilder<O2> for builder value for field objectField, got 10",
                e.message
            )
        }

    @Test
    fun testNonNullType(): Unit =
        runBlocking {
            val o1Builder = ViaductObjectBuilder.dynamicBuilderFor(
                context,
                O1::class
            )
            val e = assertThrows<IllegalArgumentException> { o1Builder.put("listFieldNonNullBaseType", listOf(null)) }
            assertEquals("Got null builder value for non-null type [O2!]! for field listFieldNonNullBaseType", e.message)
        }

    @Test
    fun testIDType(): Unit =
        runBlocking {
            val globalId = GlobalID(TestUser.Reflection, "42")

            ViaductObjectBuilder.dynamicBuilderFor(context, TestUser::class)
                .put("id", globalId)
                .put("id2", "42")
                .put("id3", listOf(globalId, null))
                .put("id4", listOf("42", null))
                .build()
                .let { user ->
                    assertEquals(globalId, user.getIdOrThrow())
                    assertEquals("42", user.getId2OrThrow())
                    assertEquals(listOf(globalId, null), user.getId3OrThrow())
                    assertEquals(listOf("42", null), user.getId4OrThrow())
                }

            // null values
            ViaductObjectBuilder.dynamicBuilderFor(context, TestUser::class)
                .put("id3", null)
                .put("id4", null)
                .build()
                .let { user ->
                    assertEquals(null, user.getId3OrThrow())
                    assertEquals(null, user.getId4OrThrow())
                }
        }
}
