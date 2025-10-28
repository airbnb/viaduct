package viaduct.mapping.graphql

import io.kotest.common.runBlocking
import io.kotest.property.Arb
import io.kotest.property.arbitrary.constant
import io.kotest.property.forAll
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.checkInvariants
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.asSchema
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.mapping.test.DomainValidator
import viaduct.mapping.test.RoundtripError
import viaduct.mapping.test.ValueRoundtripError

class DomainValidatorTest : KotestPropertyBase() {
    private val cfg = Config.default + (GenInterfaceStubsIfNeeded to true)

    @Test
    fun `checkAll with schema -- passes for valid domain`(): Unit =
        runBlocking {
            Arb.graphQLSchema(cfg).forAll(100) { schema ->
                val validator = DomainValidator(IdentityDomain, schema)
                val result = runCatching { validator.checkAll(100) }
                result.isSuccess
            }
        }

    @Test
    fun `checkAll with schema -- throws ValueRoundtripError with seed for invalid domain`(): Unit =
        runBlocking {
            Arb.graphQLSchema(cfg).checkInvariants(100) { schema, check ->
                val validator = DomainValidator(NonBijectiveTestDomain, schema)
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
            Arb.graphQLSchema(cfg).checkInvariants(100) { schema, check ->
                val err = RuntimeException()
                val validator = DomainValidator(ThrowingTestDomain(err), schema)
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
            DomainValidator(NonBijectiveTestDomain, schema).checkAll()
        }
    }

    @Test
    fun `checkAll -- passes for IR domain`() {
        val schema = "type Query { x:Int }".asSchema
        assertDoesNotThrow {
            DomainValidator(IR, schema).checkAll()
        }
    }

    @Test
    fun `checkAll -- passes for simple test domain`() {
        val schema = "type Query { x:Int }".asSchema
        assertDoesNotThrow {
            DomainValidator(IdentityDomain, schema).checkAll()
        }
    }

    @Test
    fun `checkAll -- roundTrips objects and input objects`() {
        val schema = mkSchema(
            """
                input Inp { x:Int }
                type Query { x:Int }
            """.trimIndent()
        )
        val mappedForward = mutableSetOf<String>()
        val inverted = mutableSetOf<String>()
        val domain = object : Domain<IR.Value.Object> {
            override fun objectToIR(): Conv<IR.Value.Object, IR.Value.Object> =
                Conv(
                    { it.also { mappedForward += it.name } },
                    { it.also { inverted += it.name } }
                )
        }
        val validator = DomainValidator(domain, schema)

        runCatching {
            validator.checkAll()
        }

        assertEquals(setOf("Inp", "Query"), mappedForward.toSet())
        assertEquals(setOf("Inp", "Query"), inverted.toSet())
    }

    @Test
    fun `check -- throws ValueRoundtripError for non-bijective domain`() {
        val schema = "type Query { x:Int }".asSchema
        val err = assertThrows<ValueRoundtripError> {
            DomainValidator(NonBijectiveTestDomain, schema).check(IR.Value.Object("Query", emptyMap()))
        }
        assertNull(err.seed)
    }

    @Test
    fun `check -- throws RoundtripError for throwing domain`() {
        val schema = "type Query { x:Int }".asSchema
        val cause = RuntimeException()
        val err = assertThrows<RoundtripError> {
            DomainValidator(ThrowingTestDomain(cause), schema)
                .check(IR.Value.Object("Query", emptyMap()))
        }
        assertNull(err.seed)
        assertSame(cause, err.cause)
    }

    @Test
    fun `check -- does not throw for valid domain`() {
        val schema = "type Query { x:Int }".asSchema
        assertDoesNotThrow {
            DomainValidator(IdentityDomain, schema).check(IR.Value.Object("Query", emptyMap()))
        }
    }

    @Test
    fun `create with custom generator`() {
        val obj = IR.Value.Object("Query", mapOf("x" to IR.Value.Number(1)))
        val domain = object : Domain<IR.Value.Object> {
            override fun objectToIR(): Conv<IR.Value.Object, IR.Value.Object> = Conv(::checkAndPass, ::checkAndPass)

            fun checkAndPass(inp: IR.Value.Object): IR.Value.Object =
                inp.also {
                    assertSame(obj, inp)
                }
        }
        val validator = DomainValidator(domain, Arb.constant(obj))
        assertDoesNotThrow {
            validator.checkAll()
        }
    }
}

private object NonBijectiveTestDomain : Domain<IR.Value.Object> {
    override fun objectToIR(): Conv<IR.Value.Object, IR.Value.Object> =
        Conv(
            { it },
            { it.copy(name = it.name + "_") }
        )
}

private class ThrowingTestDomain(val cause: Throwable) : Domain<IR.Value.Object> {
    override fun objectToIR(): Conv<IR.Value.Object, IR.Value.Object> = Conv({ throw cause }, { it })
}

private object IdentityDomain : Domain<IR.Value.Object> {
    override fun objectToIR(): Conv<IR.Value.Object, IR.Value.Object> = Conv.identity()
}
