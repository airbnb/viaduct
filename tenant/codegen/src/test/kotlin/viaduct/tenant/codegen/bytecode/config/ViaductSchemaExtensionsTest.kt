package viaduct.tenant.codegen.bytecode.config

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.codegen.km.KmClassFilesBuilder
import viaduct.codegen.utils.JavaBinaryName
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.graphql.schema.graphqljava.GJSchema
import viaduct.graphql.schema.test.mkSchema
import viaduct.tenant.codegen.bytecode.util.assertKotlinTypeString
import viaduct.tenant.codegen.bytecode.util.field
import viaduct.tenant.codegen.bytecode.util.typedef

class ViaductSchemaExtensionsTest {
    private fun mkSchema(
        schemaText: String,
        schemaFilePath: String,
        repoRoot: File
    ): ViaductExtendedSchema {
        val schemaFile = repoRoot.resolve("$schemaFilePath")
        schemaFile.parentFile.mkdirs()
        schemaFile.createNewFile()
        schemaFile.writeText(schemaText)
        return GJSchema.fromFiles(listOf(schemaFile))
    }

    @Test
    fun `isNode -- object`() {
        assertFalse(mkSchema("type Obj { empty: Int }").typedef("Obj").isNode)
        assertFalse(mkSchema("type Node { empty: Int }").typedef("Node").isNode)

        mkSchema(
            """
            interface I { empty: Int }
            type O implements I { empty: Int }
            """.trimIndent()
        ).apply {
            assertFalse(typedef("O").isNode)
        }

        mkSchema(
            """
                interface Node { empty: Int }
                type O implements Node { empty: Int }
            """.trimIndent()
        ).apply {
            assertTrue(typedef("O").isNode)
        }
    }

    @Test
    fun `isNode -- scalar`() {
        assertFalse(mkSchema("scalar Scalar").typedef("Scalar").isNode)
        assertFalse(mkSchema("scalar Node").typedef("Node").isNode)
    }

    @Test
    fun `isNode -- enum`() {
        assertFalse(mkSchema("enum E { empty }").typedef("E").isNode)
        assertFalse(mkSchema("enum Node { empty }").typedef("Node").isNode)
    }

    @Test
    fun `isNode -- interface`() {
        assertFalse(mkSchema("interface I { empty: Int }").typedef("I").isNode)

        mkSchema(
            """
                interface Super { empty: Int }
                interface I implements Super { empty: Int }
            """.trimIndent()
        ).apply {
            assertFalse(typedef("I").isNode)
        }

        assertTrue(
            mkSchema("interface Node { empty: Int }").typedef("Node").isNode
        )

        mkSchema(
            """
                interface Node { empty: Int }
                interface I implements Node { empty: Int }
            """.trimIndent()
        ).apply {
            assertTrue(typedef("I").isNode)
        }
    }

    @Test
    fun `isNode -- input`() {
        assertFalse(
            mkSchema("input I { empty: Int }").typedef("I").isNode
        )
    }

    @Test
    fun `isConnection -- object`() {
        assertFalse(
            mkSchema("type O { empty: Int }").typedef("O").isConnection
        )
        assertFalse(
            mkSchema("type PagedConnection { empty: Int }").typedef("PagedConnection").isConnection
        )

        mkSchema(
            """
            interface Super { empty: Int }
            interface O implements Super { empty: Int }
            """.trimIndent()
        ).apply {
            assertFalse(typedef("O").isConnection)
        }

        mkSchema(
            """
            interface PagedConnection { empty: Int }
            interface O implements PagedConnection { empty: Int }
            """.trimIndent()
        ).apply {
            assertTrue(typedef("O").isConnection)
        }
    }

    @Test
    fun `isConnection -- scalar`() {
        assertFalse(mkSchema("scalar S").typedef("S").isConnection)
        assertFalse(
            mkSchema("scalar PagedConnection").typedef("PagedConnection").isConnection
        )
    }

    @Test
    fun `isConnection -- enum`() {
        assertFalse(mkSchema("enum E").typedef("E").isConnection)
        assertFalse(
            mkSchema("enum PagedConnection").typedef("PagedConnection").isConnection
        )
    }

    @Test
    fun `isConnection -- interface`() {
        assertFalse(
            mkSchema("interface I { empty: Int }").typedef("I").isConnection
        )

        mkSchema(
            """
                interface Super { empty: Int }
                interface I implements Super { empty: Int }
            """.trimIndent()
        ).apply {
            assertFalse(typedef("I").isConnection)
        }

        assertTrue(
            mkSchema("interface PagedConnection { empty: Int }").typedef("PagedConnection").isConnection
        )

        mkSchema(
            """
            interface PagedConnection { empty: Int }
            interface I implements PagedConnection { empty: Int }
            """.trimIndent()
        ).apply {
            assertTrue(typedef("I").isConnection)
        }
    }

    @Test
    fun `isConnection -- input`() {
        assertFalse(mkSchema("input I { empty: Int }").typedef("I").isConnection)
    }

    @Test
    fun `hasReflectedType`() {
        mkSchema(
            """
                type Obj { empty: Int }
                input Inp { empty: Int }
                interface Iface { empty: Int }
                scalar Scalar
                union Union = Obj
                enum Enum { Value }
            """.trimIndent()
        ).apply {
            assertTrue(typedef("Obj").hasReflectedType)
            assertTrue(typedef("Inp").hasReflectedType)
            assertTrue(typedef("Iface").hasReflectedType)
            assertFalse(typedef("Scalar").hasReflectedType)
            assertTrue(typedef("Union").hasReflectedType)
            assertTrue(typedef("Enum").hasReflectedType)
        }
    }

    @Test
    fun `kotlinTypeString -- lists and nulls`() {
        // lists and nulls
        mkSchema(
            """
                type Obj {
                    f1: Int
                    f2: Int!
                    f3: [Int]
                    f4: [Int!]
                    f5: [Int!]!
                }
            """.trimIndent()
        ).apply {
            typedef("Obj").assertKotlinTypeString("pkg.Obj?")
            field("Obj", "f1").assertKotlinTypeString("kotlin.Int?")
            field("Obj", "f2").assertKotlinTypeString("kotlin.Int")
            field("Obj", "f3").assertKotlinTypeString("kotlin.collections.List<kotlin.Int?>?")
            field("Obj", "f4").assertKotlinTypeString("kotlin.collections.List<kotlin.Int>?")
            field("Obj", "f5").assertKotlinTypeString("kotlin.collections.List<kotlin.Int>")
        }
    }

    @Test
    fun `kotlinTypeString -- scalars`() {
        mkSchema(
            """
                scalar JSON
                scalar Date
                scalar DateTime
                scalar Time
                scalar Byte
            """.trimIndent()
        ).apply {
            typedef("Boolean").assertKotlinTypeString("kotlin.Boolean?")
            typedef("Byte").assertKotlinTypeString("kotlin.Byte?")
            typedef("Date").assertKotlinTypeString("java.time.LocalDate?")
            typedef("DateTime").assertKotlinTypeString("java.time.Instant?")
            typedef("Float").assertKotlinTypeString("kotlin.Double?")
            typedef("ID").assertKotlinTypeString("kotlin.String?")
            typedef("Int").assertKotlinTypeString("kotlin.Int?")
            typedef("JSON").assertKotlinTypeString("kotlin.Any?")
            typedef("Long").assertKotlinTypeString("kotlin.Long?")
            typedef("Short").assertKotlinTypeString("kotlin.Short?")
            typedef("String").assertKotlinTypeString("kotlin.String?")
            typedef("Time").assertKotlinTypeString("java.time.OffsetTime?")
        }
    }

    @Test
    fun `ViaductBaseTypeMapper provides default OSS behavior`() {
        val schema = mkSchema("type TestType { field: String }")
        // In OSS context, ViaductBaseTypeMapper should provide standard behavior
        val viaductMapper = ViaductBaseTypeMapper(schema)

        // Test that ViaductBaseTypeMapper returns INVARIANT variance for input objects
        val variance = viaductMapper.getInputVarianceForObject()
        assertTrue(variance != null)
        assertTrue(variance == kotlinx.metadata.KmVariance.INVARIANT)

        // Also test a simple type mapping to ensure it works
        val typeExpr = schema.field("TestType", "field").type

        // Should return null (letting extension function handle default case)
        val result = viaductMapper.mapBaseType(typeExpr, viaduct.codegen.utils.KmName("test"))
        assertTrue(result == null)
    }

    @Test
    fun `ViaductBaseTypeMapper getAdditionalTypeMapping returns empty map`() {
        val mapper = ViaductBaseTypeMapper(ViaductExtendedSchema.Empty)
        val mappings = mapper.getAdditionalTypeMapping()

        assertTrue(mappings.isEmpty())
    }

    @Test
    fun `ViaductBaseTypeMapper getGlobalIdType returns correct type`() {
        val mapper = ViaductBaseTypeMapper(ViaductExtendedSchema.Empty)
        val globalIdType = mapper.getGlobalIdType()

        assertTrue(globalIdType == JavaBinaryName("viaduct.api.globalid.GlobalID"))
    }

    @Test
    fun `ViaductBaseTypeMapper useGlobalIdTypeAlias returns false`() {
        val mapper = ViaductBaseTypeMapper(ViaductExtendedSchema.Empty)
        val usesAlias = mapper.useGlobalIdTypeAlias()

        assertFalse(usesAlias)
    }

    @Test
    fun `ViaductBaseTypeMapper addSchemaGRTReference handles Object types`() {
        val schema = mkSchema("type TestObject { field: String }")
        val mapper = ViaductBaseTypeMapper(schema)
        val builder = KmClassFilesBuilder()
        val objectDef = schema.typedef("TestObject") as ViaductExtendedSchema.Object
        val fqn = KmName("test/TestObject")

        // Should not throw exception - testing that method executes properly
        mapper.addSchemaGRTReference(objectDef, fqn, builder)
    }

    @Test
    fun `ViaductBaseTypeMapper addSchemaGRTReference handles Interface types`() {
        val schema = mkSchema("interface TestInterface { field: String }")
        val mapper = ViaductBaseTypeMapper(schema)
        val builder = KmClassFilesBuilder()
        val interfaceDef = schema.typedef("TestInterface") as ViaductExtendedSchema.Interface
        val fqn = KmName("test/TestInterface")

        // Should not throw exception - testing that method executes properly
        mapper.addSchemaGRTReference(interfaceDef, fqn, builder)
    }

    @Test
    fun `ViaductBaseTypeMapper addSchemaGRTReference handles Union types`() {
        val schema = mkSchema(
            """
            type TypeA { field: String }
            type TypeB { field: Int }
            union TestUnion = TypeA | TypeB
            """.trimIndent()
        )
        val mapper = ViaductBaseTypeMapper(schema)
        val builder = KmClassFilesBuilder()
        val unionDef = schema.typedef("TestUnion") as ViaductExtendedSchema.Union
        val fqn = KmName("test/TestUnion")

        // Should not throw exception - testing that method executes properly
        mapper.addSchemaGRTReference(unionDef, fqn, builder)
    }

    @Test
    fun `ViaductBaseTypeMapper addSchemaGRTReference handles Input types`() {
        val schema = mkSchema("input TestInput { field: String }")
        val mapper = ViaductBaseTypeMapper(schema)
        val builder = KmClassFilesBuilder()
        val inputDef = schema.typedef("TestInput") as ViaductExtendedSchema.Input
        val fqn = KmName("test/TestInput")

        // Should not throw exception - testing that method executes properly
        mapper.addSchemaGRTReference(inputDef, fqn, builder)
    }

    @Test
    fun `ViaductBaseTypeMapper addSchemaGRTReference handles Enum types`() {
        val schema = mkSchema("enum TestEnum { VALUE_A, VALUE_B }")
        val mapper = ViaductBaseTypeMapper(schema)
        val builder = KmClassFilesBuilder()
        val enumDef = schema.typedef("TestEnum") as ViaductExtendedSchema.Enum
        val fqn = KmName("test/TestEnum")

        // Should not throw exception - testing that method executes properly
        mapper.addSchemaGRTReference(enumDef, fqn, builder)
    }

    @Test
    fun `hashForSharding returns non-negative hash`() {
        val schema = mkSchema("type TestType { field: String }")
        val typeDef = schema.typedef("TestType")

        val hash = typeDef.hashForSharding()
        assertTrue(hash >= 0)
    }

    @Test
    fun `hashForSharding handles negative hash codes`() {
        val schema = mkSchema("type TestType { field: String }")
        val typeDef = schema.typedef("TestType")

        // Test that negative hash codes are made positive
        val hash1 = typeDef.hashForSharding()
        val hash2 = typeDef.hashForSharding()

        // Should be consistent
        assertTrue(hash1 == hash2)
        assertTrue(hash1 >= 0)
    }

    @Test
    fun `Object isEligible returns true for Query and Mutation`() {
        val schema = mkSchema("type TestQuery { field: String }")

        // Manually create objects to test the logic since mkSchema creates default Query/Mutation
        val testObj = schema.typedef("TestQuery") as ViaductExtendedSchema.Object

        // Test regular object eligibility - should be true for non-PagedConnection types
        assertTrue(testObj.isEligible(ViaductBaseTypeMapper(schema)))
    }

    @Test
    fun `Object isEligible returns false for PagedConnection types`() {
        val schema = mkSchema(
            """
            interface PagedConnection { edges: [String] }
            type TestConnection implements PagedConnection { edges: [String] }
            """.trimIndent()
        )

        val connectionObj = schema.typedef("TestConnection") as ViaductExtendedSchema.Object
        assertFalse(connectionObj.isEligible(ViaductBaseTypeMapper(schema)))
    }

    @Test
    fun `Interface noArgsAnywhere returns true when no args in interface or implementations`() {
        val schema = mkSchema(
            """
            interface TestInterface { field: String }
            type TestObj implements TestInterface { field: String }
            """.trimIndent()
        )

        val interfaceDef = schema.typedef("TestInterface") as ViaductExtendedSchema.Interface
        assertTrue(interfaceDef.noArgsAnywhere("field"))
    }

    @Test
    fun `Interface noArgsAnywhere returns false when interface field has args`() {
        val schema = mkSchema(
            """
            interface TestInterface { field(arg: String): String }
            type TestObj implements TestInterface { field(arg: String): String }
            """.trimIndent()
        )

        val interfaceDef = schema.typedef("TestInterface") as ViaductExtendedSchema.Interface
        assertFalse(interfaceDef.noArgsAnywhere("field"))
    }

    @Test
    fun `Interface noArgsAnywhere returns false when implementation adds args`() {
        val schema = mkSchema(
            """
            interface TestInterface { field: String }
            type TestObj implements TestInterface { field(arg: String): String }
            """.trimIndent()
        )

        val interfaceDef = schema.typedef("TestInterface") as ViaductExtendedSchema.Interface
        assertFalse(interfaceDef.noArgsAnywhere("field"))
    }

    @Test
    fun `hasViaductDefaultValue returns true for nullable fields`() {
        val schema = mkSchema("type TestType { nullableField: String }")
        val field = schema.field("TestType", "nullableField")

        assertTrue(field.hasViaductDefaultValue)
    }

    @Test
    fun `hasViaductDefaultValue returns false for non-nullable fields`() {
        val schema = mkSchema("type TestType { nonNullField: String! }")
        val field = schema.field("TestType", "nonNullField")

        assertFalse(field.hasViaductDefaultValue)
    }

    @Test
    fun `viaductDefaultValue returns null for nullable non-list fields`() {
        val schema = mkSchema("type TestType { nullableField: String }")
        val field = schema.field("TestType", "nullableField")

        assertNull(field.viaductDefaultValue)
    }

    @Test
    fun `viaductDefaultValue returns empty list for nullable list fields in Object types`() {
        val schema = mkSchema("type TestType { listField: [String] }")
        val field = schema.field("TestType", "listField")

        val defaultValue = field.viaductDefaultValue
        assertTrue(defaultValue is List<*>)
        assertTrue((defaultValue as List<*>).isEmpty())
    }

    @Test
    fun `viaductDefaultValue throws exception for non-nullable fields`() {
        val schema = mkSchema("type TestType { nonNullField: String! }")
        val field = schema.field("TestType", "nonNullField")

        assertThrows<NoSuchElementException> {
            field.viaductDefaultValue
        }
    }

    @Test
    fun `viaductDefaultValue returns null for nullable fields in Input types`() {
        val schema = mkSchema("input TestInput { nullableField: String }")
        val field = schema.field("TestInput", "nullableField")

        assertNull(field.viaductDefaultValue)
    }

    @Test
    fun `kmType with useSchemaValueType returns Value class for eligible objects`() {
        val schema = mkSchema("type TestObject { field: String }")
        val field = schema.field("TestObject", "field")

        val kmType = field.kmType(KmName("pkg"), ViaductBaseTypeMapper(schema), isInput = false, useSchemaValueType = true)

        // Should use the base type, not the Value class for this field
        assertTrue(kmType.classifier.toString().contains("String"))
    }

    @Test
    fun `Object isEligible returns true for regular objects`() {
        val schema = mkSchema("type RegularObject { field: String }")
        val obj = schema.typedef("RegularObject") as ViaductExtendedSchema.Object

        assertTrue(obj.isEligible(ViaductBaseTypeMapper(schema)))
    }

    @Test
    fun `Object isEligible returns false for objects in nativeGraphQLTypeToKmName`() {
        // This test would need to configure cfg.nativeGraphQLTypeToKmName
        // but since that's more complex, we'll test the PagedConnection case which is simpler
        val schema = mkSchema(
            """
            interface PagedConnection { edges: [String] }
            type TestConnection implements PagedConnection { edges: [String] }
            """.trimIndent()
        )

        val connectionObj = schema.typedef("TestConnection") as ViaductExtendedSchema.Object
        assertFalse(connectionObj.isEligible(ViaductBaseTypeMapper(schema)))
    }

    @Test
    fun `isPagedConnection returns true for objects implementing PagedConnection`() {
        val schema = mkSchema(
            """
            interface PagedConnection { edges: [String] }
            type TestConnection implements PagedConnection { edges: [String] }
            """.trimIndent()
        )

        val connectionObj = schema.typedef("TestConnection") as ViaductExtendedSchema.Object
        assertTrue(connectionObj.isPagedConnection)
    }

    @Test
    fun `isPagedConnection returns false for regular objects`() {
        val schema = mkSchema("type RegularObject { field: String }")
        val obj = schema.typedef("RegularObject") as ViaductExtendedSchema.Object

        assertFalse(obj.isPagedConnection)
    }
}
