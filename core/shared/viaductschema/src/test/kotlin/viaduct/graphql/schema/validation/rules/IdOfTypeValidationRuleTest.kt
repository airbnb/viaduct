package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class IdOfTypeValidationRuleTest {
    private val preamble = """
        directive @idOf(type: String!) on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION
        interface Node { id: ID! }
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(IdOfTypeValidationRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `should pass when idOf references a Node object`() {
        val errors = validate(
            """
            type MyNode implements Node { id: ID! }
            type Query {
                nodeId: ID @idOf(type: "MyNode")
            }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when idOf references a Node interface`() {
        val errors = validate(
            """
            interface MyInterface implements Node { id: ID! }
            type Query {
                ifaceId: ID @idOf(type: "MyInterface")
            }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when idOf references Node directly`() {
        val errors = validate(
            """
            type Query {
                nodeId: ID @idOf(type: "Node")
            }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when idOf is on a field argument`() {
        val errors = validate(
            """
            type MyNode implements Node { id: ID! }
            type Query {
                nodeById(id: ID! @idOf(type: "MyNode")): MyNode
            }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when idOf is on an input field`() {
        val errors = validate(
            """
            type MyNode implements Node { id: ID! }
            type Query { placeholder: String }
            input MyInput {
                nodeId: ID! @idOf(type: "MyNode")
            }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should fail when idOf references a non-existent type`() {
        val errors = validate(
            """
            type Query {
                badId: ID @idOf(type: "NonExistent")
            }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.ID_OF_TYPE_NOT_FOUND
        errors[0].message shouldContain "NonExistent"
        errors[0].message shouldContain "references undefined type"
    }

    @Test
    fun `should fail when idOf references a type that does not implement Node`() {
        val errors = validate(
            """
            type NotANode { name: String }
            type Query {
                badId: ID @idOf(type: "NotANode")
            }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.ID_OF_TYPE_NOT_NODE
        errors[0].message shouldContain "NotANode"
        errors[0].message shouldContain "references non-Node type"
    }

    @Test
    fun `should fail when idOf references an enum type`() {
        val errors = validate(
            """
            enum Status { ACTIVE INACTIVE }
            type Query {
                badId: ID @idOf(type: "Status")
            }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.ID_OF_TYPE_NOT_NODE
        errors[0].message shouldContain "Status"
    }

    @Test
    fun `should fail when idOf on argument references non-existent type`() {
        val errors = validate(
            """
            type Query {
                lookup(id: ID! @idOf(type: "Ghost")): String
            }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.ID_OF_TYPE_NOT_FOUND
        errors[0].message shouldContain "Ghost"
    }

    @Test
    fun `should report multiple errors for multiple invalid fields`() {
        val errors = validate(
            """
            type Query {
                a: ID @idOf(type: "Missing1")
                b: ID @idOf(type: "Missing2")
            }
            """.trimIndent()
        )
        errors shouldHaveSize 2
        errors.all { it.code == ValidationErrorCodes.ID_OF_TYPE_NOT_FOUND } shouldBe true
    }

    @Test
    fun `should fail when idOf on input field references non-existent type`() {
        val errors = validate(
            """
            type Query { placeholder: String }
            input MyInput {
                nodeId: ID! @idOf(type: "Ghost")
            }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.ID_OF_TYPE_NOT_FOUND
        errors[0].message shouldContain "Ghost"
        errors[0].message shouldContain "references undefined type"
    }
}
