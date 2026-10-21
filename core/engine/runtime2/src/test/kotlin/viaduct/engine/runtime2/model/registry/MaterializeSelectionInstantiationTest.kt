@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.model.registry

import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.usedVariables

class MaterializeSelectionInstantiationTest {
    private val fixture = TestWorld.fromSDL("type Query { checked: Int child: Child echo(value: Int): Int } type Child { flag: Boolean }")
    private val schema = fixture.schemas
    private val target = ResolverTarget.FieldValueResolverTarget(schema.loweredSchema.requireObjectField("Query", "checked"))

    private fun occurrence() = ResolverOccurrenceId.at(ObjectEngineResult.of(schema.loweredSchema.requireQueryTypeDef()), emptyList())

    @Test
    fun `constant trees are shared across distinct applications`() {
        val source = schema.fragmentFrom("fragment Input on Query { child { flag } echo(value: 1) }", variableTarget = target).materializeSelections
        assertSame(source, source.instantiateVariables(occurrence()))
        assertSame(source, source.instantiateVariables(occurrence()))
        val construction = source.constructionSelections()
        assertSame(construction, construction.instantiateVariables(occurrence()))
    }

    @Test
    fun `mixed trees retain static children and distinct occurrence bindings`() {
        val source = schema.fragmentFrom("fragment Input on Query { child { flag } echo(value: ${'$'}v) }", variableTarget = target).materializeSelections
        val first = source.instantiateVariables(occurrence())
        val second = source.instantiateVariables(occurrence())
        assertSame(source.filter { it.responseKey == "child" }.single().subselections, first.filter { it.responseKey == "child" }.single().subselections)
        assertNotEquals(first.constructionSelections().usedVariables(), second.constructionSelections().usedVariables())
        assertTrue(first.constructionSelections().usedVariables().all { it.isInstantiated })
        val type = schema.loweredSchema.requireQueryTypeDef()
        val firstLayout = first.collect(type)
        val secondLayout = second.collect(type)
        assertSame(firstLayout, first.collect(type))
        assertSame(secondLayout, second.collect(type))
        assertNotEquals(firstLayout["echo"].key.arguments, secondLayout["echo"].key.arguments)
        val construction = source.constructionSelections()
        val firstConstruction = construction.instantiateVariables(occurrence())
        val secondConstruction = construction.instantiateVariables(occurrence())
        assertSame(
            construction.filter { it.key.field.name == "child" }.single().subselections,
            firstConstruction.filter { it.key.field.name == "child" }.single().subselections,
        )
        assertNotEquals(firstConstruction.usedVariables(), secondConstruction.usedVariables())
    }

    @Test
    fun `nested guard variables are instantiated even when arguments are constant`() {
        val source = schema.fragmentFrom("fragment Input on Query { child { flag @include(if: ${'$'}included) } }", variableTarget = target).materializeSelections
        val first = source.instantiateVariables(occurrence())
        val second = source.instantiateVariables(occurrence())
        assertTrue(first.constructionSelections().usedVariables().all { it.isInstantiated })
        assertNotEquals(first.constructionSelections().usedVariables(), second.constructionSelections().usedVariables())
        val construction = source.constructionSelections()
        val firstConstruction = construction.instantiateVariables(occurrence())
        val secondConstruction = construction.instantiateVariables(occurrence())
        assertTrue(firstConstruction.usedVariables().all { it.isInstantiated })
        assertNotEquals(firstConstruction.usedVariables(), secondConstruction.usedVariables())
    }
}
