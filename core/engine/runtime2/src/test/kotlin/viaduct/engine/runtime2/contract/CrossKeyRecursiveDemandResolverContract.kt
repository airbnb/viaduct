package viaduct.engine.runtime2.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

interface CrossKeyRecursiveDemandResolverContract : ResolverContract {
    @Test
    fun `does not copy recursive demand between different grounded keys`() {
        var childrenApplications = 0
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                if (field.containingDef.name == "Item" &&
                    field.name == "children"
                ) {
                    childrenApplications += 1
                    check(childrenApplications <= 12) {
                        "recursive demand crossed grounded keys"
                    }
                }
            }
        }
        val testWorld =
            TestWorld.fromDSL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    extend type Query {
                      item: Item! @resolver(result: {})
                    }

                    type Item {
                      children(depth: Int!): [Item!]! @resolver(result: [{}])
                    }
                    """.trimIndent(),
            )

        resolveAndValidate(
            testWorld,
            """
                query {
                  item {
                    atOne: children(depth: 1) {
                      children(depth: 2) { __typename }
                    }
                    atTwo: children(depth: 2) {
                      children(depth: 1) { __typename }
                    }
                  }
                }
            """.trimIndent(),
            resolverObserver = invocationObserver,
        )

        assertEquals(4, childrenApplications)
    }
}
