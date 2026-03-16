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
import graphql.schema.GraphQLObjectType
import graphql.schema.GraphQLOutputType
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import strikt.api.Assertion
import strikt.api.expectThat
import strikt.assertions.hasSize
import strikt.assertions.isA
import strikt.assertions.isEqualTo
import strikt.assertions.isNotNull
import strikt.assertions.isNotSameInstanceAs
import strikt.assertions.isNull
import strikt.assertions.isSameInstanceAs
import strikt.assertions.single
import strikt.assertions.withSingle
import strikt.assertions.withValue
import viaduct.arbitrary.graphql.asDocument
import viaduct.arbitrary.graphql.asSchema
import viaduct.engine.api.ExecutionAttribution
import viaduct.engine.api.FromObjectFieldVariable
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.VariablesResolver
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.mocks.MockRequiredSelectionSetRegistry
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
import viaduct.engine.runtime.execution.constraints.Constraints

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
                                        .build(),
                                    emptyList()
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

            expectThat(plan) {
                // plan should contain a single Field 'x'
                val field = get { selectionSet.selections }.single().isA<Field>()

                // field x should have one child plan for its RSS
                field.get { childPlans }.withSingle {
                    // child plan should contain a single plan for its variables
                    get { childPlans }.withSingle {
                        // the variable plan should contain a single field selection, 'z'
                        val field = get { selectionSet.selections }.single().isA<Field>()
                        field.get { resultKey }.isEqualTo("z")
                    }

                    // child plan should contain variables resolvers for "vara"
                    get { variablesResolvers }.single().get { variableNames }.single().isEqualTo("vara")
                }
            }
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
            expectThat(buildPlan("{x}")) {
                val fieldX = get { selectionSet.selections }.single().isA<Field>()

                // field x should have one child plan for its rss
                fieldX.get { childPlans }.withSingle {
                    // the rss plan should include a plan for its variables
                    get { childPlans }.withSingle {
                        // the variables plan should include a field selection on 'z'
                        get { selectionSet.selections }.single().isA<Field>().get { resultKey }.isEqualTo("z")
                    }
                }
            }
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
            expectThat(buildPlan("{x}")) {
                // plan should have a single field selection
                val fieldX = get { selectionSet.selections }.single().isA<Field>()
                // the field should have result key "x"
                fieldX.get { resultKey }.isEqualTo("x")

                // the field should have a single child plan
                fieldX.get { childPlans }.withSingle {
                    // the child plan should have a fragment spread on T
                    val spread = get { selectionSet.selections }.single().isA<FragmentSpread>()
                    spread.get { name }.isEqualTo("T")

                    // fragment T should be in the child plans fragment map
                    get { fragments }.withValue("T") {
                        get { selectionSet.selections }.single()
                            .isA<Field>()
                            .get { resultKey }.isEqualTo("y")
                    }

                    // the current child plan should have a variable resolver for variable "vara"
                    get { variablesResolvers }.single().get { variableNames }.single().isEqualTo("vara")

                    // the current child plan should have its own child plan for vara
                    get { childPlans }.withSingle {
                        // the variables child plan should have a single selection on field 'z'
                        get { selectionSet.selections }.single().isA<Field>().get { resultKey }.isEqualTo("z")
                    }
                }
            }
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
            expectThat(buildPlan("{x}")) {
                // plan should contain a single selection
                get { selectionSet.selections }.withSingle {
                    // the selection should be a field with result key 'x'
                    val field = isA<Field>()
                    field.get { resultKey } isEqualTo ("x")

                    // the selection has a single child plan
                    field.get { childPlans }.withSingle {
                        // the child plan contains a single fragment spread on "T"
                        val spread = get { selectionSet.selections }.single().isA<FragmentSpread>()
                        spread.get { name }.isEqualTo("T")

                        // the child plan should have a variables resolver for 'z'
                        get { variablesResolvers }.single().get { variableNames }.single().isEqualTo("z")

                        // the child plan should contain a variables plan
                        get { childPlans }.withSingle {
                            // the variables plan should have a selection on 'z'
                            get { selectionSet.selections }.single().isA<Field>()
                                .get { resultKey }.isEqualTo("z")
                        }
                    }
                }
            }
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

            expectThat(buildPlan("{x{y}}")) {
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

            expectThat(buildPlan("{node{y}}")) {
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
    fun `QueryPlanFactory_Cached -- caches plan within same instance`() {
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val params = mkQPParameters("{__typename}", schema)
        runExecutionTest {
            val plan1 = factory.build(params, "{__typename}".asDocument)
            val plan2 = factory.build(params, "{__typename}".asDocument)
            expectThat(plan1).isSameInstanceAs(plan2)
        }
    }

    @Test
    fun `QueryPlanFactory_Default -- does not cache`() {
        val schema = ViaductSchema("type Query {x:Int}".asSchema)
        val params = mkQPParameters("{__typename}", schema)
        runExecutionTest {
            val plan1 = QueryPlanFactory.Default.build(params, "{__typename}".asDocument)
            val plan2 = QueryPlanFactory.Default.build(params, "{__typename}".asDocument)
            expectThat(plan1).isNotSameInstanceAs(plan2)
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
            expectThat(plan1.executionCondition).isSameInstanceAs(condition1)
            expectThat(plan2.executionCondition).isSameInstanceAs(condition2)
            expectThat(planAlways.executionCondition).isSameInstanceAs(ALWAYS_EXECUTE)
            // ALWAYS_EXECUTE returns the cached plan directly (no copy) — same instance
            expectThat(planAlways).isSameInstanceAs(planAlways2)
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
            executeAccessChecksInModstrat = false,
        )
        val objectA = schema.schema.getObjectType("ObjectA")!!
        runExecutionTest {
            val plan = factory.build(params, "{x{id} y{id}}".asDocument)

            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            val yField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "y" }
            // Each field's fieldTypeChildPlans has one entry for ObjectA with one type checker plan
            val xPlans = xField.fieldTypeChildPlans[objectA]!!.value
            val yPlans = yField.fieldTypeChildPlans[objectA]!!.value
            expectThat(xPlans).hasSize(1)
            expectThat(yPlans).hasSize(1)
            // Both plans come from the same RSS singleton — must be the exact same QueryPlan instance
            expectThat(xPlans.first()).isSameInstanceAs(yPlans.first())
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
            executeAccessChecksInModstrat = false,
        )
        runExecutionTest {
            // Build with {x} — triggers a cache miss for the top-level plan
            val plan1 = factory.build(params, "{x}".asDocument)
            // Build with {x z} — different top-level plan, but checker RSS for x is same object
            val params2 = params.copy(query = "{x z}")
            val plan2 = factory.build(params2, "{x z}".asDocument)

            val xField1 = plan1.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            val xField2 = plan2.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            expectThat(xField1.childPlans).hasSize(1)
            expectThat(xField2.childPlans).hasSize(1)
            // Both top-level plans share the same RSS child plan instance
            expectThat(xField1.childPlans.first()).isSameInstanceAs(xField2.childPlans.first())
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
            executeAccessChecksInModstrat = false,
        )
        runExecutionTest {
            val plan = factory.build(params, "{x}".asDocument)
            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            // Checker RSS for x produces one child plan (the cycle is broken at that level)
            expectThat(xField.childPlans).hasSize(1)
            val checkerPlan = xField.childPlans.first()
            // The checker plan contains field x but no further child plans (cycle broken)
            val innerX = checkerPlan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            expectThat(innerX.childPlans).hasSize(0)
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
            executeAccessChecksInModstrat = false,
        )
        runExecutionTest {
            // Must complete without stack overflow or infinite recursion
            val plan = factory.build(params, "{x{z}}".asDocument)
            val xField = plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "x" }
            val objectX = schema.schema.getObjectType("ObjectX")!!
            // ObjectX has a type checker — x should have fieldTypeChildPlans for ObjectX
            val typeCheckerPlans = xField.fieldTypeChildPlans[objectX]
            expectThat(typeCheckerPlans).isNotNull()
            // Forcing the lazy terminates — RSS plan is built once then cached
            val typeCheckerPlanList = typeCheckerPlans!!.value
            expectThat(typeCheckerPlanList).hasSize(1)
            val checkerPlan = typeCheckerPlanList.first()
            // The checker plan selects y (type ObjectX); y also has fieldTypeChildPlans for ObjectX
            val yField = checkerPlan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "y" }
            val yTypeCheckerPlans = yField.fieldTypeChildPlans[objectX]
            expectThat(yTypeCheckerPlans).isNotNull()
            // Forcing y's lazy also terminates — returns the RSS plan already in the cache
            val yTypeCheckerPlanList = yTypeCheckerPlans!!.value
            expectThat(yTypeCheckerPlanList).hasSize(1)
            // Both lazies return the same cached RSS plan instance
            expectThat(typeCheckerPlanList.first()).isSameInstanceAs(yTypeCheckerPlanList.first())
        }
    }

    @Test
    fun `QueryPlanFactory_Cached -- sub-plan RSS is not globally cached with cycle-truncated children`() {
        // RSS1 (checker for a) selects {b}; RSS2 (checker for b) selects {a} — mutual cycle.
        //
        // When {a} is built, RSS1 is cached globally. During RSS1's build, RSS2 is encountered
        // as a sub-plan with RSS1 in the building set, so RSS2's `a` field gets no child plans
        // (cycle broken). Without isolation, RSS2 would be globally cached in this truncated form.
        //
        // The correct behavior: RSS2 is only cached locally during RSS1's build. When {b} is
        // later built, RSS2 is built fresh and `a` correctly gets RSS1 as a child plan.
        val reg = MockRequiredSelectionSetRegistry.builder()
            .fieldCheckerEntry("Query" to "a", "b") // RSS1
            .fieldCheckerEntry("Query" to "b", "a") // RSS2
            .build()
        val factory = QueryPlanFactory.Cached()
        val schema = ViaductSchema("type Query { a: Int b: Int }".asSchema)
        val params = QueryPlan.Parameters(
            schema = schema,
            registry = reg,
            executeAccessChecksInModstrat = false,
        )
        runExecutionTest {
            // Build {a} — RSS1 is cached globally; RSS2 is built locally within RSS1 (truncated)
            val planA = factory.build(params.copy(query = "{a}"), "{a}".asDocument)
            val aField = planA.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "a" }
            expectThat(aField.childPlans).hasSize(1) // RSS1 attached to a
            val rss1Plan = aField.childPlans.first()
            val bInRss1 = rss1Plan.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "b" }
            expectThat(bInRss1.childPlans).hasSize(1) // RSS2-truncated embedded in RSS1
            val aInRss2Truncated = bInRss1.childPlans.first().selectionSet.selections
                .filterIsInstance<Field>().first { it.resultKey == "a" }
            expectThat(aInRss2Truncated.childPlans).hasSize(0) // cycle truncated here

            // Build {b} — RSS2 not in global cache, so built fresh with full children
            val planB = factory.build(params.copy(query = "{b}"), "{b}".asDocument)
            val bField = planB.selectionSet.selections.filterIsInstance<Field>().first { it.resultKey == "b" }
            expectThat(bField.childPlans).hasSize(1) // RSS2-fresh attached to b
            val aInRss2Fresh = bField.childPlans.first().selectionSet.selections
                .filterIsInstance<Field>().first { it.resultKey == "a" }
            // RSS2 built fresh: a has RSS1 as child (cycle broken at RSS1's level, not RSS2's)
            // Fails with flat context: RSS2 was globally cached during RSS1's build with a having no children
            expectThat(aInRss2Fresh.childPlans).hasSize(1)
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
                QueryPlanFactory.Default.build(params, "{x}".asDocument)
            }

            // Verify the ExecutionCondition is stored in the plan
            expectThat(plan.executionCondition).isEqualTo(customCondition)
            expectThat(plan.executionCondition.shouldExecute(null)).isEqualTo(false)
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
            expectThat(plan.executionCondition).isEqualTo(customCondition)

            // Verify child plans have ALWAYS_EXECUTE (the default from RSS), not the custom condition from parameters
            val field = plan.selectionSet.selections.first() as QueryPlan.Field
            val childPlan = field.childPlans.first()
            expectThat(childPlan.executionCondition).isEqualTo(ALWAYS_EXECUTE)
            expectThat(childPlan.executionCondition.shouldExecute(null)).isEqualTo(true)
        }
    }

    @Test
    fun `QueryPlan defaults to ALWAYS_EXECUTE when not specified`() {
        Fixture("type Query { x:Int }") {
            val plan = buildPlan("{x}")

            expectThat(plan.executionCondition).isEqualTo(ALWAYS_EXECUTE)
            expectThat(plan.executionCondition.shouldExecute(null)).isEqualTo(true)
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
            emptyMap(),
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
