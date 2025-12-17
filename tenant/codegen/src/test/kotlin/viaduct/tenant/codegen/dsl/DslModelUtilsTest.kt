package viaduct.tenant.codegen.dsl

import kotlin.test.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper
import viaduct.tenant.codegen.kotlingen.bytecode.mkSchema

class DslModelUtilsTest {

    @Nested
    @DisplayName("replaceGlobalIdWithString Extension")
    inner class ReplaceGlobalIdTests {

        @Test
        fun `replaces simple GlobalID with String`() {
            val input = "viaduct.api.globalid.GlobalID<User>"
            val result = input.replaceGlobalIdWithString()
            assertEquals("String", result)
        }

        @Test
        fun `replaces GlobalID in complex type`() {
            val input = "List<viaduct.api.globalid.GlobalID<Post>>"
            val result = input.replaceGlobalIdWithString()
            assertEquals("List<String>", result)
        }

        @Test
        fun `preserves non-GlobalID types`() {
            val input = "String"
            val result = input.replaceGlobalIdWithString()
            assertEquals("String", result)
        }

        @Test
        fun `handles multiple GlobalID replacements`() {
            val input = "Map<viaduct.api.globalid.GlobalID<User>, viaduct.api.globalid.GlobalID<Post>>"
            val result = input.replaceGlobalIdWithString()
            assertEquals("Map<String, String>", result)
        }

        @Test
        fun `handles nullable GlobalID`() {
            val input = "viaduct.api.globalid.GlobalID<User>?"
            val result = input.replaceGlobalIdWithString()
            assertEquals("String?", result)
        }
    }

    @Nested
    @DisplayName("simplifyKotlinType Extension")
    inner class SimplifyKotlinTypeTests {

        @Test
        fun `removes kotlin prefix`() {
            val input = "kotlin.String"
            val result = input.simplifyKotlinType()
            assertEquals("String", result)
        }

        @Test
        fun `removes kotlin collections prefix`() {
            val input = "kotlin.collections.List<String>"
            val result = input.simplifyKotlinType()
            assertEquals("List<String>", result)
        }

        @Test
        fun `handles nested collections`() {
            val input = "kotlin.collections.Map<kotlin.String, kotlin.collections.List<kotlin.Int>>"
            val result = input.simplifyKotlinType()
            assertEquals("Map<String, List<Int>>", result)
        }

        @Test
        fun `preserves non-kotlin types`() {
            val input = "com.example.CustomType"
            val result = input.simplifyKotlinType()
            assertEquals("com.example.CustomType", result)
        }

        @Test
        fun `handles nullable types`() {
            val input = "kotlin.String?"
            val result = input.simplifyKotlinType()
            assertEquals("String?", result)
        }
    }

    @Nested
    @DisplayName("isScalarOrEnum Extension")
    inner class IsScalarOrEnumTests {

        private fun getTypeDef(sdl: String, typeName: String): ViaductSchema.TypeDef {
            val schema = mkSchema(sdl)
            return schema.types[typeName]!!
        }

        @Test
        fun `returns true for scalar types`() {
            val schema = mkSchema("type Query { name: String }")
            val stringType = schema.types["String"]!!
            assertTrue(stringType.isScalarOrEnum())
        }

        @Test
        fun `returns true for enum types`() {
            val typeDef = getTypeDef(
                """
                type Query { status: Status }
                enum Status { ACTIVE INACTIVE }
                """.trimIndent(),
                "Status"
            )
            assertTrue(typeDef.isScalarOrEnum())
        }

        @Test
        fun `returns false for object types`() {
            val typeDef = getTypeDef(
                """
                type Query { user: User }
                type User { id: ID }
                """.trimIndent(),
                "User"
            )
            assertFalse(typeDef.isScalarOrEnum())
        }

        @Test
        fun `returns false for interface types`() {
            val typeDef = getTypeDef(
                """
                type Query { node: Node }
                interface Node { id: ID! }
                type User implements Node { id: ID! }
                """.trimIndent(),
                "Node"
            )
            assertFalse(typeDef.isScalarOrEnum())
        }

        @Test
        fun `returns false for input types`() {
            val typeDef = getTypeDef(
                """
                type Query { test: String }
                input UserInput { name: String }
                """.trimIndent(),
                "UserInput"
            )
            assertFalse(typeDef.isScalarOrEnum())
        }
    }

    @Nested
    @DisplayName("requiresSelectionSet Extension")
    inner class RequiresSelectionSetTests {

        private fun getTypeDef(sdl: String, typeName: String): ViaductSchema.TypeDef {
            val schema = mkSchema(sdl)
            return schema.types[typeName]!!
        }

        @Test
        fun `returns true for object types`() {
            val typeDef = getTypeDef(
                """
                type Query { user: User }
                type User { id: ID }
                """.trimIndent(),
                "User"
            )
            assertTrue(typeDef.requiresSelectionSet())
        }

        @Test
        fun `returns true for interface types`() {
            val typeDef = getTypeDef(
                """
                type Query { node: Node }
                interface Node { id: ID! }
                type User implements Node { id: ID! }
                """.trimIndent(),
                "Node"
            )
            assertTrue(typeDef.requiresSelectionSet())
        }

        @Test
        fun `returns true for union types`() {
            val typeDef = getTypeDef(
                """
                type Query { search: SearchResult }
                union SearchResult = User | Post
                type User { id: ID }
                type Post { id: ID }
                """.trimIndent(),
                "SearchResult"
            )
            assertTrue(typeDef.requiresSelectionSet())
        }

        @Test
        fun `returns false for scalar types`() {
            val schema = mkSchema("type Query { name: String }")
            val stringType = schema.types["String"]!!
            assertFalse(stringType.requiresSelectionSet())
        }

        @Test
        fun `returns false for enum types`() {
            val typeDef = getTypeDef(
                """
                type Query { status: Status }
                enum Status { ACTIVE INACTIVE }
                """.trimIndent(),
                "Status"
            )
            assertFalse(typeDef.requiresSelectionSet())
        }

        @Test
        fun `returns false for input types`() {
            val typeDef = getTypeDef(
                """
                type Query { test: String }
                input UserInput { name: String }
                """.trimIndent(),
                "UserInput"
            )
            assertFalse(typeDef.requiresSelectionSet())
        }
    }

    @Nested
    @DisplayName("buildParameterSignature Function")
    inner class BuildParameterSignatureTests {

        @Test
        fun `builds empty signature for empty parameters`() {
            val result = buildParameterSignature(emptyList(), includeAlias = false)
            assertEquals("", result)
        }

        @Test
        fun `builds signature with alias when includeAlias is true and params empty`() {
            val result = buildParameterSignature(emptyList(), includeAlias = true)
            assertEquals("alias: String? = null", result)
        }

        @Test
        fun `builds signature with parameters`() {
            val schema = mkSchema(
                """
                type Query { user(id: ID!, name: String): User }
                type User { id: ID }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "user" }

            val parameters = field.args.map {
                FieldParameterModel(it, TestPackages.DSL_PACKAGE, baseTypeMapper)
            }

            val result = buildParameterSignature(parameters, includeAlias = false)
            assertTrue(result.contains("id:"))
            assertTrue(result.contains("name:"))
        }

        @Test
        fun `appends alias when includeAlias is true`() {
            val schema = mkSchema(
                """
                type Query { user(id: ID!): User }
                type User { id: ID }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "user" }

            val parameters = field.args.map {
                FieldParameterModel(it, TestPackages.DSL_PACKAGE, baseTypeMapper)
            }

            val result = buildParameterSignature(parameters, includeAlias = true)
            assertTrue(result.contains("alias: String? = null"))
        }
    }

    @Nested
    @DisplayName("buildParameterSerializers Function")
    inner class BuildParameterSerializersTests {

        @Test
        fun `builds empty serializers for empty parameters`() {
            val result = buildParameterSerializers(emptyList())
            assertEquals("", result)
        }

        @Test
        fun `builds serializers for parameters`() {
            val schema = mkSchema(
                """
                type Query { user(id: ID!, name: String): User }
                type User { id: ID }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "user" }

            val parameters = field.args.map {
                FieldParameterModel(it, TestPackages.DSL_PACKAGE, baseTypeMapper)
            }

            val result = buildParameterSerializers(parameters)
            assertTrue(result.contains("\"id: \" + serializeValue(id)"))
            assertTrue(result.contains("\"name: \" + serializeValue(name)"))
        }

        @Test
        fun `joins multiple serializers with comma`() {
            val schema = mkSchema(
                """
                type Query { search(a: String, b: Int, c: Boolean): String }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "search" }

            val parameters = field.args.map {
                FieldParameterModel(it, TestPackages.DSL_PACKAGE, baseTypeMapper)
            }

            val result = buildParameterSerializers(parameters)
            // Should have commas between serializers
            assertTrue(result.count { it == ',' } == 2)
        }
    }

    @Nested
    @DisplayName("FieldParameterModel")
    inner class FieldParameterModelTests {

        @Test
        fun `converts input type to Map String Any`() {
            val schema = mkSchema(
                """
                type Query { users(filter: UserFilter): [User] }
                input UserFilter { name: String }
                type User { id: ID }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "users" }
            val filterArg = field.args.first { it.name == "filter" }

            val model = FieldParameterModel(filterArg, TestPackages.DSL_PACKAGE, baseTypeMapper)
            assertTrue(model.kotlinType.contains("Map<String, Any?>"))
        }

        @Test
        fun `preserves nullable input type`() {
            val schema = mkSchema(
                """
                type Query { users(filter: UserFilter): [User] }
                input UserFilter { name: String }
                type User { id: ID }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "users" }
            val filterArg = field.args.first { it.name == "filter" }

            val model = FieldParameterModel(filterArg, TestPackages.DSL_PACKAGE, baseTypeMapper)
            assertEquals("Map<String, Any?>?", model.kotlinType)
        }

        @Test
        fun `handles non-nullable input type`() {
            val schema = mkSchema(
                """
                type Query { users(filter: UserFilter!): [User] }
                input UserFilter { name: String }
                type User { id: ID }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "users" }
            val filterArg = field.args.first { it.name == "filter" }

            val model = FieldParameterModel(filterArg, TestPackages.DSL_PACKAGE, baseTypeMapper)
            assertEquals("Map<String, Any?>", model.kotlinType)
        }

        @Test
        fun `preserves escaped name`() {
            val schema = mkSchema(
                """
                type Query { test(class: String): String }
                """.trimIndent()
            )
            val baseTypeMapper = ViaductBaseTypeMapper(schema)
            val queryDef = schema.types["Query"]!! as ViaductSchema.Object
            val field = queryDef.fields.first { it.name == "test" }
            val classArg = field.args.first { it.name == "class" }

            val model = FieldParameterModel(classArg, TestPackages.DSL_PACKAGE, baseTypeMapper)
            assertEquals("class", model.argName)
            // escapedName should handle reserved keyword
            assertTrue(model.escapedName.contains("class") || model.escapedName.contains("`"))
        }
    }
}
