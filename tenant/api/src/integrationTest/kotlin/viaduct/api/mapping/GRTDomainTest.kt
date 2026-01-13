package viaduct.api.mapping

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetTime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.api.globalid.GlobalIDImpl
import viaduct.api.internal.KeyMapping
import viaduct.api.internal.ObjectBase
import viaduct.api.internal.putWithAlias
import viaduct.api.mocks.MockInternalContext
import viaduct.api.mocks.executionContext
import viaduct.api.schemautils.SchemaUtils
import viaduct.api.testschema.E1
import viaduct.api.testschema.Input1
import viaduct.api.testschema.Input2
import viaduct.api.testschema.O1
import viaduct.api.testschema.Scalars
import viaduct.api.testschema.TestUser
import viaduct.arbitrary.common.KotestPropertyBase
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.engineObjectsAreEquivalent
import viaduct.mapping.graphql.IR
import viaduct.mapping.test.DomainValidator

class GRTDomainTest : KotestPropertyBase() {
    private val schema = SchemaUtils.getSchema()
    private val internalContext = MockInternalContext.mk(schema, "viaduct.api.testschema")
    private val executionContext = internalContext.executionContext

    private val domain = GRTDomain(executionContext)
    private val validator = DomainValidator(domain, schema.schema, equalsFn = ::grtsEqual)

    @Test
    fun `GRTDomain roundtrips arbitrary IR`() {
        validator.checkAll()
    }

    @Test
    fun `GRTDomain -- roundtrips output objects`() {
        validator.check(
            Scalars.Builder(executionContext)
                .boolean(true)
                .byte(Byte.MAX_VALUE)
                .short(Short.MAX_VALUE)
                .int(Int.MAX_VALUE)
                .float(Double.MAX_VALUE)
                .json("{}")
                .string("str")
                .id("ID")
                .date(LocalDate.MAX)
                .dateTime(Instant.MAX)
                .time(OffsetTime.MAX)
                .build()
        )
    }

    @Test
    fun `GRTDomain -- roundtrips input objects`() {
        validator.check(
            Input1.Builder(executionContext)
                .stringField("str")
                .intField(Int.MAX_VALUE)
                .nonNullStringField("str2")
                .listField(
                    listOf(E1.A, E1.B)
                )
                .nestedListField(
                    listOf(listOf(E1.A), listOf(E1.B, E1.B))
                )
                .inputField(
                    Input2.Builder(executionContext)
                        .stringField("str")
                        .id1("id")
                        .id2(GlobalIDImpl(TestUser.Reflection, "1"))
                        .build()
                )
                .build()
        )
    }

    @Test
    fun `GRTDomain_forSelectionSet -- converts simple aliases`() {
        val domain = GRTDomain.forSelectionSet(
            executionContext,
            mkSelectionSet(
                schema,
                O1.Reflection,
                "x:stringField"
            ),
        )

        assertEquals(
            IR.Value.Object(
                O1.Reflection.name,
                "x" to IR.Value.String("VALUE")
            ),
            domain.conv(
                O1.Builder(executionContext)
                    .putWithAlias("stringField", "x", "VALUE")
                    .build()
            )
        )
    }

    @Test
    fun `GRTDomain_forSelectionSet -- converts simple aliases with KeyMapping`() {
        val domain = GRTDomain.forSelectionSet(
            executionContext,
            mkSelectionSet(
                schema,
                O1.Reflection,
                "x:stringField",
            ),
            KeyMapping.FieldNameToSelection
        )

        assertEquals(
            IR.Value.Object(
                O1.Reflection.name,
                "x" to IR.Value.String("VALUE")
            ),
            domain.conv(
                O1.Builder(executionContext)
                    .stringField("VALUE")
                    .build()
            )
        )
    }

    @Test
    fun `GRTDOmain_forType -- converts simple values`() {
        val domain = GRTDomain.forType<O1>(executionContext)
        assertEquals(
            IR.Value.Object(
                O1.Reflection.name,
                "stringField" to IR.Value.String("VALUE")
            ),
            domain.conv(
                O1.Builder(executionContext)
                    .stringField("VALUE")
                    .build()
            )
        )
    }
}

private fun grtsEqual(
    expected: Any?,
    actual: Any?
): Boolean =
    when {
        // object grts only support referential equality
        expected is ObjectBase && actual is ObjectBase ->
            expected.javaClass == actual.javaClass &&
                engineObjectsAreEquivalent(
                    expected.engineObject as EngineObjectData.Sync,
                    actual.engineObject as EngineObjectData.Sync
                )
        else -> expected == actual
    }
