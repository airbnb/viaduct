@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.execution.MergedField
import graphql.execution.ResultPath
import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.Directive as GJDirective
import graphql.language.Field as GJField
import graphql.language.FragmentDefinition as GJFragmentDefinition
import graphql.language.Node
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.SourceLocation
import graphql.language.TypeName as GJTypeName
import graphql.language.VariableReference
import graphql.schema.GraphQLNamedOutputType
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.arbitrary.graphql.asDocument
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.EngineExecutionContext
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.RequiredSelectionSet
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.mocks.createRSS
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.QueryPlanExecutionCondition
import viaduct.engine.runtime.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.runtime.RequiredSelectionSetRegistry
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.execution.QueryPlan.CollectedField
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.FragmentDefinition
import viaduct.engine.runtime.execution.QueryPlan.FragmentSpread
import viaduct.engine.runtime.execution.QueryPlan.Fragments
import viaduct.engine.runtime.execution.QueryPlan.InlineFragment
import viaduct.engine.runtime.execution.QueryPlan.Selection
import viaduct.engine.runtime.execution.QueryPlan.SelectionSet
import viaduct.engine.runtime.execution.QueryPlan.SelectionVariableReference
import viaduct.engine.runtime.execution.QueryPlan.SelectionVariableReference.Kind
import viaduct.engine.runtime.execution.constraints.Constraints
import viaduct.graphql.utils.ParsedSelections

class QueryPlanTest {
    @Test
    fun `scalar field`() {
        Fixture("type Query { x:Int }") {
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField("x", typeConstraint(query))
                    ),
                    parentType = query
                ),
                buildPlan("{x}"),
            )
        }
    }

    @Test
    fun `field -- with directives`() {
        val skipDir = GJDirective.newDirective()
            .name("skip")
            .argument(Argument("if", VariableReference.of("var")))
            .build()

        Fixture("type Query { x:Int }") {
            val plan = buildPlan("{x @skip(if:\$var) }")
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            "x",
                            Constraints(listOf(skipDir), possibleTypes = setOf(query)),
                            GJField.newField("x").directive(skipDir).build()
                        )
                    ),
                    parentType = query
                ),
                plan,
            )
            assertEquals(listOf(conditionalDirectiveReference("var")), (plan.selectionSet.selections.single() as Field).variableReferences)
            assertEquals(listOf("var"), plan.variableDefinitions.map { it.name })
        }
    }

    @Test
    fun `field with subselections`() {
        Fixture("type Query { q:Query }") {
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            "q",
                            Constraints(emptyList(), possibleTypes = setOf(query)),
                            GJField(
                                "q",
                                GJSelectionSet(listOf(GJField("__typename")))
                            ),
                            SelectionSet(
                                mkField("__typename", typeConstraint(query))
                            )
                        )
                    ),
                    parentType = query
                ),
                buildPlan("{ q { __typename } }"),
            )
        }
    }

    @Test
    fun `inline fragment`() {
        Fixture("type Query { x:Int }") {
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        InlineFragment(
                            SelectionSet(
                                mkField("x", typeConstraint(query))
                            ),
                            Constraints(emptyList(), possibleTypes = setOf(query)),
                        )
                    ),
                    parentType = query
                ),
                buildPlan("{ ... { x } }"),
            )
        }
    }

    @Test
    fun `fragment spread`() {
        Fixture("type Query { x:Int }") {
            val plan = buildPlan(
                """
                    { ... F }
                    fragment F on Query { x }
                """.trimIndent()
            )
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        FragmentSpread("F", Constraints(emptyList(), possibleTypes = setOf(query))),
                    ),
                    fragments = Fragments(
                        mapOf(
                            "F" to FragmentDefinition(
                                SelectionSet(
                                    mkField("x", typeConstraint(query))
                                ),
                                GJFragmentDefinition.newFragmentDefinition()
                                    .name("F")
                                    .typeCondition(GJTypeName("Query"))
                                    .selectionSet(
                                        GJSelectionSet(
                                            listOf(GJField("x"))
                                        )
                                    )
                                    .build(),
                                emptyList(),
                                index = QueryPlanIndex.empty(),
                            )
                        )
                    ),
                    parentType = query
                ),
                plan,
            )
        }
    }

    @Test
    fun `QueryPlanBuilder -- stores collection variable facts for fragment spreads`() {
        Fixture("type Query { x:Int }") {
            val plan = buildPlan(
                """
                    query(${'$'}spread: Boolean!, ${'$'}field: Boolean!) { ... F @skip(if: ${'$'}spread) }
                    fragment F on Query { x @include(if: ${'$'}field) }
                """.trimIndent()
            )

            assertEquals(listOf(conditionalDirectiveReference("spread")), (plan.selectionSet.selections.single() as FragmentSpread).variableReferences)
            assertEquals(listOf(conditionalDirectiveReference("field")), (plan.fragments.getValue("F").selectionSet.selections.single() as Field).variableReferences)
        }
    }

    @Test
    fun `QueryPlanBuilder -- stores variable facts for fragment definitions`() {
        Fixture(
            """
                directive @definitionDirective(arg: Boolean) on FRAGMENT_DEFINITION
                type Query { x:Int }
            """.trimIndent()
        ) {
            val plan = buildPlan(
                """
                    query(${'$'}definition: Boolean!) { ... F }
                    fragment F on Query @definitionDirective(arg: ${'$'}definition) { x }
                """.trimIndent()
            )

            assertEquals(listOf(directiveReference("definition")), plan.fragments.getValue("F").variableReferences)
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for field required selection sets`() {
        Fixture(
            "type Query { x:Int, y:Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Query" to "x", "y")
                .build()
        ) {
            val plan = buildPlan("{x}")
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            "x",
                            typeConstraint(query),
                            childPlans = mkFieldChildPlans("Query", "x", buildPlan("{y}")),
                        )
                    ),
                    parentType = query
                ),
                plan,
            )
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for concrete fields inside inline fragments`() {
        Fixture(
            """
                type Query { entity: Entity }
                interface Entity { id: ID! }
                type User implements Entity { id: ID!, restricted: String }
                type Admin implements Entity { id: ID!, restricted: String }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("User" to "restricted", "id")
                .fieldResolverEntry("Admin" to "restricted", "id")
                .build()
        ) {
            val plan = buildPlan(
                """
                    {
                        entity {
                            ... on User { restricted }
                            ... on Admin { restricted }
                        }
                    }
                """.trimIndent()
            )

            val entityField = plan.selectionSet.selections.single() as Field
            val entitySelections = entityField.selectionSet!!

            val userRestricted = (entitySelections.selections[0] as InlineFragment)
                .selectionSet
                .selections
                .single() as Field
            val adminRestricted = (entitySelections.selections[1] as InlineFragment)
                .selectionSet
                .selections
                .single() as Field

            userRestricted.childPlans.shouldHaveSize(1)
            assertEquals(schema.getObjectType("User"), plan.planFor(userRestricted.childPlans.single()).parentType)
            adminRestricted.childPlans.shouldHaveSize(1)
            assertEquals(schema.getObjectType("Admin"), plan.planFor(adminRestricted.childPlans.single()).parentType)
        }
    }

    @Test
    fun `QueryPlanBuilder -- field child plans carry origin coordinate for resolver and checker RSS`() {
        // For a field T.f with both a field-resolver RSS and a field-checker RSS registered,
        // both produced FieldChildPlan entries should carry originCoordinate == (T, f).
        // The resolver RSS selects {y}, the checker RSS selects {z} — assert both shapes are
        // present so we'd catch a regression where one of the two RSSes was elided or replaced.
        Fixture(
            "type Query { x:Int, y:Int, z:Int }",
            MockRequiredSelectionSetRegistry.builder()
                .fieldResolverEntry("Query" to "x", "y")
                .fieldCheckerEntry("Query" to "x", "z")
                .build()
        ) {
            val plan = buildPlan("{x}")
            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            xField.childPlans.shouldHaveSize(2)
            xField.childPlans.forEach { fcp ->
                assertEquals("Query" to "x", fcp.originCoordinate)
            }
            // The two RSS plans must contain both {y} (resolver) and {z} (checker) — exact set.
            val rssSelectedFieldNames = xField.childPlans
                .map { fcp ->
                    plan.planFor(fcp).selectionSet.selections.filterIsInstance<Field>().single().resultKey
                }
                .toSet()
            assertEquals(setOf("y", "z"), rssSelectedFieldNames)
        }
    }

    @Test
    fun `QueryPlanBuilder -- interface field child plans pin origin to each implementor`() {
        // For a field selected on an interface, RSS plans are expanded across each concrete
        // implementor. Each FieldChildPlan's originCoordinate must be pinned to its own implementor,
        // so that CollectFields can later drop sibling-implementor plans at runtime.
        Fixture(
            """
                interface Node { id:Int }
                type ImplA implements Node { id:Int a:Int }
                type ImplB implements Node { id:Int b:Int }
                type Query { node:Node }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .fieldCheckerEntry("ImplA" to "id", "a")
                .fieldCheckerEntry("ImplB" to "id", "b")
                .build()
        ) {
            val plan = buildPlan("{node{id}}")
            val nodeField = plan.selectionSet.selections.filterIsInstance<Field>().single { it.resultKey == "node" }
            val idField = nodeField.selectionSet!!.selections.filterIsInstance<Field>().single { it.resultKey == "id" }
            // Both implementor RSSes attach to the interface field selection
            idField.childPlans.shouldHaveSize(2)
            val origins = idField.childPlans.map { it.originCoordinate }.toSet()
            assertEquals(setOf("ImplA" to "id", "ImplB" to "id"), origins)
        }
    }

    @Test
    fun `QueryPlanFactory -- required selection sets prune impossible sibling implementation branches`() {
        val registry = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry(
                "Foo" to "x",
                """
                    ... on Shared {
                        ... on Bar {
                            y
                        }
                    }
                """.trimIndent()
            )
            .fieldResolverEntry("Bar" to "y", "z")
            .build()

        Fixture(
            """
                type Query { foo: Foo }
                interface Shared { x: Int }
                type Foo implements Shared { x: Int }
                type Bar implements Shared { x: Int, y: Int, z: Int }
            """.trimIndent(),
            registry
        ) {
            val fooXRss = registry.getFieldResolverRequiredSelectionSets("Foo", "x").single()

            val rssPlan = runExecutionTest {
                QueryPlanFactory.Default.buildFromRequiredSelectionSet(
                    mkQPParameters("", ViaductSchema(schema), registry),
                    fooXRss
                )
            }

            // The RSS is rooted at Foo. Foo and Bar are sibling implementations of Shared, so the
            // nested `... on Bar` branch cannot execute and must not contribute Bar.y's resolver
            // dependency to the query plan.
            assertNull(rssPlan.selectionSet.findField("y"))
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for variables with required selection sets`() {
        val varResolvers = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Query", "z"),
            ParsedSelections.empty("Query"),
            listOf(
                FromObjectFieldVariable("vara", "z")
            ),
            forChecker = false,
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry("Query" to "x", "y(a:\$vara)", varResolvers)
            .build()
        Fixture("type Query { x:Int, y(a:Int):Int, z:Int }", reg) {
            val plan = buildPlan("{x}")
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            "x",
                            typeConstraint(query),
                            childPlans = mkFieldChildPlans(
                                "Query",
                                "x",
                                mkQueryPlan(
                                    SelectionSet(
                                        mkField(
                                            "y",
                                            typeConstraint(query),
                                            GJField(
                                                "y",
                                                listOf(
                                                    Argument("a", VariableReference("vara"))
                                                )
                                            )
                                        )
                                    ),
                                    variablesResolvers = varResolvers,
                                    parentType = query,
                                    childPlans = listOf(
                                        buildPlan("{z}")
                                    )
                                )
                            )
                        )
                    ),
                    parentType = query
                ),
                plan,
            )
            val yField = (plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single()).selectionSet.selections.single() as Field)
            assertEquals(listOf(fieldArgumentReference("vara")), yField.variableReferences)
            val childPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())
            assertEquals(listOf("vara"), childPlan.variableDefinitions.map { it.name })
        }
    }

    @Test
    fun `QueryPlanBuilder -- prunes variables from statically dropped fields`() {
        val varResolver = CountingRequiredSelectionSetResolver(
            "skipY",
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "z"),
                emptyList(),
                forChecker = false,
            )
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry(
                "Query" to "x",
                "y @include(if: false) @skip(if: ${'$'}skipY)",
                listOf(varResolver)
            )
            .build()

        Fixture("type Query { x:Int, y:Int, z:Boolean }", reg) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertEquals(emptyList<String>(), rssPlan.variableDefinitions.map { it.name })
            rssPlan.variablesResolvers.shouldHaveSize(0)
            rssPlan.childPlanIds.shouldHaveSize(0)
            assertEquals(0, varResolver.requiredSelectionSetReads)
        }
    }

    @Test
    fun `QueryPlanBuilder -- keeps variables from active conditional directives`() {
        val varResolver = CountingRequiredSelectionSetResolver(
            "skipY",
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "z"),
                emptyList(),
                forChecker = false,
            )
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry(
                "Query" to "x",
                "y @skip(if: ${'$'}skipY)",
                listOf(varResolver)
            )
            .build()

        Fixture("type Query { x:Int, y:Int, z:Boolean }", reg) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertEquals(listOf("skipY"), rssPlan.variableDefinitions.map { it.name })
            assertEquals(listOf(varResolver), rssPlan.variablesResolvers)
            rssPlan.childPlanIds.shouldHaveSize(1)
            assertEquals(1, varResolver.requiredSelectionSetReads)
        }
    }

    @Test
    fun `QueryPlanBuilder -- keeps variables from active field arguments`() {
        val varResolver = CountingRequiredSelectionSetResolver(
            "value",
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "z"),
                emptyList(),
                forChecker = false,
            )
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry(
                "Query" to "x",
                "y(arg: ${'$'}value)",
                listOf(varResolver)
            )
            .build()

        Fixture("type Query { x:Int, y(arg:Int):Int, z:Int }", reg) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertEquals(listOf("value"), rssPlan.variableDefinitions.map { it.name })
            assertEquals(listOf(varResolver), rssPlan.variablesResolvers)
            rssPlan.childPlanIds.shouldHaveSize(1)
            assertEquals(1, varResolver.requiredSelectionSetReads)
        }
    }

    @Test
    fun `QueryPlanBuilder -- prunes statically skippable fragments`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry(
                "Query" to "x",
                """
                    fragment Main on Query { ...Frag @skip(if: true) }
                    fragment Frag on Query { __typename }
                """.trimIndent(),
            )
            .build()

        Fixture("type Query { x:Int }", reg) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertTrue(rssPlan.fragments.isEmpty())
        }
    }

    @Test
    fun `QueryPlanBuilder -- prunes variables from statically skipped fragment spreads`() {
        val varResolver = CountingRequiredSelectionSetResolver(
            "value",
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "z"),
                emptyList(),
                forChecker = false,
            )
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry(
                "Query" to "x",
                """
                    fragment Main on Query {
                      ...Values @skip(if: true)
                    }

                    fragment Values on Query {
                      y(arg: ${'$'}value)
                    }
                """.trimIndent(),
                listOf(varResolver)
            )
            .build()

        Fixture("type Query { x:Int, y(arg:Int):Int, z:Int }", reg) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertEquals(emptyList<String>(), rssPlan.variableDefinitions.map { it.name })
            rssPlan.variablesResolvers.shouldHaveSize(0)
            rssPlan.childPlanIds.shouldHaveSize(0)
            assertEquals(0, rssPlan.fragments.map.size)
            assertEquals(0, varResolver.requiredSelectionSetReads)
        }
    }

    @Test
    fun `QueryPlanBuilder -- propagates variable child plans from nested field selections without rescanning parent field`() {
        val varResolver = CountingRequiredSelectionSetResolver(
            "vara",
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "z"),
                emptyList(),
                forChecker = false,
            )
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                "container { y(a:\$vara) }",
                listOf(varResolver)
            )
            .build()

        Fixture(
            """
                type Query { x:Int, container:Container, z:Int }
                type Container { y(a:Int):Int }
            """.trimIndent(),
            reg
        ) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertEquals("z", (rssPlan.eagerChildPlans().single().selectionSet.selections.single() as Field).resultKey)
            assertEquals(1, varResolver.requiredSelectionSetReads)
        }
    }

    @Test
    fun `QueryPlanBuilder -- propagates variable child plans from nested inline fragments without rescanning parent fragment`() {
        val varResolver = CountingRequiredSelectionSetResolver(
            "vara",
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "z"),
                emptyList(),
                forChecker = false,
            )
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                "container { ... { y(a:\$vara) } }",
                listOf(varResolver)
            )
            .build()

        Fixture(
            """
                type Query { x:Int, container:Container, z:Int }
                type Container { y(a:Int):Int }
            """.trimIndent(),
            reg
        ) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertEquals("z", (rssPlan.eagerChildPlans().single().selectionSet.selections.single() as Field).resultKey)
            assertEquals(1, varResolver.requiredSelectionSetReads)
        }
    }

    @Test
    fun `QueryPlanBuilder -- propagates variable child plans from nested fragment spreads`() {
        val varResolver = CountingRequiredSelectionSetResolver(
            "vara",
            RequiredSelectionSet(
                SelectionsParser.parse("Query", "z"),
                emptyList(),
                forChecker = false,
            )
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                """
                    fragment Main on Query {
                      container { ...ContainerFields }
                    }
                    fragment ContainerFields on Container {
                      y(a: ${'$'}vara)
                    }
                """.trimIndent(),
                listOf(varResolver)
            )
            .build()

        Fixture(
            """
                type Query { x:Int, container:Container, z:Int }
                type Container { y(a:Int):Int }
            """.trimIndent(),
            reg
        ) {
            val plan = buildPlan("{x}")
            val rssPlan = plan.planFor((plan.selectionSet.selections.single() as Field).childPlans.single())

            assertEquals("z", (rssPlan.eagerChildPlans().single().selectionSet.selections.single() as Field).resultKey)
            assertEquals(1, varResolver.requiredSelectionSetReads)
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for variables with required selection sets for inline fragments`() {
        val varResolvers = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Query", "z"),
            ParsedSelections.empty("Query"),
            listOf(
                FromObjectFieldVariable("vara", "z")
            ),
            forChecker = false,
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                "fragment Main on Query { ... { y(a:\$vara) } }",
                varResolvers
            ).build()

        Fixture("type Query { x:Int, y(a:Int):Int, z:Int }", reg) {
            val plan = buildPlan("{x}")

            val xField = plan.selectionSet.selections.filterIsInstance<Field>().single()
            // field x should have one child plan for its RSS
            xField.childPlans.shouldHaveSize(1)
            val rss = xField.childPlans.single()
            // child plan's origin coordinate is Query.x
            assertEquals("Query" to "x", rss.originCoordinate)
            val rssPlan = plan.planFor(rss)
            // RSS plan should have its variables-plan child
            rssPlan.childPlanIds.shouldHaveSize(1)
            val variablesPlan = rssPlan.eagerChildPlans().single()
            // variables plan should select field z
            val innerField = variablesPlan.selectionSet.selections.filterIsInstance<Field>().single()
            assertEquals("z", innerField.resultKey)
            // RSS plan should have variables resolver for "vara"
            assertEquals("vara", rssPlan.variablesResolvers.single().variableNames.single())
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for inline fragments with selection-based variables`() {
        val varResolvers = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Query", "z"),
            ParsedSelections.empty("Query"),
            listOf(
                FromObjectFieldVariable("z", "z")
            ),
            forChecker = false,
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                "fragment Main on Query { ... @include(if:\$z) { y } }",
                varResolvers
            ).build()

        Fixture("type Query { x:Int, y:Int, z:Boolean }", reg) {
            val plan = buildPlan("{x}")
            val fieldX = plan.selectionSet.selections.single() as Field

            // field x should have one child plan for its RSS
            fieldX.childPlans.shouldHaveSize(1)
            val rss = fieldX.childPlans.single()
            // the child plan's origin coordinate is Query.x
            assertEquals("Query" to "x", rss.originCoordinate)
            val rssPlan = plan.planFor(rss)

            // the rss plan should include a plan for its variables, selecting 'z'
            rssPlan.childPlanIds.shouldHaveSize(1)
            val variablesPlan = rssPlan.eagerChildPlans().single()
            val innerField = variablesPlan.selectionSet.selections.single() as Field
            assertEquals("z", innerField.resultKey)
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for variables with required selection sets for fragment spread`() {
        val varResolvers = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Query", "z"),
            ParsedSelections.empty("Query"),
            listOf(
                FromObjectFieldVariable("vara", "z")
            ),
            forChecker = false,
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                // checker rss as fragment spread with variable
                "fragment Main on Query { ...T }  fragment T on Query { y(a:\$vara) }",
                varResolvers
            ).build()
        Fixture("type Query { x:Int, y(a:Int):Int, z:Int }", reg) {
            val plan = buildPlan("{x}")
            // plan should have a single field selection
            val fieldX = plan.selectionSet.selections.single() as Field
            assertEquals("x", fieldX.resultKey)

            // the field should have a single child plan whose origin is Query.x
            fieldX.childPlans.shouldHaveSize(1)
            val rss = fieldX.childPlans.single()
            assertEquals("Query" to "x", rss.originCoordinate)

            // the child plan should have a fragment spread on T
            val rssPlan = plan.planFor(rss)
            val spread = rssPlan.selectionSet.selections.single() as FragmentSpread
            assertEquals("T", spread.name)

            // fragment T should be in the child plan's fragment map and contain field 'y'
            val tFrag = rssPlan.fragments["T"]
            checkNotNull(tFrag)
            assertEquals("y", (tFrag.selectionSet.selections.single() as Field).resultKey)

            // the child plan should have a variable resolver for variable "vara"
            assertEquals("vara", rssPlan.variablesResolvers.single().variableNames.single())

            // the child plan should have its own child plan for vara, selecting 'z'
            rssPlan.childPlanIds.shouldHaveSize(1)
            val varaPlan = rssPlan.eagerChildPlans().single()
            assertEquals("z", (varaPlan.selectionSet.selections.single() as Field).resultKey)
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for fragment spreads with selection-based variables`() {
        val varResolvers = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Query", "z"),
            ParsedSelections.empty("Query"),
            listOf(
                FromObjectFieldVariable("z", "z")
            ),
            forChecker = false,
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                "fragment Main on Query { ...T @include(if:\$z) }  fragment T on Query { y }",
                varResolvers
            ).build()

        Fixture("type Query { x:Int, y:Int, z:Boolean }", reg) {
            val plan = buildPlan("{x}")
            // plan should contain a single field 'x'
            val fieldX = plan.selectionSet.selections.single() as Field
            assertEquals("x", fieldX.resultKey)

            // the field has a single child plan whose origin is Query.x
            fieldX.childPlans.shouldHaveSize(1)
            val rss = fieldX.childPlans.single()
            assertEquals("Query" to "x", rss.originCoordinate)
            val rssPlan = plan.planFor(rss)

            // the child plan contains a single fragment spread on "T"
            val spread = rssPlan.selectionSet.selections.single() as FragmentSpread
            assertEquals("T", spread.name)

            // the child plan should have a variables resolver for 'z'
            assertEquals("z", rssPlan.variablesResolvers.single().variableNames.single())

            // the child plan should contain a variables plan that selects 'z'
            rssPlan.childPlanIds.shouldHaveSize(1)
            val varaPlan = rssPlan.eagerChildPlans().single()
            assertEquals("z", (varaPlan.selectionSet.selections.single() as Field).resultKey)
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds child plans for field type required selection sets`() {
        Fixture(
            """
                type Query { x:ObjectX }
                type ObjectX { y:Int z:Int }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("ObjectX", "z")
                .build()
        ) {
            val objectX = schema.getObjectType("ObjectX")!!

            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            resultKey = "x",
                            constraints = typeConstraint(query),
                            field = GJField(
                                "x",
                                GJSelectionSet(
                                    listOf(GJField("y"))
                                )
                            ),
                            selectionSet = SelectionSet(
                                mkField("y", typeConstraint(objectX))
                            ),
                            childPlans = emptyList(),
                            fieldTypeChildPlans = mapOf(
                                objectX to listOf(
                                    mkQueryPlan(
                                        SelectionSet(
                                            mkField("z", typeConstraint(objectX))
                                        ),
                                        parentType = objectX
                                    )
                                )
                            )
                        )
                    ),
                    parentType = query
                ),
                buildPlan("{x{y}}"),
            )
        }
    }

    @Test
    fun `QueryPlanBuilder -- builds field type child plans for all possible implementers of interface from schema`() {
        Fixture(
            """
                type Query {
                    node:Node
                }
                interface Node {
                    id:Int
                    y:Int
                }
                type ObjectX implements Node {
                    id:Int
                    y:Int
                }
                type ObjectY implements Node {
                    id:Int
                    y:Int,
                    z:Int
                }
            """.trimIndent(),
            MockRequiredSelectionSetRegistry.builder()
                .typeCheckerEntry("ObjectX", "id")
                .typeCheckerEntry("ObjectY", "z")
                .build()
        ) {
            val objectX = schema.getObjectType("ObjectX")!!
            val objectY = schema.getObjectType("ObjectY")!!

            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            resultKey = "node",
                            constraints = Constraints(emptyList(), listOf(query)),
                            field = GJField(
                                "node",
                                GJSelectionSet(
                                    listOf(GJField("y"))
                                )
                            ),
                            selectionSet = SelectionSet(
                                mkField(
                                    "y",
                                    Constraints(emptyList(), listOf(objectX, objectY))
                                )
                            ),
                            childPlans = emptyList(),
                            fieldTypeChildPlans = mapOf(
                                objectX to listOf(
                                    mkQueryPlan(
                                        SelectionSet(
                                            mkField("id", typeConstraint(objectX))
                                        ),
                                        parentType = objectX
                                    )
                                ),
                                objectY to listOf(
                                    mkQueryPlan(
                                        SelectionSet(
                                            mkField("z", typeConstraint(objectY))
                                        ),
                                        parentType = objectY
                                    )
                                ),
                            )
                        )
                    ),
                    parentType = query
                ),
                buildPlan("{node{y}}"),
            )
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- caches plan within same instance`() {
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val params = mkQPParameters("{__typename}", schema)
        runExecutionTest {
            val plan1 = factory.build(params, "{__typename}".asDocument)
            val plan2 = factory.build(params, "{__typename}".asDocument)
            assertSame(plan2, plan1)
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- maximumSize bounds document plan cache`() {
        val factory = QueryPlanFactory.Cached(maximumSize = 1)
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val firstQuery = "{a:x}"
        val secondQuery = "{b:x}"
        val firstParams = mkQPParameters(firstQuery, schema)
        val secondParams = mkQPParameters(secondQuery, schema)

        runExecutionTest {
            factory.build(firstParams, firstQuery.asDocument)
            factory.build(secondParams, secondQuery.asDocument)
            val cacheStats = factory.cacheStats

            assertEquals(1L, cacheStats.evictionCount())
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- emits cache metrics for document plans`() {
        val meterRegistry = SimpleMeterRegistry()
        val factory = QueryPlanFactory.Cached(meterRegistry)
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val query = "{x}"
        val params = mkQPParameters(query, schema)
        val document = query.asDocument

        runExecutionTest {
            factory.build(params, document)
            factory.build(params, document)

            assertEquals(
                1.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_REQUESTS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "miss",
                ),
            )
            assertEquals(
                1.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_REQUESTS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "hit",
                ),
            )
            assertEquals(
                1.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_LOADS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "success",
                ),
            )
            assertEquals(
                0.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_LOADS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "failure",
                ),
            )
            assertEquals(
                1L,
                meterRegistry.timerCount(
                    QueryPlanFactoryStats.CACHE_LOAD_DURATION_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "success",
                ),
            )
            assertEquals(1.0, meterRegistry.gaugeValue(QueryPlanFactoryStats.CACHE_SIZE_METRIC_NAME))
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- emits cache metrics for parsed selection plans`() {
        val meterRegistry = SimpleMeterRegistry()
        val factory = QueryPlanFactory.Cached(meterRegistry)
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val params = mkQPParameters("{x}", schema)
        val parsedSelections = SelectionsParser.parse("Query", "x")

        runExecutionTest {
            factory.buildFromParsedSelections(params, parsedSelections)
            factory.buildFromParsedSelections(params, parsedSelections)

            assertEquals(
                1.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_REQUESTS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "miss",
                ),
            )
            assertEquals(
                1.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_REQUESTS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "hit",
                ),
            )
            assertEquals(
                1.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_LOADS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "success",
                ),
            )
            assertEquals(
                0.0,
                meterRegistry.counterCount(
                    QueryPlanFactoryStats.CACHE_LOADS_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "failure",
                ),
            )
            assertEquals(
                1L,
                meterRegistry.timerCount(
                    QueryPlanFactoryStats.CACHE_LOAD_DURATION_METRIC_NAME,
                    QueryPlanFactoryStats.RESULT_TAG_NAME,
                    "success",
                ),
            )
            assertEquals(1.0, meterRegistry.gaugeValue(QueryPlanFactoryStats.CACHE_SIZE_METRIC_NAME))
        }
    }

    @Test
    fun `QueryPlanFactory_Default -- does not cache`() {
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val params = mkQPParameters("{__typename}", schema)
        runExecutionTest {
            val plan1 = QueryPlanFactory.Default.build(params, "{__typename}".asDocument)
            val plan2 = QueryPlanFactory.Default.build(params, "{__typename}".asDocument)
            assertNotSame(plan2, plan1)
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- buildFromParsedSelections -- overlays executionCondition without invalidating cache`() {
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val params = mkQPParameters("{__typename}", schema)
        val condition1 = QueryPlanExecutionCondition { true }
        val condition2 = QueryPlanExecutionCondition { false }
        val parsedSelections = SelectionsParser.parse("Query", "__typename")
        runExecutionTest {
            val plan1 = factory.buildFromParsedSelections(params, parsedSelections, executionCondition = condition1)
            val plan2 = factory.buildFromParsedSelections(params, parsedSelections, executionCondition = condition2)
            val planAlways = factory.buildFromParsedSelections(params, parsedSelections, executionCondition = ALWAYS_EXECUTE)
            val planAlways2 = factory.buildFromParsedSelections(params, parsedSelections, executionCondition = ALWAYS_EXECUTE)

            // Each plan has the correct executionCondition overlaid
            assertSame(condition1, plan1.executionCondition)
            assertSame(condition2, plan2.executionCondition)
            assertSame(ALWAYS_EXECUTE, planAlways.executionCondition)
            // ALWAYS_EXECUTE returns the cached plan directly (no copy) — same instance
            assertSame(planAlways2, planAlways)
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- same RSS produces same QueryPlan instance within a build`() {
        // Two fields x and y both return ObjectA, which has a single type checker RSS (selects id).
        // Since the type checker RSS is one singleton shared by both fields, within one top-level plan
        // build both fields should reference the exact same child QueryPlan instance from the RSS cache.
        val reg = MockRequiredSelectionSetRegistry.builder()
            .typeCheckerEntry("ObjectA", "id")
            .build()
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema(
            """
            type Query { x:ObjectA y:ObjectA }
            type ObjectA { id:Int }
            """.trimIndent().asSchema
        )
        val params = QueryPlan.Parameters(
            query = "{x{id} y{id}}",
            schema = schema,
            registry = reg,
        )
        val objectA = schema.schema.getObjectType("ObjectA")!!
        runExecutionTest {
            val plan = factory.build(params, "{x{id} y{id}}".asDocument)

            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            val yField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "y" }
            // Each field can provide one type checker plan for ObjectA.
            val xPlans = xField.fieldTypeChildPlans.plansFor(objectA)
            val yPlans = yField.fieldTypeChildPlans.plansFor(objectA)
            xPlans.shouldHaveSize(1)
            yPlans.shouldHaveSize(1)
            // Both plans come from the same RSS singleton — must be the exact same QueryPlan instance
            assertSame(yPlans.first(), xPlans.first())
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- same RSS produces same QueryPlan instance across top-level builds`() {
        // x has a checker RSS (selects z). Built in two separate top-level plan builds.
        // The second build should reuse the RSS child plan from the global cache.
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Query" to "x", "z")
            .build()
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query { x:Int z:Int }".asSchema)
        val params = QueryPlan.Parameters(
            query = "{x}",
            schema = schema,
            registry = reg,
        )
        runExecutionTest {
            // Build with {x} — triggers a cache miss for the top-level plan
            val plan1 = factory.build(params, "{x}".asDocument)
            // Build with {x z} — different top-level plan, but checker RSS for x is same object
            val params2 = params.copy(query = "{x z}")
            val plan2 = factory.build(params2, "{x z}".asDocument)

            val xField1 = plan1.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            val xField2 = plan2.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            xField1.childPlans.shouldHaveSize(1)
            xField2.childPlans.shouldHaveSize(1)
            // Both top-level plans share the same RSS child plan instance (compare underlying QueryPlan)
            assertSame(plan2.planFor(xField2.childPlans.first()), plan1.planFor(xField1.childPlans.first()))
            // Both also carry the same origin coordinate, pinned to Query.x
            assertEquals("Query" to "x", xField1.childPlans.first().originCoordinate)
            assertEquals("Query" to "x", xField2.childPlans.first().originCoordinate)
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- equivalent RSS instances get distinct requiredSelectionSetIds`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Query" to "x", "z")
            .fieldCheckerEntry("Query" to "y", "z")
            .build()
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query { x:Int y:Int z:Int }".asSchema)
        val params = QueryPlan.Parameters(
            query = "{x y}",
            schema = schema,
            registry = reg,
        )
        val xRss = reg.getFieldCheckerRequiredSelectionSets("Query", "x").single()
        val yRss = reg.getFieldCheckerRequiredSelectionSets("Query", "y").single()

        runExecutionTest {
            val plan = factory.build(params, "{x y}".asDocument)

            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            val yField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "y" }
            val xPlan = xField.childPlans.single()
            val yPlan = yField.childPlans.single()

            assertSame(xRss.id, xPlan.requiredSelectionSetId)
            assertSame(yRss.id, yPlan.requiredSelectionSetId)
            assertNotSame(plan.planFor(yPlan), plan.planFor(xPlan))
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- cycle prevention still works with RSS cache (direct self-reference)`() {
        // Same as the Default cycle test but using the Cached factory.
        // x's checker RSS selects x itself — must not cause infinite recursion.
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Query" to "x", "x")
            .build()
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query { x:Int }".asSchema)
        val params = QueryPlan.Parameters(
            query = "{x}",
            schema = schema,
            registry = reg,
        )
        runExecutionTest {
            val plan = factory.build(params, "{x}".asDocument)
            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            // Checker RSS for x produces one child plan (the cycle is broken at that level)
            xField.childPlans.shouldHaveSize(1)
            // Origin coordinate is preserved across the RSS-cache lookup
            assertEquals("Query" to "x", xField.childPlans.first().originCoordinate)
            val checkerPlan = plan.planFor(xField.childPlans.first())
            // The checker plan keeps the self-cycle as an RSS id back-edge; runtime suppresses recursion.
            val innerX = checkerPlan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            innerX.childPlans.shouldHaveSize(1)
            assertEquals("Query" to "x", innerX.childPlans.first().originCoordinate)
            assertSame(plan.planFor(innerX.childPlans.first()), checkerPlan)
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- cycle prevention still works with RSS cache (type checker self-reference)`() {
        // ObjectX's type checker selects y (which returns ObjectX).
        // The RSS plan cache ensures each RSS is built at most once; forcing the type checker
        // lazy after the build returns the cached plan and does not recurse infinitely.
        val reg = MockRequiredSelectionSetRegistry.builder()
            .typeCheckerEntry("ObjectX", "y")
            .build()
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema(
            """
            type Query { x:ObjectX }
            type ObjectX { y:ObjectX z:Int }
            """.trimIndent().asSchema
        )
        val params = QueryPlan.Parameters(
            query = "{x{z}}",
            schema = schema,
            registry = reg,
        )
        runExecutionTest {
            // Must complete without stack overflow or infinite recursion
            val plan = factory.build(params, "{x{z}}".asDocument)
            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            val objectX = schema.schema.getObjectType("ObjectX")!!
            // ObjectX has a type checker, so x should provide child plans for ObjectX.
            val typeCheckerPlanList = xField.fieldTypeChildPlans.plansFor(objectX)
            typeCheckerPlanList.shouldHaveSize(1)
            val checkerPlan = typeCheckerPlanList.first()
            // The checker plan selects y (type ObjectX); y also provides child plans for ObjectX.
            val yField = checkerPlan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "y" }
            val yTypeCheckerPlanList = yField.fieldTypeChildPlans.plansFor(objectX)
            yTypeCheckerPlanList.shouldHaveSize(1)
            // Both lookups return the same cached RSS plan instance.
            assertSame(yTypeCheckerPlanList.first(), typeCheckerPlanList.first())
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- sub-plan RSS is not globally cached with cycle-local context`() {
        // RSS1 (checker for a) selects {b}; RSS2 (checker for b) selects {a} — mutual cycle.
        //
        // Even though RSS1 is already in the build stack when RSS2 reaches back to it, the back
        // edge should remain as an RSS id while the local cyclic materialization stays out of
        // the global RSS plan cache.
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Query" to "a", "b") // RSS1
            .fieldCheckerEntry("Query" to "b", "a") // RSS2
            .build()
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query { a: Int b: Int }".asSchema)
        val params = QueryPlan.Parameters(
            schema = schema,
            registry = reg,
        )
        runExecutionTest {
            // Build {a} — RSS1 is cached globally; RSS2 is built locally within RSS1.
            val planA = factory.build(params.copy(query = "{a}"), "{a}".asDocument)
            val aField = planA.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "a" }
            aField.childPlans.shouldHaveSize(1) // RSS1 attached to a
            // RSS1 was attached to Query.a; origin is pinned regardless of the cycle.
            assertEquals("Query" to "a", aField.childPlans.first().originCoordinate)
            val rss1Plan = planA.planFor(aField.childPlans.first())
            val bInRss1 = rss1Plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "b" }
            bInRss1.childPlans.shouldHaveSize(1) // RSS2 edge embedded in RSS1
            // RSS2 was attached to Query.b inside RSS1; origin is preserved across the cycle.
            assertEquals("Query" to "b", bInRss1.childPlans.first().originCoordinate)
            val aInRss2 = planA.planFor(bInRss1.childPlans.first()).selectionSet.selections
                .filterIsInstance<Field>().first { it.resultKey == "a" }
            aInRss2.childPlans.shouldHaveSize(1)
            assertEquals("Query" to "a", aInRss2.childPlans.first().originCoordinate)
            assertSame(planA.planFor(aInRss2.childPlans.first()), rss1Plan)

            // Build {b} — RSS2 is built fresh rather than globally cached in its cycle-local form.
            val planB = factory.build(params.copy(query = "{b}"), "{b}".asDocument)
            val bField = planB.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "b" }
            bField.childPlans.shouldHaveSize(1) // RSS2 attached to b
            // Fresh build of RSS2 keeps origin pinned to Query.b
            assertEquals("Query" to "b", bField.childPlans.first().originCoordinate)
            val rss2Plan = planB.planFor(bField.childPlans.first())
            val aInRss2Fresh = rss2Plan.selectionSet.selections
                .filterIsInstance<Field>().first { it.resultKey == "a" }
            aInRss2Fresh.childPlans.shouldHaveSize(1)
            // Nested RSS1 origin is also Query.a
            assertEquals("Query" to "a", aInRss2Fresh.childPlans.first().originCoordinate)
            val bInRss1Fresh = planB.planFor(aInRss2Fresh.childPlans.first()).selectionSet.selections
                .filterIsInstance<Field>().first { it.resultKey == "b" }
            bInRss1Fresh.childPlans.shouldHaveSize(1)
            assertEquals("Query" to "b", bInRss1Fresh.childPlans.first().originCoordinate)
            assertSame(planB.planFor(bInRss1Fresh.childPlans.first()), rss2Plan)
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- buildFromParsedSelections preserves attribution per call`() {
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query { x:Int }".asSchema)
        val params = mkQPParameters("{x}", schema)
        val parsedSelections = SelectionsParser.parse("Query", "x")
        val resolver1 = ExecutionAttribution.fromResolver("ResolverOne")
        val resolver2 = ExecutionAttribution.fromResolver("ResolverTwo")

        runExecutionTest {
            val plan1 = factory.buildFromParsedSelections(
                params,
                parsedSelections,
                attribution = resolver1,
            )
            val plan2 = factory.buildFromParsedSelections(
                params,
                parsedSelections,
                attribution = resolver2,
            )

            assertEquals(resolver1, plan1.attribution)
            assertEquals(resolver2, plan2.attribution)
        }
    }

    @Test
    fun `QueryPlanBuilder -- cycle prevention in checker RSS chains`() {
        val varResolvers = VariablesResolver.fromSelectionSetVariables(
            SelectionsParser.parse("Query", "z"),
            ParsedSelections.empty("Query"),
            listOf(
                FromObjectFieldVariable("vara", "z")
            ),
            forChecker = true,
        )
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry(
                "Query" to "x",
                "y(a:\$vara)",
                varResolvers
            )
            .fieldResolverEntry("Query" to "z", "zz")
            .fieldCheckerEntry("Query" to "z", "x")
            .build()
        Fixture("type Query { x:Int, y(a:Int):Int, z:Int zz:String}", reg) {
            val plan = buildPlan("{x}")
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            "x",
                            typeConstraint(query),
                            childPlans = mkFieldChildPlans(
                                "Query",
                                "x",
                                mkQueryPlan(
                                    SelectionSet(
                                        mkField(
                                            "y",
                                            typeConstraint(query),
                                            GJField(
                                                "y",
                                                listOf(
                                                    Argument("a", VariableReference("vara"))
                                                )
                                            )
                                        )
                                    ),
                                    variablesResolvers = varResolvers,
                                    parentType = query,
                                    childPlans = listOf(
                                        mkQueryPlan(
                                            SelectionSet(
                                                mkField("z", typeConstraint(query))
                                            ),
                                            parentType = query,
                                            // Both resolver and checker RSSes for z are now included
                                            childPlans = listOf(
                                                // Resolver RSS for z: selects zz
                                                mkQueryPlan(
                                                    SelectionSet(
                                                        mkField("zz", typeConstraint(query))
                                                    ),
                                                    parentType = query
                                                ),
                                                // Checker RSS for z: selects x, but x has no child plans (cycle broken)
                                                mkQueryPlan(
                                                    SelectionSet(
                                                        mkField("x", typeConstraint(query))
                                                    ),
                                                    parentType = query
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    ),
                    parentType = query
                ),
                plan,
            )
        }
    }

    @Test
    fun `QueryPlanBuilder -- cycle prevention for direct self-reference`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Query" to "x", "x") // x's checker selects x
            .build()
        Fixture("type Query { x:Int }", reg) {
            val plan = buildPlan("{x}")
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            "x",
                            typeConstraint(query),
                            childPlans = mkFieldChildPlans(
                                "Query",
                                "x",
                                // Checker RSS for x selects x, but x has no child plans (cycle broken)
                                mkQueryPlan(
                                    SelectionSet(
                                        mkField("x", typeConstraint(query))
                                    ),
                                    parentType = query
                                )
                            )
                        )
                    ),
                    parentType = query
                ),
                plan,
            )
        }
    }

    @Test
    fun `QueryPlanBuilder -- cycle prevention for type checker self-reference`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .typeCheckerEntry("ObjectX", "y") // ObjectX's type checker selects y (which is of type ObjectX)
            .build()
        Fixture(
            """
                type Query { x:ObjectX }
                type ObjectX { y:ObjectX z:Int }
            """.trimIndent(),
            reg
        ) {
            val objectX = schema.getObjectType("ObjectX")!!
            val plan = buildPlan("{x{z}}")
            checkEquals(
                mkQueryPlan(
                    SelectionSet(
                        mkField(
                            resultKey = "x",
                            constraints = typeConstraint(query),
                            field = GJField(
                                "x",
                                GJSelectionSet(
                                    listOf(GJField("z"))
                                )
                            ),
                            selectionSet = SelectionSet(
                                mkField("z", typeConstraint(objectX))
                            ),
                            childPlans = emptyList(),
                            fieldTypeChildPlans = mapOf(
                                objectX to listOf(
                                    // Type checker for ObjectX selects y (type ObjectX)
                                    mkQueryPlan(
                                        SelectionSet(
                                            mkField(
                                                resultKey = "y",
                                                constraints = typeConstraint(objectX),
                                                field = GJField("y"),
                                                selectionSet = null,
                                                childPlans = emptyList(),
                                                // y's type is ObjectX, but ObjectX is already in seen set (cycle broken)
                                                fieldTypeChildPlans = emptyMap()
                                            )
                                        ),
                                        parentType = objectX
                                    )
                                )
                            )
                        )
                    ),
                    parentType = query
                ),
                plan,
            )
        }
    }

    @Test
    fun `QueryPlanBuilder -- interface with multiple implementing types`() {
        fun uniqueVarResolvers(id: String) =
            VariablesResolver.fromSelectionSetVariables(
                SelectionsParser.parse("I", "x"),
                ParsedSelections.empty("I"),
                listOf(FromObjectFieldVariable("var_$id", "x")),
                forChecker = true,
            )

        val reg = MockRequiredSelectionSetRegistry.builder()
            .typeCheckerEntry("A", "fragment Main on A { ...Frag1 } fragment Frag1 on I { x y z }", uniqueVarResolvers("typeA"))
            .fieldCheckerEntry("A" to "x", "fragment Main on A { ...Frag2 } fragment Frag2 on I { x y z }", uniqueVarResolvers("Ax"))
            .fieldCheckerEntry("A" to "y", "fragment Main on A { ...Frag2 } fragment Frag2 on I { x y z }", uniqueVarResolvers("Ay"))
            .fieldCheckerEntry("A" to "z", "v { v }", uniqueVarResolvers("Az"), selectionsType = "Query")
            .typeCheckerEntry("B", "fragment Main on B { ...Frag1 } fragment Frag1 on I { x y z }", uniqueVarResolvers("typeB"))
            .fieldCheckerEntry("B" to "x", "fragment Main on B { ...Frag2 } fragment Frag2 on I { x y z }", uniqueVarResolvers("Bx"))
            .fieldCheckerEntry("B" to "y", "fragment Main on B { ...Frag2 } fragment Frag2 on I { x y z }", uniqueVarResolvers("By"))
            .fieldCheckerEntry("B" to "z", "v { v }", uniqueVarResolvers("Bz"), selectionsType = "Query")
            .build()

        Fixture(
            """
                interface I { x: Int, y: Int, z: Int }
                type A implements I { x: Int, y: Int, z: Int }
                type B implements I { x: Int, y: Int, z: Int}

                type V { v: String }
                type Query { i: I, v: V }
            """.trimIndent(),
            reg
        ) {
            val plan = buildPlan("{ i { x } }")
            plan.selectionSet.selections.shouldHaveSize(1)
        }
    }

    @Test
    fun `QueryPlan can be built with custom ExecutionCondition`() {
        Fixture("type Query { x:Int }") {
            val customCondition = QueryPlanExecutionCondition { false }
            val plan = runExecutionTest {
                val params = mkQPParameters("{x}", ViaductSchema(schema), requiredSelectionSetRegistry)
                    .copy(executionCondition = customCondition)
                QueryPlanFactory.Default.build(params, "{x}".asDocument)
            }

            // Verify the ExecutionCondition is stored in the plan
            assertEquals(customCondition, plan.executionCondition)
            assertEquals(false, plan.executionCondition.shouldExecute(null))
        }
    }

    @Test
    fun `Child QueryPlans inherit ExecutionCondition from RSS, not parameters`() {
        // Build registry with child RSS that creates child plans
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldResolverEntry("Query" to "x", "y")
            .build()

        Fixture("type Query { x:Int, y:Int }", reg) {
            val customCondition = QueryPlanExecutionCondition { false }
            val plan = runExecutionTest {
                val params = mkQPParameters("{x}", ViaductSchema(schema), reg)
                    .copy(executionCondition = customCondition)
                QueryPlanFactory.Default.build(params, "{x}".asDocument)
            }

            // Verify the root plan has the custom ExecutionCondition from parameters
            assertEquals(customCondition, plan.executionCondition)

            // Verify child plans have ALWAYS_EXECUTE (the default from RSS), not the custom condition from parameters
            val field = plan.selectionSet.selections.first() as QueryPlan.Field
            val childPlanEntry = field.childPlans.first()
            // Origin coordinate is set even on plans built with custom ExecutionCondition
            assertEquals("Query" to "x", childPlanEntry.originCoordinate)
            val childPlan = plan.planFor(childPlanEntry)
            assertEquals(ALWAYS_EXECUTE, childPlan.executionCondition)
            assertEquals(true, childPlan.executionCondition.shouldExecute(null))
        }
    }

    @Test
    fun `QueryPlan defaults to ALWAYS_EXECUTE when not specified`() {
        Fixture("type Query { x:Int }") {
            val plan = buildPlan("{x}")

            assertEquals(ALWAYS_EXECUTE, plan.executionCondition)
            assertEquals(true, plan.executionCondition.shouldExecute(null))
        }
    }

    @Test
    fun `regression -- CollectedField_sourceLocation does not throw for fields with missing source location`() {
        // A MergedField may be created from fields without a source location.
        // Ensure that in the CollectedField representation, that we can access this source location
        // without throwing an NPE
        val mergedField = MergedField.newMergedField(GJField("field")).build()

        // sanity check
        assertNull(mergedField.singleField.sourceLocation)

        val cf = CollectedField(
            "field",
            null,
            mergedField,
            emptyList(),
            FieldTypeChildPlans.empty,
        )

        assertEquals(SourceLocation.EMPTY, cf.sourceLocation)
    }

    private class Fixture(
        sdl: String,
        val requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
        fn: Fixture.() -> Unit
    ) {
        val schema = sdl.asSchema
        val query: GraphQLObjectType = schema.queryType

        init {
            fn(this)
        }

        fun buildPlan(doc: String): QueryPlan = buildPlan(doc, ViaductSchema(schema), requiredSelectionSetRegistry)
    }
}

private class CountingRequiredSelectionSetResolver(
    variableName: String,
    private val backingRequiredSelectionSet: RequiredSelectionSet,
) : VariablesResolver {
    var requiredSelectionSetReads = 0
        private set

    override val variableNames: Set<String> = setOf(variableName)

    override val requiredSelectionSet: RequiredSelectionSet
        get() {
            requiredSelectionSetReads += 1
            return backingRequiredSelectionSet
        }

    override suspend fun resolve(
        ctx: VariablesResolver.ResolveCtx,
        context: EngineExecutionContext,
    ): Map<String, Any?> = error("not used")
}

internal fun checkEquals(
    exp: QueryPlan,
    act: QueryPlan,
) {
    checkEquals(exp.selectionSet, act.selectionSet)
    checkEquals(exp.fragments, act.fragments)
    act.variablesResolvers.shouldHaveSize(exp.variablesResolvers.size)
    exp.variablesResolvers.zip(act.variablesResolvers).forEach { (expvr, actvr) ->
        checkEqualsVariablesResolver(expvr, actvr)
    }
}

internal fun checkEquals(
    exp: SelectionSet,
    act: SelectionSet,
) {
    act.selections.shouldHaveSize(exp.selections.size)
    exp.selections.zip(act.selections).forEach { (expSel, actSel) ->
        checkEqualsSelection(expSel, actSel)
    }
}

internal fun checkEquals(
    exp: Fragments,
    act: Fragments,
) {
    assertEquals(exp.map.size, act.map.size)
    if (exp.map.isNotEmpty()) {
        exp.forEach { (expName, expDef) ->
            val actDef = act.map[expName]
            assertNotNull(actDef, "Expected fragment '$expName' to be present")
            checkEquals(expDef, actDef!!)
        }
    }
}

internal fun checkEqualsVariablesResolver(
    exp: VariablesResolver,
    act: VariablesResolver,
) {
    assertEquals(exp, act)
}

internal fun checkEqualsSelection(
    exp: Selection,
    act: Selection,
) {
    when (exp) {
        is Field -> {
            val actField = act.shouldBeInstanceOf<Field>()
            assertEquals(exp.resultKey, actField.resultKey)
            assertEquals(exp.constraints, actField.constraints)
            checkEqualsNode(exp.field, actField.field)
            val expSelectionSet = exp.selectionSet
            if (expSelectionSet != null) {
                assertNotNull(actField.selectionSet)
                checkEquals(expSelectionSet, actField.selectionSet!!)
            } else {
                assertNull(actField.selectionSet)
            }
            checkEqualsFieldChildPlanList(exp.childPlans, actField.childPlans)
        }

        is FragmentSpread -> {
            val actSpread = act.shouldBeInstanceOf<FragmentSpread>()
            assertEquals(exp.name, actSpread.name)
            assertEquals(exp.constraints, actSpread.constraints)
        }

        is InlineFragment -> {
            val actInline = act.shouldBeInstanceOf<InlineFragment>()
            checkEquals(exp.selectionSet, actInline.selectionSet)
            assertEquals(exp.constraints, actInline.constraints)
        }

        is CollectedField -> {
            val actCollected = act.shouldBeInstanceOf<CollectedField>()
            assertEquals(exp.responseKey, actCollected.responseKey)
            val expSelectionSet = exp.selectionSet
            if (expSelectionSet != null) {
                assertNotNull(actCollected.selectionSet)
                checkEquals(expSelectionSet, actCollected.selectionSet!!)
            } else {
                assertNull(actCollected.selectionSet)
            }
            assertMergedFieldsEqual(ResultPath.rootPath(), exp.mergedField, actCollected.mergedField)
            checkEqualsFieldChildPlanList(exp.childPlans, actCollected.childPlans)
        }
    }
}

internal fun checkEqualsQueryPlanList(
    exp: List<QueryPlan>,
    act: List<QueryPlan>,
) {
    act.shouldHaveSize(exp.size)
    exp.zip(act).forEach { (expCp, actCp) ->
        checkEquals(expCp, actCp)
    }
}

internal fun checkEqualsFieldChildPlanList(
    exp: List<FieldChildPlan>,
    act: List<FieldChildPlan>,
) {
    act.shouldHaveSize(exp.size)
    exp.zip(act).forEach { (expCp, actCp) ->
        assertEquals(expCp.originCoordinate, actCp.originCoordinate)
        assertEquals(expCp.queryPlanParentType, actCp.queryPlanParentType)
    }
}

internal fun checkEquals(
    exp: FragmentDefinition,
    act: FragmentDefinition,
) {
    checkEquals(exp.selectionSet, act.selectionSet)
    assertEquals(exp.variableReferences, act.variableReferences)
}

internal fun <T : Node<*>> checkEqualsNode(
    exp: T,
    act: T,
) {
    assertEquals(AstPrinter.printAst(exp), AstPrinter.printAst(act))
}

internal fun buildPlan(
    doc: String,
    schema: ViaductSchema,
    requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty
): QueryPlan =
    runExecutionTest {
        mkQPParameters(doc, schema, requiredSelectionSetRegistry).let { params ->
            QueryPlanFactory.Default.build(params, doc.asDocument)
        }
    }

internal fun mkQueryPlan(
    selectionSet: SelectionSet = SelectionSet(emptyList()),
    fragments: Fragments = Fragments.empty,
    variablesResolvers: List<VariablesResolver> = emptyList(),
    parentType: GraphQLOutputType,
    childPlans: List<QueryPlan> = emptyList(),
    attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
): QueryPlan {
    val indexedChildPlans = childPlans.map { it.withRequiredSelectionSetIdForTest() }
    return QueryPlan(
        selectionSet = selectionSet,
        fragments = fragments,
        variablesResolvers = variablesResolvers,
        parentType = parentType,
        childPlanIds = indexedChildPlans.map { requireNotNull(it.requiredSelectionSetId) },
        baseIndex = indexedChildPlans.fold(QueryPlanIndex.empty()) { index, plan -> plan.index.merge(index) },
        astSelectionSet = mockk(),
        attribution = attribution,
        executionCondition = ALWAYS_EXECUTE,
        variableDefinitions = emptyList()
    )
}

private fun QueryPlan.withRequiredSelectionSetIdForTest(): QueryPlan =
    if (requiredSelectionSetId != null) {
        this
    } else {
        copy(requiredSelectionSetId = createRSS((parentType as? GraphQLNamedOutputType)?.name ?: "Query", "__test").id)
    }

internal fun mkQPParameters(
    doc: String,
    schema: ViaductSchema,
    requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
): QueryPlan.Parameters =
    QueryPlan.Parameters(
        doc,
        schema,
        requiredSelectionSetRegistry,
    )

private fun mkField(
    resultKey: String,
    constraints: Constraints,
    field: GJField? = null,
    selectionSet: SelectionSet? = null,
    childPlans: List<FieldChildPlan> = emptyList(),
    fieldTypeChildPlans: Map<GraphQLObjectType, List<QueryPlan>> = emptyMap()
) = QueryPlan.Field(
    resultKey = resultKey,
    constraints = constraints,
    field = field ?: GJField(resultKey),
    selectionSet = selectionSet,
    childPlans = childPlans,
    fieldTypeChildPlans = fieldTypeChildPlansFor(fieldTypeChildPlans)
)

private fun fieldTypeChildPlansFor(plansByType: Map<GraphQLObjectType, List<QueryPlan>>): FieldTypeChildPlans =
    if (plansByType.isEmpty()) {
        FieldTypeChildPlans.empty
    } else {
        FieldTypeChildPlans { objectType -> plansByType[objectType].orEmpty() }
    }

private fun conditionalDirectiveReference(name: String) = SelectionVariableReference(name, Kind.CONDITIONAL_DIRECTIVE)

private fun fieldArgumentReference(name: String) = SelectionVariableReference(name, Kind.FIELD_ARGUMENT)

private fun directiveReference(name: String) = SelectionVariableReference(name, Kind.DIRECTIVE)

private fun SelectionSet.findField(resultKey: String): Field? =
    selections.firstNotNullOfOrNull { selection ->
        when (selection) {
            is Field ->
                if (selection.resultKey == resultKey) {
                    selection
                } else {
                    selection.selectionSet?.findField(resultKey)
                }

            is InlineFragment -> selection.selectionSet.findField(resultKey)
            is CollectedField -> selection.selectionSet?.findField(resultKey)
            is FragmentSpread -> null
        }
    }

private fun QueryPlan.planFor(childPlan: FieldChildPlan): QueryPlan = planFor(childPlan.requiredSelectionSetId)

private fun QueryPlan.planFor(requiredSelectionSetId: RequiredSelectionSet.Id): QueryPlan =
    checkNotNull(index.find(requiredSelectionSetId)) {
        "Missing QueryPlan for RequiredSelectionSet $requiredSelectionSetId"
    }

internal fun SimpleMeterRegistry.counterCount(
    metricName: String,
    tagName: String,
    tagValue: String,
): Double =
    checkNotNull(
        find(metricName)
            .tag(tagName, tagValue)
            .counter()
    ) {
        "Missing counter $metricName for $tagName=$tagValue"
    }.count()

internal fun SimpleMeterRegistry.timerCount(
    metricName: String,
    tagName: String,
    tagValue: String,
): Long =
    checkNotNull(
        find(metricName)
            .tag(tagName, tagValue)
            .timer()
    ) {
        "Missing timer $metricName for $tagName=$tagValue"
    }.count()

internal fun SimpleMeterRegistry.gaugeValue(metricName: String): Double =
    checkNotNull(
        find(metricName)
            .gauge()
    ) {
        "Missing gauge $metricName"
    }.value()

private fun QueryPlan.eagerChildPlans(): List<QueryPlan> = childPlanIds.map(::planFor)

/**
 * Test helper: wrap a list of [QueryPlan]s as [FieldChildPlan]s with a given origin coordinate.
 *
 * Used when the plan tree is built outside the registry (via `mkQueryPlan` / `buildPlan`) and we
 * want to pin the origin coordinate manually for `checkEquals` comparisons. Requires at least
 * one plan; an empty `mkFieldChildPlans(...)` call is almost certainly a typo (`emptyList()`
 * is the right way to express "no child plans").
 */
internal fun mkFieldChildPlans(
    originParentType: String,
    originFieldName: String,
    vararg plans: QueryPlan,
): List<FieldChildPlan> {
    require(plans.isNotEmpty()) {
        "mkFieldChildPlans requires at least one plan; use emptyList<FieldChildPlan>() if you intend no child plans."
    }
    return plans.map { plan ->
        val planWithId = plan.withRequiredSelectionSetIdForTest()
        FieldChildPlan(
            plan = planWithId,
            originCoordinate = originParentType to originFieldName,
        )
    }
}

private fun typeConstraint(type: GraphQLObjectType) = Constraints(emptyList(), listOf(type))
