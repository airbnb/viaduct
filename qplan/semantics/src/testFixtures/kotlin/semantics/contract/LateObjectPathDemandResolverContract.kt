package semantics.contract

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import model.ObjectEngineResult
import model.merge
import model.objectOf
import model.operationSelectionsFrom
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.correctresolution.correctResolution
import semantics.shared.ResolverInvocationObservation
import semantics.shared.instantiateBindings
import viaduct.graphql.schema.ViaductSchema

interface LateObjectPathDemandResolverContract : ResolverContract {
    @Test
    fun `disjoint successor demand is closed before one selective producer application`() {
        var fooApplications = 0
        var fooDemandFields: Set<String>? = null
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (
                    field.containingDef.name == "Query" &&
                    field.name == "foo" &&
                    demand != null
                ) {
                    fooApplications += 1
                    fooDemandFields =
                        demand
                            .merge(field.type.baseTypeDef as ViaductSchema.Object)
                            .groundKeys()
                            .mapTo(linkedSetOf()) { groundKey ->
                                groundKey.field.name
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
                      foo: Foo! @resolver(result: {z: 2, w: 3})
                    }

                    type Foo {
                      x: Int!
                        @resolver(of: "z", result: "sum(z, z, z, z, z)")
                      y: Int!
                        @resolver(of: "w", result: "sum(w, w, w, w, w, w, w)")
                      z: Int!
                      w: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val fooKey = world.schema.contractKey("Query", "foo")
        val xKey = world.schema.contractKey("Foo", "x")
        val yKey = world.schema.contractKey("Foo", "y")
        val selections =
            testWorld.schemas.operationSelectionsFrom(
                "query { foo { x y } }",
            )

        val resolution =
            resolveAndValidateObserved(world, world.objectOf("Query"), selections, resolverObserver = invocationObserver)
        val resolved = resolution.result
        val foo = resolved.getCell(fooKey).get() as ObjectEngineResult

        assertEquals(10, foo.getCell(xKey).get())
        assertEquals(21, foo.getCell(yKey).get())
        assertEquals(1, fooApplications)
        assertEquals(setOf("x", "y", "z", "w"), fooDemandFields)
        assertTrue(
            resolved.correctResolution(
                resolution.operation,
                selections
                    .merge(world.schema.requireQueryTypeDef())
                    .instantiateBindings(resolution.operation),
            ),
        )
    }

    @Test
    fun `open resolver template closes demand before an argumentless descendant launches`() {
        var nodeApplications = 0
        var nodeDemandFields: Set<String>? = null
        val invocationObserver = object : ResolverApplicationArguments() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (
                    field.containingDef.name == "Mid" &&
                    field.name == "node" &&
                    demand != null
                ) {
                    nodeApplications += 1
                    nodeDemandFields =
                        demand
                            .merge(field.type.baseTypeDef as ViaductSchema.Object)
                            .groundKeys()
                            .mapTo(linkedSetOf()) { groundKey ->
                                groundKey.field.name
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
                      trigger: Int!
                        @resolver(
                          of: "late(arg: ${'$'}value) seed { node { first } }"
                          pathVars: [{name: "value", path: ["seed", "node", "first"]}]
                          result: "sum(late)"
                        )
                      late(arg: Int!): Int!
                        @resolver(
                          of: "seed { node { second } }"
                          result: "sum(seed.node.second)"
                        )
                      seed: Mid! @resolver(result: {})
                    }

                    type Mid {
                      node: Leaf! @resolver(result: {first: 1, second: 2})
                    }

                    type Leaf {
                      first: Int!
                      second: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val triggerKey = world.schema.contractKey("Query", "trigger")

        val resolved = resolveAndValidate(testWorld, "query { trigger }", resolverObserver = invocationObserver)

        assertEquals(2, resolved.getCell(triggerKey).get())
        assertEquals(1, nodeApplications)
        assertEquals(setOf("first", "second"), nodeDemandFields)
        invocationObserver.assertArguments(
            world.schema.requireObjectField("Query", "late"),
            mapOf("arg" to 1),
        )
    }

    @Test
    fun `open resolver template retains output demand through nested resolver inputs`() {
        var nodeApplications = 0
        var nodeDemandFields: Set<String>? = null
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (
                    field.containingDef.name == "Passive" &&
                    field.name == "node" &&
                    demand != null
                ) {
                    nodeApplications += 1
                    nodeDemandFields =
                        demand
                            .merge(field.type.baseTypeDef as ViaductSchema.Object)
                            .groundKeys()
                            .mapTo(linkedSetOf()) { groundKey ->
                                groundKey.field.name
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
                      result: Int!
                        @resolver(
                          of: "root { middle { passive { node { shallow } } } } source root { late(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(root.late)"
                        )
                      source: Int!
                        @resolver(of: "delay", result: "sum(delay)")
                      delay: Int! @resolver(result: 1)
                      root: Root!
                        @resolver(result: {middle: {passive: {}}})
                    }

                    type Root {
                      middle: Middle!
                      late(value: Int!): Int!
                        @resolver(
                          of: "middle { intermediate }"
                          result: "sum(middle.intermediate)"
                        )
                    }

                    type Middle {
                      passive: Passive!
                      intermediate: Int!
                        @resolver(
                          of: "passive { node { deep } }"
                          result: "sum(passive.node.deep)"
                        )
                    }

                    type Passive {
                      node: Leaf!
                        @resolver(result: {shallow: 1, deep: 2})
                    }

                    type Leaf {
                      shallow: Int!
                      deep: Int!
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")

        val resolved = resolveAndValidate(testWorld, "query { result }", resolverObserver = invocationObserver)

        assertEquals(2, resolved.getCell(resultKey).get())
        assertEquals(1, nodeApplications)
        assertEquals(setOf("shallow", "deep"), nodeDemandFields)
    }

    @Test
    fun `future variable boundary selects its passive predecessors`() {
        var parentApplications = 0
        var parentDemandFields: Set<String>? = null
        val invocationObserver = object : ResolverApplicationArguments() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (
                    field.containingDef.name == "Query" &&
                    field.name == "parent" &&
                    demand != null
                ) {
                    parentApplications += 1
                    parentDemandFields =
                        linkedSetOf<String>().also { fields ->
                            demand
                                .merge(field.type.baseTypeDef as ViaductSchema.Object)
                                .forEach { selection ->
                                    fields += selection.key.field.name
                                }
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
                      late: Int!
                        @resolver(
                          of: "provider parent { computed(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["provider"]}]
                          result: "sum(parent.computed)"
                        )
                      provider: Int! @resolver(result: 7)
                      parent: Payload! @resolver(result: {source: 7})
                    }

                    type Payload {
                      source: Int!
                      computed(value: Int!): Int!
                        @resolver(of: "source", result: "sum(source)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val lateKey = world.schema.contractKey("Query", "late")

        val resolved = resolveAndValidate(testWorld, "query { late }", resolverObserver = invocationObserver)

        assertEquals(7, resolved.getCell(lateKey).get())
        assertEquals(1, parentApplications)
        assertEquals(setOf("source"), parentDemandFields)
        invocationObserver.assertArguments(
            world.schema.requireObjectField("Payload", "computed"),
            mapOf("value" to 7),
        )
    }

    @Test
    fun `late variable selection crosses a passive object field`() {
        var holderApplications = 0
        var computedApplications = 0
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (demand != null) {
                    when (field.containingDef.name to field.name) {
                        "Query" to "holder" -> holderApplications += 1
                        "Nested" to "computed" -> computedApplications += 1
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
                      result: Int!
                        @resolver(
                          of: "provider holder { nested { computed(value: ${'$'}value) } }"
                          pathVars: [{name: "value", path: ["provider"]}]
                          result: "sum(holder.nested.computed)"
                        )
                      provider: Int! @resolver(result: 7)
                      holder: Holder! @resolver(result: {nested: {}})
                    }

                    type Holder {
                      nested: Nested!
                    }

                    type Nested {
                      computed(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val resultKey = world.schema.contractKey("Query", "result")

        val resolved = resolveAndValidate(testWorld, "query { result }", resolverObserver = invocationObserver)

        assertEquals(7, resolved.getCell(resultKey).get())
        assertEquals(1, holderApplications)
        assertEquals(1, computedApplications)
    }

    @Test
    fun `late equal child calls retain their symbolic identities`() {
        var parentApplications = 0
        var childApplications = 0
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                val demand = observation.suppliedDemand
                if (demand != null) {
                    when (field.containingDef.name to field.name) {
                        "Query" to "parent" -> parentApplications += 1
                        "Parent" to "child" -> childApplications += 1
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
                      early: Int!
                        @resolver(
                          of: "parent { child(value: 1) }"
                          result: "sum(parent.child)"
                        )
                      outer: Int!
                        @resolver(
                          of: "source parent { child(value: ${'$'}value) }"
                          pathVars: [{name: "value", path: ["source"]}]
                          result: "sum(parent.child)"
                        )
                      source: Int!
                        @resolver(of: "delay", result: "sum(delay)")
                      delay: Int! @resolver(result: 1)
                      parent: Parent! @resolver(result: {})
                    }

                    type Parent {
                      child(value: Int!): Int!
                        @resolver(result: "sum(${'$'}value)")
                    }
                    """.trimIndent(),
            )
        val world = testWorld.assumptions
        val outerKey = world.schema.contractKey("Query", "outer")
        val parentKey = world.schema.contractKey("Query", "parent")

        val resolved = resolveAndValidate(testWorld, "query { early outer }", resolverObserver = invocationObserver)
        val parent = resolved.getCell(parentKey).get() as ObjectEngineResult

        assertEquals(1, resolved.getCell(outerKey).get())
        assertEquals(1, parentApplications)
        assertEquals(2, childApplications)
        assertEquals(
            2,
            parent.keys.count { groundKey -> groundKey.field.name == "child" },
        )
    }
}
