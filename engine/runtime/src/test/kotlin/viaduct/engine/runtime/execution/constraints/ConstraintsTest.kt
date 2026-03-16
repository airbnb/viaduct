package viaduct.engine.runtime.execution.constraints

import graphql.execution.CoercedVariables
import graphql.language.Argument
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.Value
import graphql.language.VariableReference
import graphql.schema.GraphQLCompositeType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.ViaductSchema
import viaduct.engine.runtime.execution.constraints.Constraints.Resolution
import viaduct.utils.collections.MaskedSet

class ConstraintsTest {
    private val schema = """
        type Query { empty:Int }
        type Foo { x:Int }
        type Bar { x:Int }
    """.trimIndent().asSchema
    private val foo = schema.getObjectType("Foo")
    private val bar = schema.getObjectType("Bar")

    @Test
    fun `Unconstrained is Collect for empty ctx`() {
        assertEquals(Resolution.Collect, Constraints.Unconstrained.solve(Constraints.Ctx.empty))
    }

    @Test
    fun `Drop is Drop for empty ctx`() {
        assertEquals(Resolution.Drop, Constraints.Drop.solve(Constraints.Ctx.empty))
    }

    @Test
    fun `Drop withDirectives is Drop`() {
        assertEquals(Constraints.Drop, Constraints.Drop.withDirective(skip(false.value)))
    }

    @Test
    fun `Drop narrowTypes is Drop`() {
        assertEquals(Constraints.Drop, Constraints.Drop.narrowTypes(MaskedSet(listOf(foo))))
    }

    @Test
    fun `create from skip-true is Drop`() {
        assertEquals(Constraints.Drop, mk(skip(true.value)))
    }

    @Test
    fun `create from include-false is Drop`() {
        assertEquals(Constraints.Drop, mk(include(false.value)))
    }

    @Test
    fun `create from skips and includes with same var ref is Drop`() {
        assertEquals(
            Constraints.Drop,
            mk(
                skip("var".varRef),
                include("var".varRef)
            )
        )
    }

    @Test
    fun `solve directives with unknown variable value is Unsolved`() {
        assertEquals(
            Resolution.Unsolved,
            mk(skip("var".varRef)).solve(Constraints.Ctx.empty)
        )
    }

    @Test
    fun `solve directives with known variable values`() {
        val trueVarCtx = Constraints.Ctx.empty.copy(variables = CoercedVariables.of(mapOf("var" to true)))
        val falseVarCtx = Constraints.Ctx.empty.copy(variables = CoercedVariables.of(mapOf("var" to false)))

        // skips
        assertEquals(
            Resolution.Drop,
            mk(skip("var".varRef)).solve(trueVarCtx)
        )
        assertEquals(
            Resolution.Collect,
            mk(skip("var".varRef)).solve(falseVarCtx)
        )

        // includes
        assertEquals(
            Resolution.Drop,
            mk(include("var".varRef)).solve(falseVarCtx)
        )
        assertEquals(
            Resolution.Collect,
            mk(include("var".varRef)).solve(trueVarCtx)
        )
    }

    @Test
    fun `withDirectives returns Drop for droppable directives`() {
        assertEquals(
            Constraints.Drop,
            Constraints.Unconstrained.withDirective(skip(true.value))
        )
        assertEquals(
            Constraints.Drop,
            Constraints.Unconstrained.withDirective(include(false.value))
        )
        assertEquals(
            Constraints.Drop,
            Constraints.Unconstrained
                .withDirective(skip("var".varRef))
                .withDirective(include("var".varRef))
        )
    }

    @Test
    fun `narrowTypes -- returns Drop for unsatisfiable narrowings`() {
        assertEquals(
            Constraints.Drop,
            Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(foo)))
                .narrowTypes(MaskedSet(listOf(bar)))
        )
    }

    @Test
    fun `narrowTypes -- returns same instance for same instance of types`() {
        // this is a non-normative test that checks a performance optimization

        // given the same instance of possible types, return the same instance of Constraints
        val possibleTypes = MaskedSet(listOf(foo))
        val c1 = Constraints.Unconstrained.narrowTypes(possibleTypes)
        val c2 = c1.narrowTypes(possibleTypes)
        assertSame(c1, c2)
    }

    @Test
    fun `Collect when ctx type in possible types`() {
        val ctx = Constraints.Ctx.empty.copy(parentTypes = MaskedSet(setOf(foo)))
        assertEquals(
            Resolution.Collect,
            Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(bar, foo)))
                .solve(ctx)
        )
    }

    @Test
    fun `Drop when ctx type in possible types`() {
        val ctx = Constraints.Ctx.empty.copy(parentTypes = MaskedSet(setOf(foo)))
        assertEquals(
            Resolution.Drop,
            Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(bar)))
                .solve(ctx)
        )
    }

    @Test
    fun `create returns Unconstrained where possible`() {
        assertEquals(
            Constraints.Unconstrained,
            Constraints(emptyList(), null)
        )
    }

    @Test
    fun `Resolution isDrop -- true for Drop`() {
        assertTrue(Resolution.Drop.isDrop)
    }

    @Test
    fun `Resolution isDrop -- false for Collect`() {
        assertFalse(Resolution.Collect.isDrop)
    }

    @Test
    fun `Resolution isDrop -- false for Unsolved`() {
        assertFalse(Resolution.Unsolved.isDrop)
    }

    @Test
    fun `clearTypes on Unconstrained returns Unconstrained`() {
        assertSame(Constraints.Unconstrained, Constraints.Unconstrained.clearTypes())
    }

    @Test
    fun `clearTypes on Drop returns Drop`() {
        assertSame(Constraints.Drop, Constraints.Drop.clearTypes())
    }

    @Test
    fun `clearTypes removes type constraints`() {
        val constrained = Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(foo)))
        val barCtx = Constraints.Ctx(null, MaskedSet(setOf(bar)))
        assertEquals(Resolution.Drop, constrained.solve(barCtx))
        assertEquals(Resolution.Collect, constrained.clearTypes().solve(barCtx))
    }

    @Test
    fun `clearTypes preserves directive constraints`() {
        val constrained = Constraints.Unconstrained
            .narrowTypes(MaskedSet(listOf(foo)))
            .withDirective(skip("var".varRef))
        val cleared = constrained.clearTypes()

        // Directive constraint (@skip with unknown var) is preserved
        assertEquals(Resolution.Unsolved, cleared.solve(Constraints.Ctx.empty))

        // But type constraint is gone: solving with Bar context no longer Drops
        val barCtx = Constraints.Ctx(null, MaskedSet(setOf(bar)))
        assertEquals(Resolution.Unsolved, cleared.solve(barCtx))
    }

    @Test
    fun `Ctx empty has null parentTypes`() {
        assertNull(Constraints.Ctx.empty.parentTypes)
    }

    @Test
    fun `null parentTypes solves as Collect for type-constrained Constraints`() {
        val constrained = Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(foo)))
        assertEquals(Resolution.Collect, constrained.solve(Constraints.Ctx(null, null)))
    }

    @Test
    fun `narrowToImpls narrows to possible object types of interface`() {
        val interfaceSchema = """
            type Query { empty: Int }
            interface Animal { name: String }
            type Dog implements Animal { name: String }
            type Cat implements Animal { name: String }
        """.trimIndent().asSchema
        val viaductSchema = ViaductSchema(interfaceSchema)
        val animalType = interfaceSchema.getType("Animal") as GraphQLCompositeType
        val Dog = interfaceSchema.getObjectType("Dog")
        val Cat = interfaceSchema.getObjectType("Cat")

        val constrained = Constraints.Unconstrained.narrowToImpls(animalType, viaductSchema)

        assertEquals(Resolution.Collect, constrained.solve(Constraints.Ctx(null, MaskedSet(setOf(Dog)))))
        assertEquals(Resolution.Collect, constrained.solve(Constraints.Ctx(null, MaskedSet(setOf(Cat)))))
    }

    @Test
    fun `narrowToImpls on Drop stays Drop`() {
        val interfaceSchema = """
            type Query { empty: Int }
            interface Node { id: String }
            type Item implements Node { id: String }
        """.trimIndent().asSchema
        val viaductSchema = ViaductSchema(interfaceSchema)
        val nodeType = interfaceSchema.getType("Node") as GraphQLCompositeType

        assertSame(Constraints.Drop, Constraints.Drop.narrowToImpls(nodeType, viaductSchema))
    }

    private val Boolean.value get() = BooleanValue.of(this)
    private val String.varRef get() = VariableReference.of(this)

    private fun skip(value: Value<*>): Directive = mkDir("skip", value)

    private fun include(value: Value<*>): Directive = mkDir("include", value)

    private fun mkDir(
        name: String,
        ifValue: Value<*>
    ): Directive =
        Directive.newDirective()
            .name(name)
            .argument(Argument("if", ifValue))
            .build()

    private fun mk(vararg directives: Directive): Constraints = Constraints(directives.toList(), null)
}
