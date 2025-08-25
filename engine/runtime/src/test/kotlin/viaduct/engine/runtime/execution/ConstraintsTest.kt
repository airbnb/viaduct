package viaduct.engine.runtime.execution

import graphql.execution.CoercedVariables
import graphql.language.Argument
import graphql.language.BooleanValue
import graphql.language.Directive
import graphql.language.Value
import graphql.language.VariableReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.runtime.execution.Constraints.Resolution
import viaduct.utils.collections.MaskedSet

class ConstraintsTest {
    private val schema = """
        type Query { empty:Int }
        type Foo { x:Int }
        type Bar { x:Int }
    """.trimIndent().asSchema
    private val Foo = schema.getObjectType("Foo")
    private val Bar = schema.getObjectType("Bar")

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
        assertEquals(Constraints.Drop, Constraints.Drop.narrowTypes(MaskedSet(listOf(Foo))))
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
        val trueVarCtx = Constraints.Ctx.empty.copy((CoercedVariables.of(mapOf("var" to true))))
        val falseVarCtx = Constraints.Ctx.empty.copy((CoercedVariables.of(mapOf("var" to false))))

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
            Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(Foo)))
                .narrowTypes(MaskedSet(listOf(Bar)))
        )
    }

    @Test
    fun `narrowTypes -- returns same instance for same instance of types`() {
        // this is a non-normative test that checks a performance optimization

        // given the same instance of possible types, return the same instance of Constraints
        val possibleTypes = MaskedSet(listOf(Foo))
        val c1 = Constraints.Unconstrained.narrowTypes(possibleTypes)
        val c2 = c1.narrowTypes(possibleTypes)
        assertSame(c1, c2)
    }

    @Test
    fun `Collect when ctx type in possible types`() {
        val ctx = Constraints.Ctx.empty.copy(parentTypes = MaskedSet(setOf(Foo)))
        assertEquals(
            Resolution.Collect,
            Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(Bar, Foo)))
                .solve(ctx)
        )
    }

    @Test
    fun `Drop when ctx type in possible types`() {
        val ctx = Constraints.Ctx.empty.copy(parentTypes = MaskedSet(setOf(Foo)))
        assertEquals(
            Resolution.Drop,
            Constraints.Unconstrained.narrowTypes(MaskedSet(listOf(Bar)))
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
