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

class NodeInterfaceIdConsistencyRuleTest {
    private val preamble = """
        interface Node { id: ID! }
    """.trimIndent()

    private fun validate(sdl: String) =
        SchemaValidator(listOf(listOf(NodeInterfaceIdConsistencyRule())))
            .validate(ViaductSchema.fromTypeDefinitionRegistry("$preamble\n$sdl"))

    @Test
    fun `should fail when a Node object also implements a non-Node interface with an id field`() {
        val errors = validate(
            """
            interface Foo { id: ID! }
            type A implements Node & Foo { id: ID! }
            """.trimIndent()
        )
        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.NODE_INTERFACE_ID_INCONSISTENT
        errors[0].message shouldContain "A"
        errors[0].message shouldContain "Foo"
    }

    @Test
    fun `should fail when a Node interface also implements a non-Node interface with an id field`() {
        val errors = validate(
            """
            interface Foo { id: ID! }
            interface Bar implements Node & Foo { id: ID! }
            type Concrete implements Bar & Foo & Node { id: ID! }
            """.trimIndent()
        )
        // Bar and Concrete both directly implement Foo alongside Node, so both are flagged.
        errors shouldHaveSize 2
        errors.all { it.code == ValidationErrorCodes.NODE_INTERFACE_ID_INCONSISTENT } shouldBe true
        errors.map { it.message }.any { it.contains("Bar") } shouldBe true
        errors.map { it.message }.any { it.contains("Concrete") } shouldBe true
    }

    @Test
    fun `should pass when the co-implemented interface also implements Node`() {
        val errors = validate(
            """
            interface Foo implements Node { id: ID! }
            type A implements Node & Foo { id: ID! }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when the co-implemented interface has no id field`() {
        val errors = validate(
            """
            interface Foo { name: String }
            type A implements Node & Foo { id: ID!, name: String }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass when a non-Node type implements an interface with an id field`() {
        val errors = validate(
            """
            interface Foo { id: String }
            type A implements Foo { id: String }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should pass for a Node type with no other implemented interfaces`() {
        val errors = validate(
            """
            type A implements Node { id: ID! }
            """.trimIndent()
        )
        errors.shouldBeEmpty()
    }

    @Test
    fun `should report one error per non-Node interface with an id field`() {
        val errors = validate(
            """
            interface Foo { id: ID! }
            interface Baz { id: ID! }
            type A implements Node & Foo & Baz { id: ID! }
            """.trimIndent()
        )
        errors shouldHaveSize 2
        errors.all { it.code == ValidationErrorCodes.NODE_INTERFACE_ID_INCONSISTENT } shouldBe true
    }
}
