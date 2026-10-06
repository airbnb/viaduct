package viaduct.engine.runtime2.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.invariants.conformsToResultSchemaType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.graphql.schema.ViaductSchema

class ScalarEngineResultTest {
    private val schema = TestWorld.fromSDL(
        """
        type Query {
          json: JSON
          backing: BackingData
          int: Int
          byte: Byte
          jsons: [JSON]
        }
        """.trimIndent(),
    ).schema

    @Test
    fun `scalar wrappers distinguish JSON and backing payloads from GraphQL containers`() {
        val payload = listOf(mapOf("value" to 1))
        val jsonField = schema.requireObjectField("Query", "json")
        val backingField = schema.requireObjectField("Query", "backing")
        val json = payload.toEngineResult(jsonField.type.baseTypeDef as ViaductSchema.Scalar)
        val backing = payload.toEngineResult(backingField.type.baseTypeDef as ViaductSchema.Scalar)

        assertInstanceOf(JSONEngineResult::class.java, json)
        assertInstanceOf(BackingDataEngineResult::class.java, backing)
        assertTrue(json.conformsToResultSchemaType(jsonField.outputType))
        assertTrue(backing.conformsToResultSchemaType(backingField.outputType))
        assertFalse(json.conformsToResultSchemaType(backingField.outputType))
        assertFalse(backing.conformsToResultSchemaType(jsonField.outputType))
        assertFalse(payload.conformsToResultSchemaType(jsonField.outputType))
        assertFalse(payload.conformsToResultSchemaType(backingField.outputType))
        assertFalse(json.sameCompletedResultAs(backing))

        val listType = schema.requireObjectField("Query", "jsons").outputType
        val list = ListEngineResult.of(checkNotNull(listType.unwrapList()), listOf(json, null))
        assertTrue(list.conformsToResultSchemaType(listType))
        assertFalse(json.conformsToResultSchemaType(listType))
        assertThrows<IllegalArgumentException> {
            ListEngineResult.of(checkNotNull(listType.unwrapList()), listOf(payload))
        }
    }

    @Test
    fun `materialized aliased scalar selections contain raw Engine API values`() {
        val jsonField = schema.requireObjectField("Query", "json")
        val backingField = schema.requireObjectField("Query", "backing")
        val payload = listOf(mapOf("value" to 1))
        val opaqueObject = engineObjectDataOf(schema.requireQueryTypeDef())
        val json = JSONEngineResult.of(payload)
        val backing = BackingDataEngineResult.of(opaqueObject)
        val objectData = materializedEngineObjectDataOf(
            schema.requireQueryTypeDef(),
            listOf(
                EngineObjectDataEntry.of("jsonAlias", jsonField, json.toEngineOutputData(jsonField.type.baseTypeDef as ViaductSchema.Scalar)),
                EngineObjectDataEntry.of("backingAlias", backingField, backing.toEngineOutputData(backingField.type.baseTypeDef as ViaductSchema.Scalar)),
            ),
        )

        assertEquals(payload, assertInstanceOf(List::class.java, objectData.get("jsonAlias")))
        assertSame(opaqueObject, assertInstanceOf(EngineObjectData.Sync::class.java, objectData.get("backingAlias")))
        assertThrows<ClassCastException> {
            json.toEngineOutputData(backingField.type.baseTypeDef as ViaductSchema.Scalar)
        }
        assertThrows<ClassCastException> {
            backing.toEngineOutputData(jsonField.type.baseTypeDef as ViaductSchema.Scalar)
        }
    }

    @Test
    fun `JSON result construction snapshots containers and preserves opaque leaves`() {
        val opaque = Any()
        val elements = mutableListOf<Any>(1, opaque)
        val source = mutableMapOf<String, Any>("values" to elements)
        val result = JSONEngineResult.of(source)
        elements += 2
        source["other"] = true

        val snapshot = assertInstanceOf(Map::class.java, result.value)
        assertEquals(setOf("values"), snapshot.keys)
        val values = assertInstanceOf(List::class.java, snapshot["values"])
        assertEquals(listOf(1, opaque), values)
        assertSame(opaque, values[1])
        assertTrue(result.sameCompletedResultAs(JSONEngineResult.of(mapOf("values" to listOf(1, opaque)))))
    }

    @Test
    fun `BackingData result equality retains opaque payload identity`() {
        val payload = mutableMapOf("value" to 1)
        val samePayload = BackingDataEngineResult.of(payload)
        val again = BackingDataEngineResult.of(payload)
        val equalButDistinctPayload = BackingDataEngineResult.of(mutableMapOf("value" to 1))

        assertEquals(samePayload, again)
        assertEquals(samePayload.hashCode(), again.hashCode())
        assertNotEquals(samePayload, equalButDistinctPayload)
        assertTrue(samePayload.sameCompletedResultAs(again))
        assertFalse(samePayload.sameCompletedResultAs(equalButDistinctPayload))
        assertSame(payload, samePayload.value)
        val originalHash = samePayload.hashCode()
        payload["value"] = 2
        assertEquals(originalHash, samePayload.hashCode())
    }

    @Test
    fun `primitive JSON leaves remain distinct from native scalar results`() {
        val jsonType = schema.requireObjectField("Query", "json").outputType
        val intType = schema.requireObjectField("Query", "int").outputType
        val byteType = schema.requireObjectField("Query", "byte").outputType
        val json = JSONEngineResult.of(1)

        assertTrue(json.conformsToResultSchemaType(jsonType))
        assertFalse(json.conformsToResultSchemaType(intType))
        assertFalse(1.conformsToResultSchemaType(jsonType))
        assertFalse(1.toByte().conformsToResultSchemaType(jsonType))
        assertTrue(1.toByte().conformsToResultSchemaType(byteType))
        assertFalse(json.sameCompletedResultAs(1))
        assertThrows<IllegalArgumentException> { json.union(1) }
    }
}
