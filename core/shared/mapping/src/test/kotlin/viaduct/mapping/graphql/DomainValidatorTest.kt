package viaduct.mapping.graphql

import io.kotest.common.runBlocking
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.printSeedMarch
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.asSchema
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.engine.api.mocks.MockSchema
import viaduct.mapping.test.DomainValidator
import viaduct.mapping.test.RoundtripError
import viaduct.mapping.test.ValueRoundtripError

class DomainValidatorTest : KotestPropertyBase() {
    private val cfg = Config.default + (GenInterfaceStubsIfNeeded to true)

    @Test
    fun `checkAll with schema -- passes for valid domain`(): Unit =
        runBlocking {
            Arb.graphQLSchema(cfg).checkAll { schema ->
                DomainValidator(IdentityDomain, schema, randomSource).checkAll(100)
            }
        }

    @Test
    fun `checkAll with schema -- throws ValueRoundtripError with seed for invalid domain`(): Unit =
        runBlocking {
            Arb.graphQLSchema(cfg).checkInvariants { schema, check ->
                val validator = DomainValidator(NonBijectiveTestDomain, schema, randomSource)
                val exception = runCatching { validator.checkAll(100) }.exceptionOrNull()

                if (check.isInstanceOf(
                        ValueRoundtripError::class,
                        exception,
                        "exception is not ValueRoundtripError: {0}",
                        arrayOf(exception.toString())
                    )
                ) {
                    exception as ValueRoundtripError
                    // Verify that exception contains a seed for reproducibility
                    check.isTrue(
                        exception.seed != null,
                        "Exception seed should not be null"
                    )
                }
            }
        }

    @Test
    fun `checkAll with schema -- throws RoundtripError with seed when domain throws`(): Unit =
        runBlocking {
            Arb.graphQLSchema(cfg).checkInvariants { schema, check ->
                val err = RuntimeException()
                val validator = DomainValidator(ThrowingTestDomain(err), schema, randomSource)
                val exception = runCatching { validator.checkAll(1) }.exceptionOrNull()

                if (check.isInstanceOf(
                        RoundtripError::class,
                        exception,
                        "exception is not RoundtripError: {0}",
                        arrayOf(exception.toString())
                    )
                ) {
                    exception as RoundtripError
                    check.isSameInstanceAs(
                        err,
                        exception.cause,
                        "exception cause is not thrown error: {0}",
                        arrayOf(exception.cause.toString())
                    )
                    // Verify that exception contains a seed for reproducibility
                    check.isTrue(
                        exception.seed != null,
                        "Exception seed should not be null"
                    )
                }
            }
        }

    @Test
    fun `checkAll -- fails for non-bijective domain`() {
        val schema = "type Query { x:Int }".asSchema
        assertThrows<ValueRoundtripError> {
            DomainValidator(NonBijectiveTestDomain, schema, randomSource).checkAll()
        }
    }

    @Test
    fun `checkAll -- passes for IR domain`() {
        val schema = "type Query { x:Int }".asSchema
        assertDoesNotThrow {
            DomainValidator(IR, schema, randomSource).checkAll()
        }
    }

    @Test
    fun `checkAll -- passes for simple test domain`() {
        val schema = "type Query { x:Int }".asSchema
        assertDoesNotThrow {
            DomainValidator(IdentityDomain, schema, randomSource).checkAll()
        }
    }

    @Test
    fun `checkAll -- roundTrips objects and input objects`() {
        val schema = MockSchema.mk(
            """
                input Inp { x:Int }
                extend type Query { x:Int }
            """.trimIndent()
        ).schema
        val mappedForward = mutableSetOf<String>()
        val inverted = mutableSetOf<String>()
        val domain = object : Domain<IR.Value.Object> {
            override val conv = Conv<IR.Value.Object, IR.Value.Object>(
                { it.also { obj -> mappedForward += obj.name } },
                { it.also { obj -> inverted += obj.name } }
            )
        }
        val validator = DomainValidator(domain, schema, randomSource)

        runCatching {
            validator.checkAll()
        }

        assertEquals(setOf("Inp", "Query", "PageInfo"), mappedForward.toSet())
        assertEquals(setOf("Inp", "Query", "PageInfo"), inverted.toSet())
    }

    @Test
    fun `check -- throws ValueRoundtripError for non-bijective domain`() {
        val schema = "type Query { x:Int }".asSchema
        val err = assertThrows<ValueRoundtripError> {
            DomainValidator(NonBijectiveTestDomain, schema, randomSource)
                .check(IR.Value.Object("Query", emptyMap()))
        }
        assertNull(err.seed)
    }

    @Test
    fun `check -- throws RoundtripError for throwing domain`() {
        val schema = "type Query { x:Int }".asSchema
        val cause = RuntimeException()
        val err = assertThrows<RoundtripError> {
            DomainValidator(ThrowingTestDomain(cause), schema, randomSource)
                .check(IR.Value.Object("Query", emptyMap()))
        }
        assertNull(err.seed)
        assertSame(cause, err.cause)
    }

    @Test
    fun `check -- does not throw for valid domain`() {
        val schema = "type Query { x:Int }".asSchema
        assertDoesNotThrow {
            DomainValidator(IdentityDomain, schema, randomSource)
                .check(IR.Value.Object("Query", emptyMap()))
        }
    }

    @Test
    @Disabled("Local-only: run to hunt for the flaky seed. Remove @Disabled and run with --test_timeout=3600")
    fun `seed march -- IdentityDomain`() {
        Arb.graphQLSchema(cfg).printSeedMarch()
    }

    @Test
    fun `create with custom generator`() {
        val obj = IR.Value.Object("Query", mapOf("x" to IR.Value.Number(1)))
        val domain = object : Domain<IR.Value.Object> {
            override val conv = Conv(::checkAndPass, ::checkAndPass)

            fun checkAndPass(inp: IR.Value.Object): IR.Value.Object =
                inp.also {
                    assertSame(obj, inp)
                }
        }
        val validator = DomainValidator(domain, Arb.constant(obj), randomSource)
        assertDoesNotThrow {
            validator.checkAll()
        }
    }
}

private object NonBijectiveTestDomain : Domain<IR.Value.Object> {
    override val conv = Conv<IR.Value.Object, IR.Value.Object>(
        { it },
        { it.copy(name = it.name + "_") }
    )
}

private class ThrowingTestDomain(
    val cause: Throwable
) : Domain<IR.Value.Object> {
    override val conv = Conv<IR.Value.Object, IR.Value.Object>(
        { throw cause },
        { it }
    )
}

private object IdentityDomain : Domain<IR.Value.Object> {
    override val conv = Conv.identity<IR.Value.Object>()
}
