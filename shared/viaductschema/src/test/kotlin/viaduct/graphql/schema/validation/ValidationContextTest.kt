package viaduct.graphql.schema.validation

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

class ValidationContextTest {
    @Test
    fun `walkSchema visits all directives`() {
        val visitedDirectives = mutableListOf<String>()

        val rule = object : ValidationRule("test") {
            override fun visitDirective(
                ctx: ValidationContext,
                directive: ViaductSchema.Directive
            ) {
                visitedDirectives.add(directive.name)
            }
        }

        val sdl = """
            directive @myDirective on FIELD_DEFINITION
            type Query { hello: String }
        """.trimIndent()

        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val ctx = ValidationContext(schema)

        ctx.walkSchema(listOf(rule))

        visitedDirectives shouldContain "myDirective"
    }

    @Test
    fun `walkSchema visits all types`() {
        val visitedTypes = mutableListOf<String>()

        val rule = object : ValidationRule("test") {
            override fun visitTypeDef(
                ctx: ValidationContext,
                typeDef: ViaductSchema.TypeDef
            ) {
                visitedTypes.add(typeDef.name)
            }
        }

        val sdl = """
            type Query { hero: Character }
            interface Character { name: String }
            type Human implements Character { name: String }
            enum Episode { NEWHOPE, EMPIRE }
            union SearchResult = Human
            input SearchInput { text: String }
        """.trimIndent()

        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val ctx = ValidationContext(schema)

        ctx.walkSchema(listOf(rule))

        visitedTypes shouldContain "Query"
        visitedTypes shouldContain "Character"
        visitedTypes shouldContain "Human"
        visitedTypes shouldContain "Episode"
        visitedTypes shouldContain "SearchResult"
        visitedTypes shouldContain "SearchInput"
    }

    @Test
    fun `walkSchema visits fields and args`() {
        val visitedFields = mutableListOf<String>()
        val visitedArgs = mutableListOf<String>()

        val rule = object : ValidationRule("test") {
            override fun visitField(
                ctx: ValidationContext,
                field: ViaductSchema.Field
            ) {
                visitedFields.add(field.name)
            }

            override fun visitFieldArg(
                ctx: ValidationContext,
                arg: ViaductSchema.FieldArg
            ) {
                visitedArgs.add(arg.name)
            }
        }

        val sdl = """
            type Query {
                hero(episode: String): String
            }
        """.trimIndent()

        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val ctx = ValidationContext(schema)

        ctx.walkSchema(listOf(rule))

        visitedFields shouldContain "hero"
        visitedArgs shouldContain "episode"
    }

    @Test
    fun `reportError adds to error list`() {
        val schema = ViaductSchema.fromTypeDefinitionRegistry("type Query { hello: String }")
        val ctx = ValidationContext(schema)

        ctx.reportError("TEST_CODE", "Test message", SchemaLocation.ofType("Query"))
        ctx.reportError("TEST_CODE_2", "Test message 2", SchemaLocation.ofField("Query", "hello"))

        ctx.errors shouldHaveSize 2
        ctx.errors[0].code shouldBe "TEST_CODE"
        ctx.errors[1].location.toString() shouldBe "Query.hello"
    }

    @Test
    fun `walkSchema visits scalars`() {
        val visitedScalars = mutableListOf<String>()

        val rule = object : ValidationRule("test") {
            override fun visitScalar(
                ctx: ValidationContext,
                scalar: ViaductSchema.Scalar
            ) {
                visitedScalars.add(scalar.name)
            }
        }

        val sdl = """
            scalar DateTime
            type Query { time: DateTime }
        """.trimIndent()

        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val ctx = ValidationContext(schema)

        ctx.walkSchema(listOf(rule))

        visitedScalars shouldContain "DateTime"
    }

    @Test
    fun `walkSchema visits enum values`() {
        val visitedEnumValues = mutableListOf<String>()

        val rule = object : ValidationRule("test") {
            override fun visitEnumValue(
                ctx: ValidationContext,
                value: ViaductSchema.EnumValue
            ) {
                visitedEnumValues.add(value.name)
            }
        }

        val sdl = """
            enum Episode { NEWHOPE, EMPIRE, JEDI }
            type Query { episode: Episode }
        """.trimIndent()

        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val ctx = ValidationContext(schema)

        ctx.walkSchema(listOf(rule))

        visitedEnumValues shouldContainExactlyInAnyOrder listOf("NEWHOPE", "EMPIRE", "JEDI")
    }

    @Test
    fun `walkSchema visits unions`() {
        val visitedUnions = mutableListOf<String>()

        val rule = object : ValidationRule("test") {
            override fun visitUnion(
                ctx: ValidationContext,
                union: ViaductSchema.Union
            ) {
                visitedUnions.add(union.name)
            }
        }

        val sdl = """
            type Human { name: String }
            type Droid { name: String }
            union SearchResult = Human | Droid
            type Query { search: SearchResult }
        """.trimIndent()

        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val ctx = ValidationContext(schema)

        ctx.walkSchema(listOf(rule))

        visitedUnions shouldContain "SearchResult"
    }

    @Test
    fun `walkSchema visits input types`() {
        val visitedInputs = mutableListOf<String>()

        val rule = object : ValidationRule("test") {
            override fun visitInput(
                ctx: ValidationContext,
                input: ViaductSchema.Input
            ) {
                visitedInputs.add(input.name)
            }
        }

        val sdl = """
            input SearchInput { text: String }
            type Query { search(input: SearchInput): String }
        """.trimIndent()

        val schema = ViaductSchema.fromTypeDefinitionRegistry(sdl)
        val ctx = ValidationContext(schema)

        ctx.walkSchema(listOf(rule))

        visitedInputs shouldContain "SearchInput"
    }
}
