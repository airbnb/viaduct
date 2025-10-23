@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime

import graphql.schema.GraphQLInputObjectType
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.full.primaryConstructor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.internal.InternalContext
import viaduct.api.mocks.MockInternalContext
import viaduct.api.types.Arguments
import viaduct.api.types.CompositeOutput
import viaduct.api.types.Input
import viaduct.api.types.InputLike
import viaduct.api.types.Object
import viaduct.engine.api.EngineObject
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.engine.api.mocks.mkSchema

@Suppress("UNUSED_PARAMETER")
class GRTConstructorExtensionsTest {
    // Test normal (valid) cases

    inline fun <reified T : Any> assertValid() {
        assertNotNull(T::class.getGRTConstructor())
    }

    class ValidArguments(
        ctx: InternalContext,
        args: Map<String, Any?>,
        inputType: GraphQLInputObjectType,
    ) : Arguments

    @Test
    fun `getGRTConstructor with valid Arguments constructor succeeds`() {
        assertValid<ValidArguments>()
    }

    @Test
    fun `getGRTConstructor with FakeArguments succeeds`() {
        assertValid<FakeArguments>()
    }

    class ValidInput(
        ctx: InternalContext,
        args: Map<String, Any?>,
        inputType: GraphQLInputObjectType,
    ) : Input

    @Test
    fun `getGRTConstructor with valid Input constructor succeeds`() {
        assertValid<ValidInput>()
    }

    class ValidObject(
        ctx: InternalContext,
        eod: EngineObject
    ) : Object

    @Test
    fun `getGRTConstructor with valid Object constructor succeeds`() {
        assertValid<ValidObject>()
    }

    @Test
    fun `getGRTConstructor with FakeObject succeeds`() {
        assertValid<FakeObject>()
    }

    @Test
    fun `getGRTConstructor with FakeQuery succeeds`() {
        assertValid<FakeQuery>()
    }

    // Test invalid cases

    inline fun <reified T : Any> assertFailsWith(msgFragment: String) {
        val exception = assertThrows<IllegalArgumentException> {
            T::class.getGRTConstructor()
        }
        assertTrue(exception.message?.contains(msgFragment) ?: false)
    }

    @Test
    fun `getGRTConstructor with NoArguments throws IllegalArgumentException`() {
        assertFailsWith<Arguments.NoArguments>("NoArguments")
    }

    class InvalidArgumentsNoConstructor private constructor() : Arguments

    @Test
    fun `getGRTConstructor on Arguments with no primary constructor throws IllegalArgumentException`() {
        assertFailsWith<InvalidArgumentsNoConstructor>("Primary constructor")
    }

    class InvalidArgumentsWrongParams(
        ctx: InternalContext,
        args: Map<String, Any?>,
        inputType: String,
    ) : Arguments

    @Test
    fun `getGRTConstructor on Arguments with wrong parameter types throws IllegalArgumentException`() {
        assertFailsWith<InvalidArgumentsWrongParams>("Primary constructor")
    }

    class InvalidArgumentsTwoParams(
        ctx: InternalContext,
        args: Map<String, Any?>
    ) : Arguments

    @Test
    fun `getGRTConstructor on Arguments with two parameters throws IllegalArgumentException`() {
        assertFailsWith<InvalidArgumentsTwoParams>("Primary constructor")
    }

    class InvalidArgumentsFourParams(
        ctx: InternalContext,
        args: Map<String, Any?>,
        inputType: graphql.schema.GraphQLInputObjectType,
        extra: String
    ) : Arguments

    @Test
    fun `getGRTConstructor on Arguments with four parameters throws IllegalArgumentException`() {
        assertFailsWith<InvalidArgumentsFourParams>("Primary constructor")
    }

    class InvalidObjectNoConstructor private constructor() : Object

    @Test
    fun `getGRTConstructor on Object with no primary constructor throws IllegalArgumentException`() {
        assertFailsWith<InvalidObjectNoConstructor>("Primary constructor")
    }

    class InvalidObjectWrongParams(val value: String) : Object

    @Test
    fun `getGRTConstructor on Object with wrong parameter types throws IllegalArgumentException`() {
        assertFailsWith<InvalidObjectWrongParams>("Primary constructor")
    }

    class InvalidObjectOneParam(ctx: InternalContext) : Object

    @Test
    fun `getGRTConstructor on Object with one parameter throws IllegalArgumentException`() {
        assertFailsWith<InvalidObjectOneParam>("Primary constructor")
    }

    class InvalidObjectThreeParams(
        ctx: InternalContext,
        eod: EngineObjectData,
        extra: String
    ) : Object

    @Test
    fun `getGRTConstructor on Object with three parameters throws IllegalArgumentException`() {
        assertFailsWith<InvalidObjectThreeParams>("Primary constructor")
    }

    class InvalidType(
        ctx: InternalContext,
        eod: EngineObject
    ) : CompositeOutput

    @Test
    fun `getGRTConstructor with wrong GRT tag throws IllegalArgumentException`() {
        assertFailsWith<InvalidType>("is not in")
    }

    @Test
    fun `Test a corner case of requireValidGRTConstructorFor`() {
        val exception = assertThrows<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            val constructor = ValidObject::class.primaryConstructor as KFunction<ValidInput>
            constructor.requireValidGRTConstructorFor(ValidInput::class)
        }
        assertTrue(exception.message!!.contains("Return type"))
    }

    // Test toXyzGRT requires a schema
    val schema = mkSchema(
        """
        input InputType {
            i: Int
        }

        type Type {
            i: Int
            s(i: Int): Boolean
        }
        """.trimIndent()
    )
    val ictx = MockInternalContext(schema)
    val typeType = schema.schema.getObjectType("Type")
    val fakeEOD = mkEngineObjectData(typeType, mapOf("i" to 42, "s" to true))

    // Test toXyzGRT
    // Can test normal case for FakeObject and FakeQuery
    // Can test all error paths
    //   HOWEVER: we don't here repeat ones tested above, so if code paths change beware!
    // No tests for normal case for real GRTs -- those are in integration tests

    @Test
    fun `toObjectGRT - succeeds for FakeObject`() {
        val o = fakeEOD.toObjectGRT(ictx, FakeObject::class)
        assertInstanceOf(FakeObject::class.java, o)
        runBlocking {
            assertEquals(42, o.get<Int>("i"))
        }
    }

    @Test
    fun `toObjectGRT - succeeds for FakeQuery`() {
        val o = fakeEOD.toObjectGRT(ictx, FakeQuery::class)
        assertInstanceOf(FakeQuery::class.java, o)
        runBlocking {
            assertEquals(true, o.get<Boolean>("s"))
        }
    }

    @Test
    fun `toObjectGRT - throws IAE when kcls name does not match object type name`() {
        val exception = assertThrows<IllegalArgumentException> {
            fakeEOD.toObjectGRT(ictx, ValidObject::class)
        }
        assertTrue(exception.message!!.contains("does not match"))
    }

    // Type named "Type" but it's an Input, not an Object - for testing type erasure
    class Type(
        ctx: InternalContext,
        args: Map<String, Any?>,
        inputType: GraphQLInputObjectType,
    ) : Input

    @Test
    fun `toObjectGRT - throws IAE when kcls is Input type with matching name (type erasure test)`() {
        val exception = assertThrows<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            fakeEOD.toObjectGRT(ictx, Type::class as KClass<Object>)
        }
        assertTrue(exception.message!!.contains("is not in"))
    }

    // Test toInputLikeGRT
    val fakeInputData = mapOf("i" to 99)

    @Test
    fun `toInputLikeGRT - succeeds for FakeArguments`() {
        val args = fakeInputData.toInputLikeGRT(ictx, FakeArguments::class)
        assertInstanceOf(FakeArguments::class.java, args)
        assertEquals(99, args.get<Int>("i"))
    }

    @Test
    fun `toInputLikeGRT - returns NoArguments singleton for NoArguments`() {
        val args = emptyMap<String, Any?>().toInputLikeGRT(ictx, Arguments.NoArguments::class)
        assertSame(Arguments.NoArguments, args)
    }

    @Test
    fun `toInputLikeGRT - throws IAE when Input kcls name does not match schema`() {
        val exception = assertThrows<IllegalArgumentException> {
            fakeInputData.toInputLikeGRT(ictx, ValidInput::class)
        }
        assertTrue(exception.message!!.contains("does not exist in schema"))
    }

    @Test
    fun `toInputLikeGRT - throws IAE when Arguments kcls name does not match schema`() {
        val exception = assertThrows<IllegalArgumentException> {
            fakeInputData.toInputLikeGRT(ictx, ValidArguments::class)
        }
        assertTrue(exception.message!!.contains("Invalid Arguments class name"))
    }

    // InputType named "InputType" but it's an Object, not Input - for testing type erasure
    class InputType(
        ctx: InternalContext,
        eod: EngineObject
    ) : Object

    @Test
    fun `toInputLikeGRT - throws IAE when kcls is Object type with matching name (type erasure test)`() {
        val exception = assertThrows<IllegalArgumentException> {
            @Suppress("UNCHECKED_CAST")
            fakeInputData.toInputLikeGRT(ictx, InputType::class as KClass<InputLike>)
        }
        assertTrue(exception.message!!.contains("is not in"))
    }
}
