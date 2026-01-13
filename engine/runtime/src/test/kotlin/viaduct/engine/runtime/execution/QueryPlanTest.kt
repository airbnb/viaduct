@file:OptIn(ExperimentalCoroutinesApi::class)

package viaduct.engine.runtime.execution

import graphql.execution.ResultPath
import graphql.language.Argument
import graphql.language.AstPrinter
import graphql.language.Directive as GJDirective
import graphql.language.Field as GJField
import graphql.language.FragmentDefinition as GJFragmentDefinition
import graphql.language.Node
import graphql.language.SelectionSet as GJSelectionSet
import graphql.language.TypeName as GJTypeName
import graphql.language.VariableReference
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNull
import viaduct.arbitrary.graphql.asDocument
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.QueryPlanExecutionCondition
import viaduct.engine.api.QueryPlanExecutionCondition.Companion.ALWAYS_EXECUTE
import viaduct.engine.api.RequiredSelectionSetRegistry
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.execution.ExecutionTestHelpers.executeViaductModernGraphQL
import viaduct.engine.runtime.execution.ExecutionTestHelpers.runExecutionTest
import viaduct.engine.runtime.execution.QueryPlan.CollectedField
import viaduct.engine.runtime.execution.QueryPlan.Field
import viaduct.engine.runtime.execution.QueryPlan.FragmentDefinition
import viaduct.engine.runtime.execution.QueryPlan.FragmentSpread
import viaduct.engine.runtime.execution.QueryPlan.Fragments
import viaduct.engine.runtime.execution.QueryPlan.InlineFragment
import viaduct.engine.runtime.execution.QueryPlan.Selection
import viaduct.engine.runtime.execution.QueryPlan.SelectionSet
import viaduct.service.api.spi.FlagManager.Flags
import viaduct.service.api.spi.mocks.MockFlagManager

class QueryPlanTest {
    @Test
    fun `scalar field`() {
        Fixture("type Query { x:Int }") {
            expectThat(buildPlan("{x}")) {
                checkEquals(
                    mkQueryPlan(
                        SelectionSet(
                            mkField("x", typeConstraint(query))
                        ),
                        parentType = query
                    )
                )
            }
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
            expectThat(plan) {
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
                    )
                )
            }
            expectThat(plan.variableDefinitions.map { it.name }).equals(listOf("var"))
        }
    }

    @Test
    fun `field with subselections`() {
        Fixture("type Query { q:Query }") {
            expectThat(buildPlan("{ q { __typename } }")) {
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
                    )
                )
            }
        }
    }

    @Test
    fun `inline fragment`() {
        Fixture("type Query { x:Int }") {
            expectThat(buildPlan("{ ... { x } }")) {
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
                    )
                )
            }
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
            expectThat(plan) {
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
                                        .build()
                                )
                            )
                        ),
                        parentType = query
                    )
                )
            }
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
            expectThat(plan) {
                checkEquals(
                    mkQueryPlan(
                        SelectionSet(
                            mkField(
                                "x",
                                typeConstraint(query),
                                childPlans = listOf(buildPlan("{y}")),
                            )
                        ),
                        parentType = query
                    )
                )
            }
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
            expectThat(plan) {
                checkEquals(
                    mkQueryPlan(
                        SelectionSet(
                            mkField(
                                "x",
                                typeConstraint(query),
                                childPlans = listOf(
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
                    )
                )
            }
            expectThat(plan.variableDefinitions.map { it.name }).equals(listOf("vara"))
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
            forChecker = true,
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
            // Ideally we would do a deep equality check here, however it dramatically slows down the tests.
            // Leaving this here as a reminder to revisit if we can optimize the deep equality checks.
            // expectThat(plan) {
            //     checkEquals(
            //         mkQueryPlan(
            //             SelectionSet(
            //                 mkField(
            //                     "x",
            //                     typeConstraint(query),
            //                     childPlans = listOf(
            //                         mkQueryPlan(
            //                             SelectionSet(
            //                                 FragmentSpread("T", Constraints(emptyList(), possibleTypes = setOf(query))),
            //                             ),
            //                             fragments = Fragments(
            //                                 mapOf(
            //                                     "T" to FragmentDefinition(
            //                                         SelectionSet(
            //                                             mkField(
            //                                                 "y",
            //                                                 typeConstraint(query),
            //                                                 GJField(
            //                                                     "y",
            //                                                     listOf(
            //                                                         Argument("a", VariableReference("vara"))
            //                                                     )
            //                                                 )
            //                                             )
            //                                         ),
            //                                         GJFragmentDefinition.newFragmentDefinition()
            //                                             .name("T")
            //                                             .typeCondition(GJTypeName("Query"))
            //                                             .selectionSet(
            //                                                 GJSelectionSet(
            //                                                     listOf(GJField(
            //                                                         "y",
            //                                                         listOf(
            //                                                             Argument("a", VariableReference("vara"))
            //                                                         )
            //                                                     ))
            //                                                 )
            //                                             )
            //                                             .build()
            //                                     )
            //                                 )
            //                             ),
            //                             variablesResolvers = varResolvers,
            //                             parentType = query,
            //                             childPlans = listOf(
            //                                 mkQueryPlan(
            //                                     SelectionSet(
            //                                         mkField("z", typeConstraint(query))
            //                                     ),
            //                                     parentType = query
            //                                 )
            //                             )
            //                         )
            //                     )
            //                 )
            //             ),
            //             parentType = query
            //         )
            //     )
            // }

            // Instead, verify the basic structure without deep comparison
            expectThat(plan.selectionSet.selections).hasSize(1)
            val fieldX = plan.selectionSet.selections[0] as Field
            expectThat(fieldX.resultKey).isEqualTo("x")

            // Check that field x has one child plan (the checker RSS)
            expectThat(fieldX.childPlans).hasSize(1)
            val checkerPlan = fieldX.childPlans[0]

            // Verify the checker plan has a fragment spread to T
            expectThat(checkerPlan.selectionSet.selections).hasSize(1)
            val fragSpread = checkerPlan.selectionSet.selections[0] as FragmentSpread
            expectThat(fragSpread.name).isEqualTo("T")

            // Verify fragment T is defined in the checker plan
            expectThat(checkerPlan.fragments.map).hasSize(1)
            expectThat(checkerPlan.fragments.map["T"]).isNotNull()
            val fragT = checkerPlan.fragments.map["T"]!!

            // Verify fragment T contains field y
            expectThat(fragT.selectionSet.selections).hasSize(1)
            val fieldY = fragT.selectionSet.selections[0] as Field
            expectThat(fieldY.resultKey).isEqualTo("y")

            // Verify the checker plan has child plans for variable resolution (field z)
            expectThat(checkerPlan.childPlans).hasSize(1)
            val varPlan = checkerPlan.childPlans[0]
            expectThat(varPlan.selectionSet.selections).hasSize(1)
            val fieldZ = varPlan.selectionSet.selections[0] as Field
            expectThat(fieldZ.resultKey).isEqualTo("z")
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
            val plan = buildPlan("{x{y}}")
            expectThat(plan) {
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
                    )
                )
            }
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
            val plan = buildPlan("{node{y}}")
            expectThat(plan) {
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
                    )
                )
            }
        }
    }

    @Test
    fun `QueryPlanBuilder -- uses cache by default`() {
        // sanity
        QueryPlan.resetCache()
        assertEquals(0, QueryPlan.cacheSize)
        runExecutionTest {
            executeViaductModernGraphQL("type Query {x:Int}", resolvers = emptyMap(), "{__typename}")
        }
        assertEquals(1, QueryPlan.cacheSize)
    }

    @Test
    fun `QueryPlanBuilder -- bypasses cache when configured`() {
        // sanity
        QueryPlan.resetCache()
        assertEquals(0, QueryPlan.cacheSize)
        runExecutionTest {
            executeViaductModernGraphQL(
                "type Query {x:Int}",
                resolvers = emptyMap(),
                "{__typename}",
                flagManager = MockFlagManager.mk(Flags.DISABLE_QUERY_PLAN_CACHE)
            )
        }
        assertEquals(0, QueryPlan.cacheSize)
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
            expectThat(plan) {
                checkEquals(
                    mkQueryPlan(
                        SelectionSet(
                            mkField(
                                "x",
                                typeConstraint(query),
                                childPlans = listOf(
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
                    )
                )
            }
        }
    }

    @Test
    fun `QueryPlanBuilder -- cycle prevention for direct self-reference`() {
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Query" to "x", "x") // x's checker selects x
            .build()
        Fixture("type Query { x:Int }", reg) {
            val plan = buildPlan("{x}")
            expectThat(plan) {
                checkEquals(
                    mkQueryPlan(
                        SelectionSet(
                            mkField(
                                "x",
                                typeConstraint(query),
                                childPlans = listOf(
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
                    )
                )
            }
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
            expectThat(plan) {
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
                    )
                )
            }
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
            expectThat(plan.selectionSet.selections).hasSize(1)
        }
    }

    @Test
    fun `QueryPlan can be built with custom ExecutionCondition`() {
        Fixture("type Query { x:Int }") {
            val customCondition = QueryPlanExecutionCondition { false }
            val plan = runExecutionTest {
                val params = mkQPParameters("{x}", ViaductSchema(schema), requiredSelectionSetRegistry)
                    .copy(executionCondition = customCondition)
                QueryPlan.build(params, "{x}".asDocument)
            }

            // Verify the ExecutionCondition is stored in the plan
            expectThat(plan.executionCondition).isEqualTo(customCondition)
            expectThat(plan.executionCondition.shouldExecute()).isEqualTo(false)
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
                QueryPlan.build(params, "{x}".asDocument)
            }

            // Verify the root plan has the custom ExecutionCondition from parameters
            expectThat(plan.executionCondition).isEqualTo(customCondition)

            // Verify child plans have ALWAYS_EXECUTE (the default from RSS), not the custom condition from parameters
            val field = plan.selectionSet.selections.first() as QueryPlan.Field
            val childPlan = field.childPlans.first()
            expectThat(childPlan.executionCondition).isEqualTo(ALWAYS_EXECUTE)
            expectThat(childPlan.executionCondition.shouldExecute()).isEqualTo(true)
        }
    }

    @Test
    fun `QueryPlan defaults to ALWAYS_EXECUTE when not specified`() {
        Fixture("type Query { x:Int }") {
            val plan = buildPlan("{x}")

            expectThat(plan.executionCondition).isEqualTo(ALWAYS_EXECUTE)
            expectThat(plan.executionCondition.shouldExecute()).isEqualTo(true)
        }
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

internal fun Assertion.Builder<QueryPlan>.checkEquals(exp: QueryPlan): Assertion.Builder<QueryPlan> =
    and {
        get { selectionSet }.checkEquals(exp.selectionSet)
        get { fragments }.checkEquals(exp.fragments)
        with({ variablesResolvers }) {
            hasSize(exp.variablesResolvers.size)
            exp.variablesResolvers.zip(subject).forEach { (expvr, actvr) ->
                with({ actvr }) {
                    checkEquals(expvr)
                }
            }
        }
    }

internal fun Assertion.Builder<SelectionSet>.checkEquals(exp: SelectionSet): Assertion.Builder<SelectionSet> =
    and {
        with({ selections }) {
            hasSize(exp.selections.size)

            exp.selections.zip(subject).forEach { (expSel, actSel) ->
                with({ actSel }) {
                    checkEquals(expSel)
                }
            }
        }
    }

internal fun Assertion.Builder<Fragments>.checkEquals(exp: Fragments): Assertion.Builder<Fragments> =
    and {
        with({ map }) {
            hasSize(exp.map.size)
            if (exp.map.isNotEmpty()) {
                exp.forEach { (expName, expDef) ->
                    with({ get(expName) }) {
                        isNotNull().checkEquals(expDef)
                    }
                }
            }
        }
    }

internal fun Assertion.Builder<VariablesResolver>.checkEquals(exp: VariablesResolver): Assertion.Builder<VariablesResolver> =
    and {
        isEqualTo(exp)
    }

internal fun <T : Selection> Assertion.Builder<T>.checkEquals(exp: T): Assertion.Builder<T> =
    and {
        when (exp) {
            is Field -> {
                isA<Field>().and {
                    get { resultKey }.isEqualTo(exp.resultKey)
                    get { constraints }.isEqualTo(exp.constraints)
                    get { field }.checkEquals(exp.field)
                    with({ selectionSet }) {
                        exp.selectionSet?.let {
                            isNotNull().checkEquals(it)
                        } ?: isNull()
                    }
                    get { childPlans }.checkEquals(exp.childPlans)
                }
            }

            is FragmentSpread -> {
                isA<FragmentSpread>().and {
                    get { name }.isEqualTo(exp.name)
                    get { constraints }.isEqualTo(exp.constraints)
                }
            }

            is InlineFragment -> {
                isA<InlineFragment>().and {
                    get { selectionSet }.checkEquals(exp.selectionSet)
                    get { constraints }.isEqualTo(exp.constraints)
                }
            }

            is CollectedField -> {
                isA<CollectedField>().and {
                    get { responseKey }.isEqualTo(exp.responseKey)
                    with({ selectionSet }) {
                        exp.selectionSet?.let {
                            isNotNull().checkEquals(it)
                        } ?: isNull()
                    }
                    assertMergedFieldsEqual(ResultPath.rootPath(), exp.mergedField, subject.mergedField)
                    get { childPlans }.checkEquals(exp.childPlans)
                }
            }

            else ->
                assertThat("Unhandled selection type: ${exp::class.simpleName}") { false }
        }
    }

internal fun Assertion.Builder<List<QueryPlan>>.checkEquals(exp: List<QueryPlan>): Assertion.Builder<List<QueryPlan>> =
    and {
        hasSize(exp.size)
        exp.zip(subject).forEach { (expCp, actCp) ->
            with({ actCp }) {
                checkEquals(expCp)
            }
        }
    }

internal fun Assertion.Builder<FragmentDefinition>.checkEquals(exp: FragmentDefinition): Assertion.Builder<FragmentDefinition> =
    and {
        get { selectionSet }.checkEquals(exp.selectionSet)
    }

internal fun <T : Node<*>> Assertion.Builder<T>.checkEquals(exp: T): Assertion.Builder<T> =
    and {
        get { AstPrinter.printAst(this) }.isEqualTo(AstPrinter.printAst(exp))
    }

internal fun buildPlan(
    doc: String,
    schema: ViaductSchema,
    requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty
): QueryPlan =
    runExecutionTest {
        mkQPParameters(doc, schema, requiredSelectionSetRegistry).let { params ->
            QueryPlan.build(params, doc.asDocument)
        }
    }

internal fun mkQueryPlan(
    selectionSet: SelectionSet = SelectionSet(emptyList()),
    fragments: Fragments = Fragments.empty,
    variablesResolvers: List<VariablesResolver> = emptyList(),
    parentType: GraphQLOutputType,
    childPlans: List<QueryPlan> = emptyList(),
    attribution: ExecutionAttribution? = ExecutionAttribution.DEFAULT,
) = QueryPlan(
    selectionSet,
    fragments,
    variablesResolvers,
    parentType,
    childPlans,
    astSelectionSet = mockk(),
    attribution,
    executionCondition = ALWAYS_EXECUTE,
    variableDefinitions = emptyList()
)

internal fun mkQPParameters(
    doc: String,
    schema: ViaductSchema,
    requiredSelectionSetRegistry: RequiredSelectionSetRegistry = RequiredSelectionSetRegistry.Empty,
): QueryPlan.Parameters =
    QueryPlan.Parameters(
        doc,
        schema,
        requiredSelectionSetRegistry,
        // passing false here as it is not relevant for the tests we are running here given empty RSS registry
        executeAccessChecksInModstrat = false
    )

private fun mkField(
    resultKey: String,
    constraints: Constraints,
    field: GJField? = null,
    selectionSet: SelectionSet? = null,
    childPlans: List<QueryPlan> = emptyList(),
    fieldTypeChildPlans: Map<GraphQLObjectType, List<QueryPlan>> = emptyMap()
) = QueryPlan.Field(
    resultKey = resultKey,
    constraints = constraints,
    field = field ?: GJField(resultKey),
    selectionSet = selectionSet,
    childPlans = childPlans,
    fieldTypeChildPlans = fieldTypeChildPlans.mapValues { (_, v) -> lazy { v } }
)

private fun typeConstraint(type: GraphQLObjectType) = Constraints(emptyList(), listOf(type))
