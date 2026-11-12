package viaduct.engine.runtime2.execution

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertTrue
import viaduct.engine.runtime2.execution.testing.ExecutionTestFixtureResource
import viaduct.engine.runtime2.execution.testing.assertResult
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.resolution.framework.ResolutionObserver
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation

/**
 * GraphQL-boundary smoke coverage for deterministic Resolution scenarios.
 *
 * These tests intentionally assert only behavior observable through GraphQL.execute.
 */
class GraphQLExecuteSmokeTest : ExecutionTestFixtureResource {
    @Test
    fun `completes external typename without runtime2 typename resolver applications`() {
        val applications = ConcurrentLinkedQueue<String>()
        val resolverSchema =
            """
            extend type Query {
              viewer: User! @resolver(script: "{:}")
              item: Item! @resolver(script: "{'__typename': 'A'}")
              choice: Choice! @resolver(script: "{'__typename': 'B'}")
            }

            type User {
              value: Int
            }

            interface Item {
              value: Int
            }

            union Choice = A | B

            type A implements Item {
              value: Int
            }

            type B implements Item {
              value: Int
            }
            """.trimIndent()
        val observer = object : ResolutionObserver {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                val field = observation.field
                applications += "${field.containingDef.name}.${field.name}"
            }
        }
        val world =
            TestWorld.fromDSL(
                resolverSchema,
            )
        val fixture =
            fixtureFromWorld(
                schemaSDL =
                    """
                    type Query {
                      viewer: User!
                      item: Item!
                      choice: Choice!
                    }

                    type User {
                      value: Int
                    }

                    interface Item {
                      value: Int
                    }

                    union Choice = A | B

                    type A implements Item {
                      value: Int
                    }

                    type B implements Item {
                      value: Int
                    }
                    """.trimIndent(),
                world = world,
            )

        fixture.runQuery("query { __typename }", resolutionObserver = observer).assertResult(
            mapOf("__typename" to "Query"),
        )
        assertTrue(applications.isEmpty())

        val result =
            fixture.runQuery(
                """
                query {
                  rootKind: __typename
                  viewer {
                    __typename
                    kind: __typename
                    ...ViewerKind
                  }
                  item {
                    __typename
                    ... on A {
                      concreteKind: __typename
                    }
                  }
                  choice {
                    __typename
                  }
                }

                fragment ViewerKind on User {
                  namedKind: __typename
                }
                """.trimIndent(),
                resolutionObserver = observer,
            )

        result.assertResult(
            mapOf(
                "rootKind" to "Query",
                "viewer" to
                    mapOf(
                        "__typename" to "User",
                        "kind" to "User",
                        "namedKind" to "User",
                    ),
                "item" to
                    mapOf(
                        "__typename" to "A",
                        "concreteKind" to "A",
                    ),
                "choice" to mapOf("__typename" to "B"),
            ),
        )
        assertTrue(applications.none { it.endsWith(".V_A_typename") })
    }

    @Test
    fun `specializes shared list continuation and concrete argument defaults`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      items: [Item!]!
                        @resolver(
                          script: "[{'__typename': 'A'}, {'__typename': 'B'}]"
                        )
                    }

                    interface Item {
                      computed: Int!
                    }

                    type A implements Item {
                      computed(factor: Int = 2): Int!
                        @resolver(script: "factor")
                    }

                    type B implements Item {
                      computed: Int! @resolver(script: "3")
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { items { computed } }")

        result.assertResult(
            mapOf(
                "items" to
                    listOf(
                        mapOf("computed" to 2),
                        mapOf("computed" to 3),
                    ),
            ),
        )
    }

    @Test
    fun `resolves an empty query through field and node resolvers`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      viewer(id: ID!): User!
                        @resolver(script: "{'id': id}")
                    }

                    type User implements Node
                      @nodeResolver(ids: ["1"], script: "{'name': 7}") {
                      id: ID!
                      name: Int!
                      greeting(prefix: Int!): Int!
                        @resolver(script: "1 + prefix")
                    }
                    """.trimIndent(),
            )

        val result =
            fixture.runQuery(
                """
                query {
                  viewer(id: "1") {
                    id
                    name
                    greeting(prefix: 5)
                  }
                }
                """.trimIndent(),
            )

        result.assertResult(
            mapOf(
                "viewer" to
                    mapOf(
                        "id" to "1",
                        "name" to 7,
                        "greeting" to 6,
                    ),
            ),
        )
    }

    @Test
    fun `materializes argumentless and argument-bearing aliases by response key`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      viewer: User! @resolver(script: "{'plain': 5}")
                    }

                    type User {
                      plain: Int!
                      scaled(factor: Int!): Int!
                        @resolver(script: "factor")
                      total: Int!
                        @resolver(
                          of: "plainValue: plain byTwo: scaled(factor: 2) byThree: scaled(factor: 3)"
                          script: "(objectValue.plainValue == null ? 0 : objectValue.plainValue) + (objectValue.byTwo == null ? 0 : objectValue.byTwo) + (objectValue.byThree == null ? 0 : objectValue.byThree)"
                        )
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { viewer { total } }")

        result.assertResult(
            mapOf("viewer" to mapOf("total" to 10)),
        )
    }

    @Test
    fun `collects one alias across non-overlapping concrete types`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      holders: [Holder!]!
                        @resolver(
                          script: "[{'item': {'__typename': 'Alpha', 'alpha': 4}}, {'item': {'__typename': 'Beta', 'beta': 7}}]"
                        )
                    }

                    type Holder {
                      item: Choice!
                      chosen: Int!
                        @resolver(
                          of: "item { ... on Alpha { value: alpha } ... on Beta { value: beta } }"
                          script: "(objectValue.item == null ? null : objectValue.item.value)"
                        )
                    }

                    union Choice = Alpha | Beta

                    type Alpha {
                      alpha: Int!
                    }

                    type Beta {
                      beta: Int!
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { holders { chosen } }")

        result.assertResult(
            mapOf(
                "holders" to
                    listOf(
                        mapOf("chosen" to 4),
                        mapOf("chosen" to 7),
                    ),
            ),
        )
    }

    @Test
    fun `resolves input selected with a fromArgument variable`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!): Int!
                        @resolver(
                          of: "consume(value: ${'$'}seed)"
                          script: "(objectValue.consume == null ? 0 : objectValue.consume)"
                        )
                      consume(value: Int!): Int!
                        @resolver(script: "value + value")
                    }
                    """.trimIndent(),
            )

        val result =
            fixture.runQuery(
                """
                query Resolve(${'$'}first: Int!, ${'$'}second: Int!) {
                  first: result(seed: ${'$'}first)
                  second: result(seed: ${'$'}second)
                }
                """.trimIndent(),
                variables = mapOf("first" to 7, "second" to 8),
            )

        result.assertResult(
            mapOf(
                "first" to 14,
                "second" to 16,
            ),
        )
    }

    @Test
    fun `retains passive demand below an ungrounded nested resolver key`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      holder: Item! @resolver(script: "{'passive': 7}")
                      result(value: Int!): Int!
                        @resolver(
                          of: "holder { consume(value: ${'$'}value) }"
                          script: "(objectValue.holder == null ? 0 : objectValue.holder.consume == null ? 0 : objectValue.holder.consume)"
                        )
                    }

                    type Item {
                      consume(value: Int!): Int!
                        @resolver(of: "passive", script: "(objectValue.passive == null ? 0 : objectValue.passive)")
                      passive: Int!
                    }
                    """.trimIndent(),
            )

        val result =
            fixture.runQuery(
                """
                query {
                  holder { __typename }
                  result(value: 7)
                }
                """.trimIndent(),
            )

        result.assertResult(
            mapOf(
                "holder" to mapOf("__typename" to "Item"),
                "result" to 7,
            ),
        )
    }

    @Test
    fun `waits for a provider value before expanding a nested variable use`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result: Int!
                        @resolver(
                          of: "source holder { consume(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          script: "(objectValue.holder == null ? 0 : objectValue.holder.consume == null ? 0 : objectValue.holder.consume)"
                        )
                      source: Int! @resolver(of: "delay", script: "(objectValue.delay == null ? 0 : objectValue.delay)")
                      holder: Holder! @resolver(script: "{:}")
                      delay: Int! @resolver(script: "7")
                    }

                    type Holder {
                      consume(value: Int!): Int!
                        @resolver(script: "value")
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { result }")

        result.assertResult(mapOf("result" to 7))
    }

    @Test
    fun `applies the configured identity policy after variable selections ground equally`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      result(seed: Int!, other: Int!): Int!
                        @resolver(
                          of: "ground: payload(arg: 1) { one } seedValue: payload(arg: ${'$'}seed) { two } seedValue: payload(arg: ${'$'}seed) { two } otherValue: payload(arg: ${'$'}other) { one }"
                          script: "(objectValue.ground == null ? 0 : objectValue.ground.one == null ? 0 : objectValue.ground.one) + (objectValue.seedValue == null ? 0 : objectValue.seedValue.two == null ? 0 : objectValue.seedValue.two)"
                        )
                      payload(arg: Int!): Payload!
                        @resolver(script: "{'one': 3, 'two': 5}")
                    }

                    type Payload {
                      one: Int!
                      two: Int!
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { result(seed: 1, other: 1) }")

        result.assertResult(mapOf("result" to 8))
    }

    @Test
    fun `list null and error elements preserve position and skip descendants`() {
        val fixture =
            fixtureFromResolverDSL(
                resolverSchemaSDL =
                    """
                    extend type Query {
                      items: [Item]
                        @resolver(script: "[null, error, {'seed': 3}]")
                    }

                    type Item {
                      seed: Int!
                      computed: Int!
                        @resolver(of: "seed", script: "(objectValue.seed == null ? 0 : objectValue.seed) + (objectValue.seed == null ? 0 : objectValue.seed)")
                    }
                    """.trimIndent(),
            )

        val result = fixture.runQuery("query { items { computed } }")

        result.assertResult(
            mapOf(
                "items" to
                    listOf(
                        null,
                        null,
                        mapOf("computed" to 6),
                    ),
            ),
            listOf("items", 1),
        )
        assertTrue(result.errors.single().message.contains("Runtime2 field resolution failed"))
    }
}
