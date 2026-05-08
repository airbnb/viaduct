package viaduct.tenant.codegen.kotlingen.bytecode

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.codegen.st.STContents
import viaduct.graphql.schema.ViaductSchema

class InputGenTest {
    private fun genInput(
        sdl: String,
        typename: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder(schema)
        val def = schema.types[typename]!! as ViaductSchema.Input
        val desc = InputTypeDescriptor(def.name, def.fields, def)
        return builder.inputKotlinGen(desc, "viaduct.api.types.Input")
    }

    private fun genArguments(
        sdl: String,
        typeName: String,
        fieldName: String
    ): STContents {
        val schema = mkSchema(sdl)
        val builder = mkKotlinGRTFilesBuilder(schema)
        val typeDef = schema.types[typeName]!! as ViaductSchema.Object
        val field = typeDef.fields.first { it.name == fieldName }
        val className = "${typeName}_${fieldName.replaceFirstChar { it.uppercase() }}_Arguments"
        val desc = InputTypeDescriptor(className, field.args, null, containingField = field)
        return builder.inputKotlinGen(desc, "viaduct.api.types.Arguments", field)
    }

    @Test
    fun `generates Reflection`() {
        val result = genInput(
            """
                type Query { empty: Int }
                input Input { x: Int, y: Input }
            """.trimIndent(),
            "Input"
        ).toString()
        assertTrue(result.contains("object Reflection : viaduct.api.reflect.Type<pkg.Input>"))
        assertTrue(result.contains("object Fields"))
    }

    @Test
    fun `Arguments Builder uses explicit typeName and fieldName`() {
        val result = genArguments(
            """
                type Query { empty: Int }
                type MyType { myField(arg: String!): String }
            """.trimIndent(),
            "MyType",
            "myField"
        ).toString()
        assertTrue(
            result.contains("""argumentsInputType("MyType_MyField_Arguments", "MyType", "myField""""),
            "Expected explicit name/typeName/fieldName call, got:\n$result"
        )
    }

    @Test
    fun `Arguments Builder with underscore type name uses explicit params`() {
        val result = genArguments(
            """
                type Query { empty: Int }
                type Under_Score { someField(arg: String!): String }
            """.trimIndent(),
            "Under_Score",
            "someField"
        ).toString()
        assertTrue(
            result.contains("""argumentsInputType("Under_Score_SomeField_Arguments", "Under_Score", "someField""""),
            "Expected explicit name/typeName/fieldName call for underscore type, got:\n$result"
        )
    }

    @Test
    fun `Input Builder still uses inputObjectInputType with className`() {
        val result = genInput(
            """
                type Query { empty: Int }
                input MyInput { x: Int }
            """.trimIndent(),
            "MyInput"
        ).toString()
        assertTrue(
            result.contains("""inputObjectInputType("MyInput""""),
            "Expected inputObjectInputType call for Input type, got:\n$result"
        )
    }

    @Test
    fun `generates of factory object`() {
        val result = genInput(
            """
                type Query { empty: Int }
                input MyInput { x: Int }
            """.trimIndent(),
            "MyInput"
        ).toString()
        assertTrue(
            result.contains("object of"),
            "Expected 'object of' factory singleton, got:\n$result"
        )
        assertTrue(
            result.contains("operator fun invoke(context: ExecutionContext, block: Builder.() -> Unit): MyInput"),
            "Expected invoke operator with builder-lambda signature, got:\n$result"
        )
        assertTrue(
            result.contains("Builder(context).apply(block).build()"),
            "Expected of body to construct builder, apply block, and build, got:\n$result"
        )
    }

    @Test
    fun `Builder implements InputValueBuilder`() {
        val result = genInput(
            """
                type Query { empty: Int }
                input MyInput { x: Int }
            """.trimIndent(),
            "MyInput"
        ).toString()
        assertTrue(
            result.contains("InputValueBuilder<MyInput>"),
            "Expected Builder to implement InputValueBuilder<MyInput>, got:\n$result"
        )
        assertTrue(
            result.contains("final override fun build()"),
            "Expected build() to be marked as final override, got:\n$result"
        )
    }
}
