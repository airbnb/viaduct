@file:Suppress("ForbiddenImport")

package viaduct.tenant.runtime

import kotlin.Suppress
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.api.ViaductTenantUsageException
import viaduct.api.internal.ObjectBase
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.MockReflectionLoader
import viaduct.api.mocks.testGlobalId
import viaduct.engine.api.UnsetSelectionException
import viaduct.engine.api.mocks.mkEngineObjectData
import viaduct.service.api.spi.globalid.GlobalIDCodecDefault
import viaduct.tenant.runtime.globalid.CreateUserInput
import viaduct.tenant.runtime.globalid.GlobalIdFeatureAppTest
import viaduct.tenant.runtime.globalid.Mutation_CreateUser_Arguments
import viaduct.tenant.runtime.globalid.Query_User_Arguments
import viaduct.tenant.runtime.globalid.User

// The unit-tests in GRTConstructorExtensions test its contents pretty comprehensively.
// This file adds just a few more tests to ensure that things work as expected with
// code-generated GRTs.
class GRTConstructorExtensionsTest {
    val schema = GlobalIdFeatureAppTest.schema
    val internalContext = MockInternalContext(
        GlobalIdFeatureAppTest.schema,
        GlobalIDCodecDefault,
        MockReflectionLoader(User.Reflection)
    )
    val userType = schema.schema.getObjectType("User")
    val emptyUserEOD = mkEngineObjectData(userType, emptyMap())

    // Test [KClass.getGRTConstructor] on code-generated GRTs

    inline fun <reified T : Any> assertValidCtor() {
        T::class.getGRTConstructor().requireValidGRTConstructorFor(T::class)
    }

    @Test
    fun `getGRTConstructor - success with generated CreateUserInput InputLike type`() {
        assertValidCtor<CreateUserInput>()
    }

    @Test
    fun `getGRTConstructor - success with generated Mutation_CreateUser_Arguments InputLike type`() {
        assertValidCtor<Mutation_CreateUser_Arguments>()
    }

    @Test
    fun `getGRTConstructor - success with generated User object type`() {
        assertValidCtor<User>()
    }

    // Test [KClass.toObjectGRT]

    @Test
    fun `toObjectGRT - success with User type`() {
        val data = mapOf(
            "id" to User.Reflection.testGlobalId("user123"), // GlobalIDCodecDefault uses Base64-encoded format
            "name" to "John Doe",
        )
        val gqlType = schema.schema.getObjectType("User")
        val eod = mkEngineObjectData(gqlType, data)
        val user = eod.toObjectGRT(internalContext, User::class)

        assertInstanceOf(User::class.java, user)
        assertSame(eod, (user as ObjectBase).engineObject, "EngineObjectData should be preserved")
    }

    @Test
    fun `toInputLikeGRT - success with CreateUserInput type`() {
        val data = mapOf(
            "id" to User.Reflection.testGlobalId("user123"),
            "name" to "John Doe",
            "email" to "john@example.com"
        )
        schema.schema.getType("CreateUserInput") as graphql.schema.GraphQLInputObjectType
        val input = data.toInputLikeGRT(internalContext, CreateUserInput::class)
        assertInstanceOf(CreateUserInput::class.java, input)
    }

    @Test
    fun `toInputLikeGRT - success with Mutation_CreateUser_Arguments type`() {
        val data = mapOf(
            "input" to mapOf(
                "id" to User.Reflection.testGlobalId("user123"),
                "name" to "John Doe",
                "email" to "john@example.com"
            )
        )
        val args = data.toInputLikeGRT(internalContext, Mutation_CreateUser_Arguments::class)
        assertInstanceOf(Mutation_CreateUser_Arguments::class.java, args)
    }

    @Test
    fun `toInputLikeGRT - success with Query_User_Arguments type`() {
        val data = mapOf(
            "id" to User.Reflection.testGlobalId("user123")
        )
        val args = data.toInputLikeGRT(internalContext, Query_User_Arguments::class)
        assertInstanceOf(Query_User_Arguments::class.java, args)
    }

    @Test
    fun `toObjectGRT - success with User type and can access provided fields`() {
        val data = mapOf(
            "id" to User.Reflection.testGlobalId("user123"), // GlobalIDCodecDefault uses Base64-encoded format
            "name" to "John Doe"
            // Intentionally not providing email to test UnsetSelectionException
        )
        val gqlType = schema.schema.getObjectType("User")
        val eod = mkEngineObjectData(gqlType, data)
        val user = eod.toObjectGRT(internalContext, User::class)

        // TODO (https://app.asana.com/1/150975571430/project/1207604899751448/task/1211682054265230?focus=true)

        // The tests below add to what is done in the previous test.  However, they should really
        // be done in the tests for the GRTs themselves.  That is, the responsibility of these tests
        // is to make sure that an instance of [User] is created (and has the right EOD in it), it's
        // someone else's responsibility to make sure that [User] behaves correctly.
        //
        // However, we're keeping this test for now until we confirm that it's indeed covered elsehwere.

        val globalId = runBlocking {
            user.getId()
        }
        assertInstanceOf(globalId::class.java, globalId) // Ensure getId returns proper type
        assertEquals("User", globalId.type.name)
        assertEquals("user123", globalId.internalID)

        val name = runBlocking {
            user.getName()
        }
        assertEquals("John Doe", name)

        val exception = assertThrows<ViaductTenantUsageException> {
            runBlocking {
                user.getEmail()
            }
        }
        assertInstanceOf(UnsetSelectionException::class.java, exception.cause)
    }
}
