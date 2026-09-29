package semantics.contract

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import model.Arguments
import model.ListEngineResult
import model.ObjectEngineResult
import model.RootFieldReferenceData
import model.emptyFragmentOf
import model.fragmentFrom
import model.merge
import model.objectOf
import model.outputValue
import model.requireObjectField
import model.requireType
import model.testing.TestWorld
import model.testing.fieldResolverOf
import model.testing.fromArgument
import model.testing.selectiveFieldResolverOf
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import semantics.correctresolution.CorrectnessResolverObserver
import semantics.shared.ResolverInvocationObservation
import viaduct.graphql.schema.ViaductSchema

/** Root-field-reference behavior common to every maintained resolver capability tier. */
interface RootFieldReferenceResolverContract : ResolverContract {
    @Test
    fun `abstract target resolves when all possible types conform to the consumer`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      lookup(kind: Int!): LookupResult!
                    }

                    type Container {
                      item: Entity!
                    }

                    interface Entity {
                      value: String!
                    }

                    type Product implements Entity {
                      value: String!
                    }

                    type Service implements Entity {
                      value: String!
                    }

                    union LookupResult = Product | Service
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val lookup = schema.requireObjectField("Query", "lookup")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "item" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(lookup),
                                            arguments = mapOf("kind" to 1),
                                        )
                                }
                            },
                        lookup to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                schema.objectOf("Service") {
                                    "value" setTo "service-${arguments.fieldValues.getValue("kind")}"
                                }
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val result = resolveAndValidate(world, "query { container { item { value } } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).get(),
            )
        val item =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "item")).get(),
            )

        assertEquals("Service", item.type.name)
        assertEquals(
            "service-1",
            item.getCell(world.schema.contractKey("Service", "value")).get(),
        )
    }

    @Test
    fun `root-field references publish scalar and enum values at every consumer shape`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      factory: Factory
                      indirectNumber: Int!
                    }

                    type Container {
                      numbers: [Int!]!
                      status: Status!
                    }

                    type Factory {
                      number(value: Int!): Int!
                      status: Status!
                    }

                    enum Status {
                      READY
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val factory = schema.requireObjectField("Query", "factory")
                    val indirectNumber = schema.requireObjectField("Query", "indirectNumber")
                    val number = schema.requireObjectField("Factory", "number")
                    val status = schema.requireObjectField("Factory", "status")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "numbers" setTo
                                        listOf(
                                            RootFieldReferenceData.of(
                                                path = listOf(factory, number),
                                                arguments = mapOf("value" to 1),
                                            ),
                                            2,
                                            RootFieldReferenceData.of(
                                                path = listOf(factory, number),
                                                arguments = mapOf("value" to 3),
                                            ),
                                        )
                                    "status" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(factory, status),
                                            arguments = emptyMap(),
                                        )
                                }
                            },
                        factory to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Factory")
                            },
                        indirectNumber to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                RootFieldReferenceData.of(
                                    path = listOf(factory, number),
                                    arguments = mapOf("value" to 42),
                                )
                            },
                        number to
                            fieldResolverOf(schema.emptyFragmentOf("Factory")) { _, arguments ->
                                arguments.fieldValues.getValue("value")
                            },
                        status to
                            fieldResolverOf(schema.emptyFragmentOf("Factory")) { _, _ ->
                                "READY"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val result =
            resolveAndValidate(world, "query { indirectNumber container { numbers status } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).get(),
            )
        val numbers =
            assertIs<ListEngineResult>(
                container.getCell(world.schema.contractKey("Container", "numbers")).get(),
            )
        val status =
            assertIs<ViaductSchema.EnumValue>(
                container.getCell(world.schema.contractKey("Container", "status")).get(),
            )

        assertEquals(
            42,
            result.getCell(world.schema.contractKey("Query", "indirectNumber")).get(),
        )
        assertEquals(listOf(1, 2, 3), numbers.map { cell -> cell.get() })
        assertEquals("READY", status.name)
    }

    @Test
    fun `resolves embedded list and tail references while preserving source ownership`() {
        val firstApplications = AtomicInteger()
        val secondApplications = AtomicInteger()
        val unusedApplications = AtomicInteger()
        val overriddenApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      namespace: Namespace!
                    }

                    type Container {
                      product: Product!
                      products: [Product!]!
                      unused: Product!
                    }

                    type Product {
                      value: String!
                      extension: String!
                    }

                    type Namespace {
                      first(id: Int!): Product!
                      second: Product!
                      unused: Product!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val emptyQuery = schema.emptyFragmentOf("Query")
                    val emptyContainer = schema.emptyFragmentOf("Container")
                    val emptyProduct = schema.emptyFragmentOf("Product")
                    val emptyNamespace = schema.emptyFragmentOf("Namespace")
                    val container = schema.requireObjectField("Query", "container")
                    val namespace = schema.requireObjectField("Query", "namespace")
                    val product = schema.requireObjectField("Container", "product")
                    val first = schema.requireObjectField("Namespace", "first")
                    val second = schema.requireObjectField("Namespace", "second")
                    val unused = schema.requireObjectField("Namespace", "unused")
                    val extension = schema.requireObjectField("Product", "extension")
                    mapOf(
                        container to
                            fieldResolverOf(emptyQuery) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(namespace, first),
                                            arguments = mapOf("id" to 7),
                                        )
                                    "products" setTo
                                        listOf(
                                            RootFieldReferenceData.of(
                                                path = listOf(namespace, second),
                                                arguments = emptyMap(),
                                            ),
                                            objectOf("Product") { "value" setTo "passive" },
                                        )
                                    "unused" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(namespace, unused),
                                            arguments = emptyMap(),
                                        )
                                }
                            },
                        product to
                            fieldResolverOf(emptyContainer) { _, _ ->
                                overriddenApplications.incrementAndGet()
                                schema.objectOf("Product") { "value" setTo "overridden" }
                            },
                        first to
                            fieldResolverOf(emptyNamespace) { _, arguments ->
                                firstApplications.incrementAndGet()
                                assertEquals(7, arguments.fieldValues.getValue("id"))
                                RootFieldReferenceData.of(
                                    path = listOf(namespace, second),
                                    arguments = emptyMap(),
                                )
                            },
                        second to
                            fieldResolverOf(emptyNamespace) { _, _ ->
                                secondApplications.incrementAndGet()
                                schema.objectOf("Product") { "value" setTo "referenced" }
                            },
                        unused to
                            fieldResolverOf(emptyNamespace) { _, _ ->
                                unusedApplications.incrementAndGet()
                                schema.objectOf("Product") { "value" setTo "unused" }
                            },
                        extension to fieldResolverOf(emptyProduct) { _, _ -> "extended" },
                    )
                },
            )
        val world = testWorld.assumptions

        val resolution =
            resolveAndValidateObserved(
                world,
                "query { container { product { value extension } products { value } } }",
            )
        val result = resolution.result
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "product")).get(),
            )
        val products =
            assertIs<model.ListEngineResult>(
                container.getCell(world.schema.contractKey("Container", "products")).get(),
            )

        assertEquals("referenced", product.getCell(world.schema.contractKey("Product", "value")).get())
        assertEquals("extended", product.getCell(world.schema.contractKey("Product", "extension")).get())
        assertEquals(2, products.size)
        assertEquals(1, firstApplications.get())
        assertEquals(2, secondApplications.get())
        assertEquals(0, unusedApplications.get())
        assertEquals(0, overriddenApplications.get())
        val referenceInvocations =
            (resolution.operation.resolverObserver as CorrectnessResolverObserver)
                .rootFieldReferenceInvocations()
        assertEquals(3, referenceInvocations.size)
        referenceInvocations.forEach { invocation ->
            assertNotSame(invocation.publicationRoot, invocation.invocationRoot)
        }
        for (left in referenceInvocations.indices) {
            for (right in (left + 1)..<referenceInvocations.size) {
                assertNotSame(
                    referenceInvocations[left].invocationRoot,
                    referenceInvocations[right].invocationRoot,
                )
            }
        }
    }
}

/** Complete-output policy for executable references embedded outside current demand. */
interface CompleteOutputRootFieldReferenceResolverContract : ResolverContract {
    @Test
    fun `does not execute root references embedded in non-selective over-fetch`() {
        val targetApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = false,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      target: Product!
                    }

                    type Container {
                      value: String!
                      unused: Product!
                      unusedList: [Product!]!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "target")
                    val reference = RootFieldReferenceData.of(listOf(target), emptyMap())
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "value" setTo "selected"
                                    "unused" setTo reference
                                    "unusedList" setTo listOf(reference)
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                targetApplications.incrementAndGet()
                                schema.objectOf("Product") { "value" setTo "unexpected" }
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(world, "query { container { value } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).get(),
            )

        assertEquals(setOf("value"), container.keys.mapTo(linkedSetOf()) { key -> key.field.name })
        assertEquals(0, targetApplications.get())
    }
}

/** Existing depth-first families execute a reference before scheduling sibling resolver work. */
interface DepthFirstRootFieldReferenceOrderingContract : ResolverContract {
    @Test
    fun `executes an embedded root reference before its containing object's sibling resolver`() {
        val applications = CopyOnWriteArrayList<String>()
        val invocationObserver = object : CorrectnessResolverObserver() {
            override fun onResolverInvocation(observation: ResolverInvocationObservation) {
                super.onResolverInvocation(observation)
                val field = observation.field
                applications += field.name
            }
        }
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      target: Product!
                    }

                    type Container {
                      product: Product!
                      sibling: String!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "target")
                    val sibling = schema.requireObjectField("Container", "sibling")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(target), emptyMap())
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "target" }
                            },
                        sibling to
                            fieldResolverOf(schema.emptyFragmentOf("Container")) { _, _ -> "sibling" },
                    )
                },
            )
        val world = testWorld.assumptions

        resolveAndValidate(world, "query { container { product { value } sibling } }", resolverObserver = invocationObserver)

        assertEquals(listOf("container", "target", "sibling"), applications.toList())
    }
}

/** Supplied references suppress the standard resolver and its object-fragment demand. */
interface ObjectFragmentRootFieldReferenceResolverContract : ResolverContract {
    @Test
    fun `root-field reference override does not activate registered resolver input demand`() {
        val dependencyApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      product: Product!
                    }

                    type Container {
                      product: Product!
                      dependency: String!
                    }

                    type Product {
                      value: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "product")
                    val consumer = schema.requireObjectField("Container", "product")
                    val dependency = schema.requireObjectField("Container", "dependency")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(target), emptyMap())
                                }
                            },
                        target to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Product") { "value" setTo "target" }
                            },
                        consumer to
                            fieldResolverOf(
                                schema.fragmentFrom(
                                    "fragment ConsumerInput on Container { dependency }",
                                ),
                            ) { _, _ ->
                                error("overridden consumer resolver must not run")
                            },
                        dependency to
                            fieldResolverOf(schema.emptyFragmentOf("Container")) { _, _ ->
                                dependencyApplications.incrementAndGet()
                                "dependency"
                            },
                    )
                },
            )
        val world = testWorld.assumptions
        val result = resolveAndValidate(world, "query { container { product { value } } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "product")).get(),
            )

        assertEquals(
            "target",
            product.getCell(world.schema.contractKey("Product", "value")).get(),
        )
        assertEquals(0, dependencyApplications.get())
    }
}

/** Reference targets inherit the Query-fragment and `FromArgument` capability tier. */
interface QueryFragmentRootFieldReferenceResolverContract : QueryFragmentResolverContract {
    @Test
    fun `binds referenced arguments consumed by the target Query fragment`() {
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      namespace: Namespace!
                      echo(value: Int!): String!
                    }

                    type Container {
                      product: Product!
                    }

                    type Product {
                      value: String!
                    }

                    type Namespace {
                      target(id: Int!): Product!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val namespace = schema.requireObjectField("Query", "namespace")
                    val echo = schema.requireObjectField("Query", "echo")
                    val target = schema.requireObjectField("Namespace", "target")
                    val targetQuery =
                        schema.fragmentFrom(
                            "fragment TargetQuery on Query { echoed: echo(value: ${'$'}argument) }",
                        )
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(
                                            path = listOf(namespace, target),
                                            arguments = mapOf("id" to 7),
                                        )
                                }
                            },
                        echo to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, arguments ->
                                "value-${arguments.fieldValues.getValue("value")}"
                            },
                        target to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Namespace"),
                                queryFragment = targetQuery,
                            ) { _, queryValue, _ ->
                                schema.objectOf("Product") {
                                    "value" setTo queryValue.outputValue("echoed")
                                }
                            },
                    )
                },
                variableProviders = { schema ->
                    val target = schema.requireObjectField("Namespace", "target")
                    mapOf(
                        Arguments.Variable.of(target, "argument") to
                            schema.fromArgument(target, "id"),
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(world, "query { container { product { value } } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "product")).get(),
            )

        assertEquals("value-7", product.getCell(world.schema.contractKey("Product", "value")).get())
    }

    @Test
    fun `referenced Query execution uses singular sharing internally`() {
        assumeTrue(usesSingularQueryOER)
        val sourceApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = selectiveResolvers,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      target: Int!
                      first: Int!
                      second: Int!
                      source: Int!
                    }

                    type Container {
                      value: Int!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "target")
                    val first = schema.requireObjectField("Query", "first")
                    val second = schema.requireObjectField("Query", "second")
                    val source = schema.requireObjectField("Query", "source")
                    val sourceQuery =
                        schema.fragmentFrom("fragment SourceQuery on Query { value: source }")
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "value" setTo
                                        RootFieldReferenceData.of(listOf(target), emptyMap())
                                }
                            },
                        target to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment =
                                    schema.fragmentFrom(
                                        "fragment TargetQuery on Query { first second }",
                                    ),
                            ) { _, queryValue, _ ->
                                queryValue.outputValue("first") as Int +
                                    queryValue.outputValue("second") as Int
                            },
                        first to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = sourceQuery,
                            ) { _, queryValue, _ -> queryValue.outputValue("value") },
                        second to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = sourceQuery,
                            ) { _, queryValue, _ -> queryValue.outputValue("value") },
                        source to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                sourceApplications.incrementAndGet()
                                3
                            },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveAndValidate(world, "query { container { value } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).get(),
            )

        assertEquals(6, container.getCell(world.schema.contractKey("Container", "value")).get())
        assertEquals(1, sourceApplications.get())
    }
}

/** Reference targets inherit the selective tier's complete successor demand. */
interface SelectiveRootFieldReferenceResolverContract : ResolverContract {
    @Test
    fun `supplies successor demand to a selective referenced target`() {
        var suppliedFields: Set<String>? = null
        val testWorld =
            TestWorld.fromSDL(
                selectiveResolvers = true,
                schemaSDL =
                    """
                    type Query {
                      container: Container!
                      target: Product!
                    }

                    type Container {
                      product: Product!
                    }

                    type Product {
                      base: String!
                      computed: String!
                    }
                    """.trimIndent(),
                fieldResolvers = { schema ->
                    val container = schema.requireObjectField("Query", "container")
                    val target = schema.requireObjectField("Query", "target")
                    val computed = schema.requireObjectField("Product", "computed")
                    val productType = schema.requireType("Product") as ViaductSchema.Object
                    mapOf(
                        container to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ ->
                                schema.objectOf("Container") {
                                    "product" setTo
                                        RootFieldReferenceData.of(listOf(target), emptyMap())
                                }
                            },
                        target to
                            selectiveFieldResolverOf(schema.emptyFragmentOf("Query")) {
                                    _,
                                    _,
                                    demand,
                                ->
                                suppliedFields =
                                    demand
                                        .merge(productType)
                                        .groundKeys()
                                        .mapTo(linkedSetOf()) { key -> key.field.name }
                                schema.objectOf("Product") { "base" setTo "input" }
                            },
                        computed to
                            fieldResolverOf(
                                schema.fragmentFrom("fragment ComputedInput on Product { base }"),
                            ) { input, _ -> "computed-${input.outputValue("base")}" },
                    )
                },
            )
        val world = testWorld.assumptions

        val result = resolveAndValidateObserved(world, "query { container { product { computed } } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.result.getCell(world.schema.contractKey("Query", "container")).get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "product")).get(),
            )

        assertEquals("computed-input", product.getCell(world.schema.contractKey("Product", "computed")).get())
        assertEquals(setOf("base", "computed"), suppliedFields)
    }
}
