package viaduct.engine.runtime2.contract

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.EngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.schema.operationSelectionsFrom

/**
 * Output-boundary test policy for resolution strategies that consume complete resolver outputs.
 */
interface CompleteResolverOutputPolicyContract : ResolverContract {
    override val selectiveResolvers: Boolean
        get() = false

    @Test
    fun `retains unselected passive fields from complete resolver outputs`() {
        val resolved = resolvePassiveOutputFixture()

        assertEquals(
            setOf("requested", "extra"),
            resolved.fieldNames,
        )
    }

    @Test
    fun `rejects a selective resolver world`() {
        assertRejectsWorldMode(selectiveResolvers = true)
    }
}

/**
 * Output-boundary test policy for resolution strategies that project resolver outputs to demand.
 */
interface SelectiveResolverOutputPolicyContract : ResolverContract {
    override val selectiveResolvers: Boolean
        get() = true

    @Test
    fun `omits unselected passive fields from selective resolver outputs`() {
        val resolved = resolvePassiveOutputFixture()

        assertEquals(setOf("requested"), resolved.fieldNames)
    }

    @Test
    fun `rejects a non-selective resolver world`() {
        assertRejectsWorldMode(selectiveResolvers = false)
    }
}

/**
 * Object-fragment output policy for resolution strategies that retain complete passive subtrees.
 */
interface CompleteObjectFragmentOutputPolicyContract : ResolverContract {
    @Test
    fun `retains complete passive recursive output`() {
        val resolved = resolveRecursiveOutputFixture()

        assertEquals(setOf("label", "next"), resolved.fieldNames)
        assertTrue(resolved.hasNext)
        assertEquals(null, resolved.nextValue)
    }
}

/**
 * Object-fragment output policy for strategies that project passive subtrees to required demand.
 */
interface SelectiveObjectFragmentOutputPolicyContract : ResolverContract {
    @Test
    fun `projects passive recursive output to required demand`() {
        val resolved = resolveRecursiveOutputFixture()

        assertEquals(setOf("label"), resolved.fieldNames)
        assertTrue(!resolved.hasNext)
    }
}

private data class PassiveOutputFixtureResult(
    val fieldNames: Set<String>,
)

private fun ResolverContract.assertRejectsWorldMode(selectiveResolvers: Boolean) {
    val testWorld =
        TestWorld.fromSDL(
            schemaSDL = "type Query { value: Int }",
            selectiveResolvers = selectiveResolvers,
        )
    val world = testWorld.assumptions
    val selections = testWorld.schemas.operationSelectionsFrom("query { __typename }")

    assertFailsWith<IllegalArgumentException> {
        resolve(
            viaduct.engine.runtime2.resolution.framework.SharedOperationContext.create(world),
            world.objectOf("Query"),
            selections,
        )
    }
}

private fun ResolverContract.resolvePassiveOutputFixture(): PassiveOutputFixtureResult {
    val testWorld =
        TestWorld.fromDSL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL =
                """
                extend type Query {
                  user: User! @resolver(result: {requested: 1, extra: 2})
                }

                type User {
                  requested: Int!
                  extra: Int!
                }
                """.trimIndent(),
        )
    val world = testWorld.assumptions
    val result =
        resolveAndValidate(testWorld, "query { user { requested } }")
    val user =
        assertIs<ObjectEngineResult>(
            result.getCell(world.schema.contractKey("Query", "user")).get(),
        )
    return PassiveOutputFixtureResult(
        fieldNames = user.keys.map { it.field.name }.toSet(),
    )
}

private data class RecursiveOutputFixtureResult(
    val fieldNames: Set<String>,
    val hasNext: Boolean,
    val nextValue: EngineResult?,
)

private fun ResolverContract.resolveRecursiveOutputFixture(): RecursiveOutputFixtureResult {
    val invocationObserver = object : CorrectnessResolverObserver() {
        override fun onResolverInvocation(observation: ResolverInvocationObservation) {
            super.onResolverInvocation(observation)
            val field = observation.field
            val input = observation.input

            if (
                field.containingDef.name == "Query" &&
                field.name == "chain"
            ) {
                require(input.hasExactlyFields())
            }
        }
    }
    val testWorld =
        TestWorld.fromDSL(
            selectiveResolvers = selectiveResolvers,
            schemaSDL =
                """
                extend type Query {
                  chain: Chain!
                    @resolver(
                      result: {
                        label: 1
                        next: {label: 2, next: null}
                      }
                    )
                }

                type Chain {
                  label: Int!
                  next: Chain
                  computed: Int!
                    @resolver(
                      of: "next { label }"
                      result: "sum(next.label)"
                    )
                }
                """.trimIndent(),
        )
    val world = testWorld.assumptions
    val result =
        resolveAndValidate(testWorld, "query { chain { computed } }", resolverObserver = invocationObserver)
    val chain =
        assertIs<ObjectEngineResult>(
            result.getCell(world.schema.contractKey("Query", "chain")).get(),
        )
    val next =
        assertIs<ObjectEngineResult>(
            chain.getCell(world.schema.contractKey("Chain", "next")).get(),
        )
    val nextKey = world.schema.contractKey("Chain", "next")
    return RecursiveOutputFixtureResult(
        fieldNames = next.keys.map { it.field.name }.toSet(),
        hasNext = nextKey in next.keys,
        nextValue = next.keys.takeIf { nextKey in it }?.let { next.getCell(nextKey).get() },
    )
}
