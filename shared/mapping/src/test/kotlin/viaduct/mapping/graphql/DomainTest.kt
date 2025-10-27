@file:Suppress("ForbiddenImport")

package viaduct.mapping.graphql

import io.kotest.property.Arb
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.take
import io.kotest.property.forAll
import java.util.Locale.getDefault
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.arbitrary.common.Config
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.arbitrary.common.flatten
import viaduct.arbitrary.common.randomSource
import viaduct.arbitrary.graphql.GenInterfaceStubsIfNeeded
import viaduct.arbitrary.graphql.graphQLSchema
import viaduct.mapping.test.objectIR
import viaduct.utils.bijection.Bijection

class DomainTest : KotestPropertyBase() {
    private val cfg = Config.default + (GenInterfaceStubsIfNeeded to true)
    private val arbSchema = Arb.graphQLSchema(cfg)

    @Test
    fun `map -- identity`(): Unit =
        runBlocking {
            val mapper = IR map IR
            val objects = arbSchema.map { schema ->
                Arb.objectIR(schema, cfg)
                    .take(100, randomSource())
                    .toList()
            }.flatten()

            objects.forAll { obj ->
                mapper(obj) == obj
            }
        }

    @Test
    fun `map -- simple`() {
        // set up the object mapper
        val mapper = TestDomain1 map TestDomain2

        // define a starting value in the "from" domain, TestDomain1
        val obj1 = TestDomain1.Value.Object(
            "Foo",
            mapOf(
                "charField" to TestDomain1.Value.Char('a'),
                "int" to TestDomain1.Value.Int(1)
            )
        )

        // map the object into the "to" domain, TestDomain2
        val obj2 = mapper(obj1)
        assertEquals(
            TestDomain2.Value.Object(
                // note: object name has been reversibly transformed: "Foo" vs "foo"
                "foo",
                mapOf(
                    // note: charField, which originated from a Char type that is not
                    // represented in either IR or TestDomain2, has been mapped into an
                    // equivalent Int value
                    "charField" to TestDomain2.Value.Int(97),
                    "int" to TestDomain2.Value.Int(1)
                )
            ),
            obj2
        )

        // roundtrip the mapped object back to the original domain
        val obj3 = mapper.inverse()(obj2)

        // assert that the object has been successfully roundtripped.
        // This checks that the char field was successfully recovered from the
        // "lower resolution" From domain
        assertEquals(obj1, obj3)
    }
}

/**
 * This is a simple test domain.
 * It's important quality is that it defines a "Char" type that does not
 * have a native type in IR domain or TestDomain2.
 *
 * Values of type Char can be represented as an IRInt
 */
object TestDomain1 : Domain<TestDomain1.Value.Object> {
    sealed interface Value {
        @JvmInline value class Char(val value: kotlin.Char) : Value

        @JvmInline value class Int(val value: kotlin.Int) : Value

        @JvmInline value class Str(val value: String) : Value

        data class Object(val name: String, val fields: Map<String, Value>) : Value
    }

    override fun objectToIR(): Bijection<Value.Object, IR.Value.Object> = Bijection(::toIRObject, ::fromIRObject)

    private fun toIRObject(obj: Value.Object): IR.Value.Object {
        // example of name transformations
        val newName = obj.name.lowercase()
        val newFields = obj.fields.mapValues { (_, v) -> toIR(v) }
        return IR.Value.Object(newName, newFields)
    }

    private fun toIR(value: Value): IR.Value =
        when (value) {
            is Value.Char -> IR.Value.Number(value.value.code)
            is Value.Int -> IR.Value.Number(value.value)
            else -> IR.Value.String(value.toString())
        }

    private fun fromIRObject(ir: IR.Value.Object): Value.Object {
        // unwind name transformation
        val newName = ir.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(getDefault()) else it.toString() }
        val newFields = ir.fields.mapValues { (n, v) -> fromIR(n, v) }

        return Value.Object(newName, newFields)
    }

    private fun fromIR(
        fieldName: String,
        ir: IR.Value
    ): Value =
        when {
            // A domain mapper may be initialized with a schema that defines the universe of types in this domain
            // We would check that here by using the type information available during mapping to find the native
            // type for a given piece of data.
            //
            // Imagine that this hard-coded fieldname check is a standin for having done that check, and it
            // determined that a field that was represented as an Int in IR should actually be a Char in this
            // domain
            fieldName == "charField" && ir is IR.Value.Number -> Value.Char(ir.value.toChar())

            ir is IR.Value.Number -> Value.Int(ir.value.toInt())
            else -> Value.Str(ir.toString())
        }
}

object TestDomain2 : Domain<TestDomain2.Value.Object> {
    sealed interface Value {
        @JvmInline value class Int(val value: kotlin.Int) : Value

        data class Object(val name: String, val fields: Map<String, Value>) : Value
    }

    private val valueBijection = Bijection<Value, IR.Value>(
        forward = { v ->
            when (v) {
                is Value.Int -> IR.Value.Number(v.value)
                else -> TODO()
            }
        },
        inverse = { ir ->
            when (ir) {
                is IR.Value.Number -> Value.Int(ir.value.toInt())
                else -> TODO()
            }
        }
    )

    override fun objectToIR(): Bijection<Value.Object, IR.Value.Object> =
        Bijection<Value.Object, IR.Value.Object>(
            forward = { obj ->
                IR.Value.Object(obj.name, obj.fields.mapValues { (_, v) -> valueBijection(v) })
            },
            inverse = { ir ->
                Value.Object(ir.name, ir.fields.mapValues { (_, v) -> valueBijection.inverse()(v) })
            }
        )
}
