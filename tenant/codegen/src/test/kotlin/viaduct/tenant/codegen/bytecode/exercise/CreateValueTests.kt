package viaduct.tenant.codegen.bytecode.exercise

import kotlinx.coroutines.test.runBlockingTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.api.internal.InternalContext
import viaduct.api.internal.ObjectBase
import viaduct.codegen.utils.JavaName
import viaduct.engine.api.EngineObjectData
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.schema.ViaductSchema as ViaductGraphQLSchema
import viaduct.graphql.schema.test.mkGraphQLSchema
import viaduct.graphql.schema.test.mkSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields

class ObjectA(
    context: InternalContext,
    engineObjectData: EngineObjectData
) : ObjectBase(context, engineObjectData) {
    final suspend fun a(): Int {
        return fetch("a", Int::class, "a")
    }
}

class ObjectB(
    context: InternalContext,
    engineObjectData: EngineObjectData
) : ObjectBase(context, engineObjectData) {
    final suspend fun b(): ObjectA {
        return fetch("b", ObjectA::class, "b")
    }
}

class ObjectC(
    context: InternalContext,
    engineObjectData: EngineObjectData
) : ObjectBase(context, engineObjectData) {
    final suspend fun c(): List<List<ObjectA?>?> {
        return fetch("c", ObjectA::class, "c")
    }
}

// Also tests createGenericValue
class CreateValueTests {
    companion object {
        fun mkDef(schema: String): ViaductGraphQLSchema.Input = mkSchema("enum E { ONE } input Nest { e: E! } $schema").types["Subject"]!! as ViaductGraphQLSchema.Input

        fun assertMultipleValues(schema: String) {
            val def = mkDef(schema)
            assertFalse(def.fields.onlyOneValue())
            val vals = def.fields.map { it.createGenericValue(false, 1, emptyList(), ViaductBaseTypeMapper(schemaForV2)) } zip def.fields.map {
                it.createGenericValue(
                    true,
                    2,
                    emptyList(),
                    ViaductBaseTypeMapper(schemaForV2)
                )
            }
            assertTrue(vals.any { it.first != it.second }) { "($vals)" }
        }

        fun assertSingleValue(schema: String) {
            val def = mkDef(schema)
            assertTrue(def.fields.onlyOneValue())
            val vals = def.fields.map { it.createGenericValue(false, 1, emptyList(), ViaductBaseTypeMapper(schemaForV2)) } zip def.fields.map {
                it.createGenericValue(
                    true,
                    2,
                    emptyList(),
                    ViaductBaseTypeMapper(schemaForV2)
                )
            }
            assertTrue(vals.all { it.first == it.second }) { "($vals)" }
        }

        private val sdlForV2 = """
            type ObjectA { a: Int! }
            type ObjectB { b: ObjectA! }
            type ObjectC { c: [[ObjectA]]! }
        """.trimIndent()
        private val schemaForV2 = mkSchema(sdlForV2)
        private val graphqlSchemaForV2 = ViaductSchema(mkGraphQLSchema(sdlForV2))
        private val defForObjectB = schemaForV2.types["ObjectB"]!! as ViaductGraphQLSchema.Object
        private val defForObjectC = schemaForV2.types["ObjectC"]!! as ViaductGraphQLSchema.Object
        private val classResolverForV2 = ClassResolver.fromSystemClassLoader(
            JavaName("viaduct.tenant.codegen.bytecode.exercise")
        )
    }

    @Test
    fun hasMultipleValuesTests() {
        assertMultipleValues("input Subject { i: Int }")
        assertMultipleValues("input Subject { i: [Int]! }")
        assertMultipleValues("input Subject { i: Int s: String }")
        assertMultipleValues("input Subject { e: E }")
        assertMultipleValues("input Subject { i: Int e: E! }")
        assertMultipleValues("input IntNest { i: Int } input Subject { n: IntNest }")
        assertMultipleValues("input Subject { l: [E!] }")
        assertMultipleValues("input Subject { l: [E]! }")
        assertMultipleValues("input Subject { l: [Nest!] }")
        assertMultipleValues("input Subject { l: [E!]! }")
        assertMultipleValues("input Subject { l: [[E!]!]! }")
        assertMultipleValues("input Subject { l: [Nest!]! }")
        assertMultipleValues("input Subject { l: [[E]!]! }")
        assertMultipleValues("input Subject { l: [[E!]]! }")
        assertMultipleValues("input Subject { l: [[E!]!] }")
        assertMultipleValues("input Subject { l: [[E]]! }")
        assertMultipleValues("input Subject { l: [[E]!] }")
        assertMultipleValues("input Subject { l: [[E!]] }")
    }

    @Test
    fun hasSingleValueTests() {
        assertSingleValue("input Subject { e: E! }")
        assertSingleValue("input Subject { e: E!, e1: E! }")
        assertSingleValue("input Subject { n: Nest! }")

        assertSingleValue(
            """
            input Base { e: E! }
            input H1 { f: Base! }
            input H2 { f: H1! }
            input H3 { f: H2! }
            input H4 { f: H3! }
            input Subject { f: H4! }
        """
        )
    }

    @Test
    fun hasInfiniteLoop() {
        assertThrows(IllegalArgumentException::class.java) {
            mkDef("input Subject { r: Subject! f: Int }").fields.onlyOneValue()
        }
        val e = assertThrows(IllegalArgumentException::class.java) {
            mkDef("input R1 { r1: R2! } input R2 { r2: R3! } input R3 { r3: R1! } input Subject { r: R1! }")
                .fields.first().createGenericValue(false, 1, emptyList(), ViaductBaseTypeMapper(schemaForV2))
        }
        assertTrue(Regex("r:.*\n.*r1:.*\n.*r2:.*\n.*r3:.*\n.*r1:").containsMatchIn(e.message!!))
    }

    @Test
    fun regressionTests() {
        val result = mkDef("input Subject { f: [[E!]]! }").fields.first().createGenericValue(true, 2, emptyList(), ViaductBaseTypeMapper(schemaForV2))!! as List<*>
        assertEquals(1, result.size)
        assertNull(result[0])
    }

    @Test
    fun testCreateValueV2() =
        runBlockingTest {
            val fieldB = defForObjectB.codegenIncludedFields.first()
            val valueV2 = fieldB.createValueV2(classResolverForV2, graphqlSchemaForV2)
            assertTrue(valueV2 is ObjectA)
            assertNotNull(((valueV2 as ObjectBase).engineObject as EngineObjectData).fetch("a"))

            val fieldC = defForObjectC.codegenIncludedFields.first()
            val listValue = fieldC.createValueV2(classResolverForV2, graphqlSchemaForV2)
            assertEquals(1, (listValue as List<*>).size)
            assertEquals(1, (listValue[0] as List<*>).size)
            assertNotNull((((listValue[0] as List<*>)[0] as ObjectBase).engineObject as EngineObjectData).fetch("a"))
        }

    @Test
    fun testValueV2FromGenericValueAsEngineObjectData() =
        runBlockingTest {
            val fieldB = defForObjectB.codegenIncludedFields.first()
            val data = fieldB.valueV2FromGenericValue(
                classResolverForV2,
                graphqlSchemaForV2,
                RecordValue(
                    schemaForV2.types["ObjectA"]!! as ViaductGraphQLSchema.Object,
                    mapOf("a" to 100)
                ),
                true
            )
            assertTrue(data is EngineObjectData)
            assertEquals((data as EngineObjectData).fetch("a"), 100)

            val fieldC = defForObjectC.codegenIncludedFields.first()
            val listData = fieldC.valueV2FromGenericValue(
                classResolverForV2,
                graphqlSchemaForV2,
                listOf(
                    listOf(
                        RecordValue(
                            schemaForV2.types["ObjectA"]!! as ViaductGraphQLSchema.Object,
                            mapOf("a" to 100)
                        )
                    )
                ),
                true
            )
            assertEquals(1, (listData as List<*>).size)
            assertEquals(1, (listData[0] as List<*>).size)
            assertTrue((listData[0] as List<*>)[0] is EngineObjectData)
            assertEquals(100, ((listData[0] as List<*>)[0] as EngineObjectData).fetch("a"))
        }
}
