package viaduct.graphql.schema.validation.rules

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry
import viaduct.graphql.schema.validation.SchemaValidator
import viaduct.graphql.schema.validation.ValidationErrorCodes

class ParentFieldConstraintsRuleTest {
    private fun validate(
        sdl: String,
        parentDirectiveLocations: String = "FIELD_DEFINITION",
    ) = SchemaValidator(listOf(listOf(ParentFieldConstraintsRule())))
        .validate(
            ViaductSchema.fromTypeDefinitionRegistry(
                """
        directive @parent on $parentDirectiveLocations
        directive @resolver(selective: Boolean = false, isSelective: Boolean = false) on FIELD_DEFINITION | OBJECT
                """.trimIndent() + "\n$sdl"
            )
        )

    @Test
    fun `valid - nullable and non-null composite parent fields`() {
        val errors = validate(
            """
            type Query { company: Company @resolver }
            type Company {
              name: String
              user: User
            }
            type User {
              nullableParent: Company @parent
              requiredParent: Company! @parent
            }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - parent field cannot have arguments or list type`() {
        val errors = validate(
            """
            type Query { company: Company @resolver }
            type Company { user: User }
            type User {
              withArg(id: ID): Company @parent
              listParent: [Company] @parent
            }
            """.trimIndent()
        )

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.PARENT_FIELD_HAS_ARGS,
            ValidationErrorCodes.PARENT_FIELD_IS_LIST,
        )
    }

    @Test
    fun `invalid - parent field must return composite output type`() {
        val errors = validate(
            """
            type Query { user: User @resolver }
            type User { parentName: String @parent }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_TYPE_NOT_COMPOSITE
        errors[0].message shouldContain "User.parentName"
    }

    @Test
    fun `invalid - parent field cannot be declared on input type`() {
        val errors = validate(
            sdl = """
            type Query { placeholder: String }
            input ParentInput { id: ID }
            input ChildInput { parent: ParentInput @parent }
            """.trimIndent(),
            parentDirectiveLocations = "FIELD_DEFINITION | INPUT_FIELD_DEFINITION"
        )

        errors.map { it.code } shouldContainExactlyInAnyOrder listOf(
            ValidationErrorCodes.PARENT_FIELD_ON_NON_OUTPUT_TYPE,
            ValidationErrorCodes.PARENT_FIELD_TYPE_NOT_COMPOSITE,
        )
    }

    @Test
    fun `invalid - parent field cannot be declared on interface`() {
        val errors = validate(
            """
            type Query { company: Company @resolver }
            type Company { user: User }
            interface UserInterface { parent: Company @parent }
            type User implements UserInterface { parent: Company }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_ON_INTERFACE
        errors[0].message shouldContain "UserInterface.parent"
        errors[0].message shouldContain "Declare @parent directly on each implementing object field"
    }

    @Test
    fun `invalid - parent field cannot have resolver directive`() {
        val errors = validate(
            """
            type Query { company: Company @resolver }
            type Company { user: User }
            type User { parent: Company @parent @resolver }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_HAS_CONFLICTING_RESOLVER
        errors[0].message shouldContain "User.parent"
        errors[0].message shouldContain "@resolver"
    }

    @Test
    fun `invalid - parent field target produced by closest selective resolver`() {
        val errors = validate(
            """
            type Query { foo: Foo @resolver(selective: true) }
            type Foo { child: Bar }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_TARGET_HAS_SELECTIVE_RESOLVER
        errors[0].message shouldContain "Bar.parent"
        errors[0].message shouldContain "Query.foo"
    }

    @Test
    fun `invalid - parent field target type has selective resolver`() {
        val errors = validate(
            """
            type Query { foo: Foo }
            type Foo @resolver(isSelective: true) { child: Bar }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_TARGET_HAS_SELECTIVE_RESOLVER
        errors[0].message shouldContain "Bar.parent"
        errors[0].message shouldContain "Foo"
    }

    @Test
    fun `valid - parent field target produced by closest non-selective resolver`() {
        val errors = validate(
            """
            type Query { foo: Foo @resolver(selective: false) }
            type Foo { child: Bar }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - parent field can target interface implemented by producer type`() {
        val errors = validate(
            """
            type Query { parent: ConcreteParent @resolver }
            interface Parent { id: ID }
            type ConcreteParent implements Parent {
              id: ID
              child: Child
            }
            type Child { parent: Parent @parent }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `valid - parent producer field can be list or nested list`() {
        val errors = validate(
            """
            type Query { parent: Parent @resolver }
            type Parent {
              children: [Child]
              childGroups: [[GroupedChild]]
            }
            type Child { parent: Parent @parent }
            type GroupedChild { parent: Parent @parent }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }

    @Test
    fun `invalid - parent field child can be reached through another parent type`() {
        val errors = validate(
            """
            type Query {
              foo: Foo @resolver
              foo2: Foo2 @resolver
            }
            type Foo { bar: Bar }
            type Foo2 { bar2: Bar }
            type Bar {
              parent: Foo @parent
              x: String
            }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_CHILD_HAS_AMBIGUOUS_PARENT
        errors[0].message shouldContain "Bar.parent"
        errors[0].message shouldContain "Foo2.bar2"
    }

    @Test
    fun `invalid - parent field child has single producer from wrong parent type`() {
        val errors = validate(
            """
            type Query {
              foo: Foo @resolver
              foo2: Foo2 @resolver
            }
            type Foo { id: ID }
            type Foo2 { bar: Bar }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_CHILD_HAS_AMBIGUOUS_PARENT
        errors[0].message shouldContain "Bar.parent"
        errors[0].message shouldContain "Foo2.bar"
    }

    @Test
    fun `invalid - parent field child has no producer field`() {
        val errors = validate(
            """
            type Query { foo: Foo @resolver }
            type Foo { id: ID }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_CHILD_HAS_AMBIGUOUS_PARENT
        errors[0].message shouldContain "Bar.parent"
        errors[0].message shouldContain "<none>"
    }

    @Test
    fun `invalid - parent field child can be reached through multiple fields on declared parent type`() {
        val errors = validate(
            """
            type Query { foo: Foo @resolver }
            type Foo {
              firstBar: Bar
              secondBar: Bar
            }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_CHILD_HAS_AMBIGUOUS_PARENT
        errors[0].message shouldContain "Bar.parent"
        errors[0].message shouldContain "Foo.firstBar"
        errors[0].message shouldContain "Foo.secondBar"
    }

    @Test
    fun `invalid - parent field target reached through data field from selective resolver`() {
        val errors = validate(
            """
            type Query { container: Container @resolver(isSelective: true) }
            type Container { foo: Foo }
            type Foo { child: Bar }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors shouldHaveSize 1
        errors[0].code shouldBe ValidationErrorCodes.PARENT_FIELD_TARGET_HAS_SELECTIVE_RESOLVER
        errors[0].message shouldContain "Bar.parent"
        errors[0].message shouldContain "Query.container"
    }

    @Test
    fun `valid - non-selective closest field resolver shields selective ancestor`() {
        val errors = validate(
            """
            type Query { container: Container @resolver(selective: true) }
            type Container { foo: Foo @resolver(selective: false) }
            type Foo { child: Bar }
            type Bar { parent: Foo @parent }
            """.trimIndent()
        )

        errors.shouldBeEmpty()
    }
}
