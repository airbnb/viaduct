@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.resolution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import viaduct.engine.runtime2.contract.contractKey
import viaduct.engine.runtime2.contract.registeredResolverOccurrenceApplicationIdentityCounts
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.correctResolution
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.Fragment
import viaduct.engine.runtime2.model.ListEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.emptyFragmentOf
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolution.framework.OEROccurrence
import viaduct.engine.runtime2.resolution.framework.ResolverInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class RootFieldReferenceResolutionTest : ResolutionDispatcherResource {
    @Test
    fun `invalid reference provider names retain their cause and reference witness`() {
        val fixture =
            TestWorld.fromSDL(
                schemaSDL = "type Query { reference: Int, result: Int, echo(value: Int): Int }",
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    val target = schema.requireObjectField("Query", "result")
                    mapOf(
                        target to
                            fieldResolverOf(
                                objectFragment = schema.emptyFragmentOf("Query"),
                                queryFragment = schemas.fragmentFrom("fragment Input on Query { echo(value: ${'$'}provided) }"),
                            ) { _, _, _ -> 7 }.withVariablesProvider(setOf("provided")) { emptyMap() },
                        schema.requireObjectField("Query", "reference") to
                            fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) },
                    )
                },
            )
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(fixture.assumptions, resolutionObserver = observer)
        val selections = fixture.schemas.fragmentFrom("fragment Result on Query { reference }").subselections

        val result = operation.resolveWithTestDispatcher(selections)

        val error = assertIs<ErrorEngineResult>(result.getCell(fixture.schema.contractKey("Query", "reference")).value.get())
        val cause = assertIs<IllegalStateException>(error.errorData.cause)
        assertEquals("VariablesProvider returned invalid variables. Missing keys: provided", cause.message)
        val hop = observer.rootFieldReferenceInvocations().single()
        assertFalse(ResolverOccurrenceId.at(hop.invocationRoot, hop.invocationPath) in observer.invokedResolverOccurrences())
        assertTrue(result.correctResolution(operation, selections.merge(fixture.schema.requireQueryTypeDef())))
    }

    @Test
    fun `conditioned passive reference list preserves its independent checker writer`() {
        for (enabled in listOf(false, true)) {
            val resolution = resolveConditionalPassiveListReference(enabled, withChecker = true)
            assertEquals(if (enabled) 1 else 0, resolution.checkerApplications.get())
            assertEquals(if (enabled) 1 else 0, resolution.targetApplications.get())
        }
    }

    @Test
    fun `list provider awaits root-reference elements`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result: Int! @resolver(
                    of: "numbers echo(values: ${'$'}provided)"
                    variables: [{name: "provided", fromObjectField: ["numbers"]}]
                    script: "objectValue.echo"
                  )
                  numbers: [Int!]! @resolver(script: "[ref('one')]")
                  one: Int! @resolver(script: "7")
                  echo(values: [Int!]!): Int! @resolver(script: "if (size(values) != 1) throw 'Expected exactly one value'; values[0]")
                }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val query = testWorld.schemas.fragmentFrom("fragment Result on Query { result }")
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world, resolutionObserver = observer)

        val resolved = operation.resolveWithTestDispatcher(query.subselections)

        assertEquals(
            7,
            resolved.getCell(world.schema.contractKey("Query", "result")).value.get(),
        )
        assertEquals(1, observer.rootFieldReferenceInvocations().size)
    }

    @Test
    fun `referenced resolver retains Query fragment sourced variables`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'product': ref('product')}")
                  product: Merchandise! @resolver(
                    qf: "providedSource: provided querySide: label(value: ${'$'}provided)"
                    variables: [{name: "provided", fromQueryField: ["providedSource"]}]
                    script: "{'__typename': 'Product', 'value': queryValue.querySide}"
                  )
                  provided: Int! @resolver(script: "7")
                  label(value: Int!): String! @resolver(script: "'value-' + value")
                }
                type Container { product: Merchandise! }
                interface Merchandise { value: String! }
                type Product implements Merchandise { value: String! }
                type Service implements Merchandise { value: String! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result = resolve(testWorld, "fragment Result on Query { container { product { value } } }")
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).value.get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container
                    .getCell(world.schema.contractKey("Container", "product"))
                    .value
                    .get(),
            )

        assertEquals(
            "value-7",
            product.getCell(world.schema.contractKey("Product", "value")).value.get(),
        )
    }

    @Test
    fun `tail reference targets retain argument and provider variables per occurrence`() {
        val firstProviderArguments = CopyOnWriteArrayList<Int>()
        val secondProviderArguments = CopyOnWriteArrayList<Int>()
        val firstQueryValues = CopyOnWriteArrayList<String>()
        val secondQueryValues = CopyOnWriteArrayList<String>()
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'product': ref('first', {'id': 5})}")
                  first(id: Int!): Product! @resolver(
                    qf: "argumentLabel: label(value: ${'$'}argument) providerLabel: label(value: ${'$'}provided)"
                    variables: [{name: "argument", fromArgument: ["id"]}]
                    vars: {names: ["provided"], script: "firstProviderArguments.add(id); {'provided': id + 1}"}
                    script: "firstQueryValues.add(queryValue.argumentLabel + '/' + queryValue.providerLabel); ref('second', {'id': 9})"
                  )
                  second(id: Int!): Product! @resolver(
                    qf: "argumentLabel: label(value: ${'$'}argument) providerLabel: label(value: ${'$'}provided)"
                    variables: [{name: "argument", fromArgument: ["id"]}]
                    vars: {names: ["provided"], script: "secondProviderArguments.add(id); {'provided': id + 1}"}
                    script: ${"\"\"\""}
                      var value = queryValue.argumentLabel + '/' + queryValue.providerLabel;
                      secondQueryValues.add(value);
                      return {'value': value};
                    ${"\"\"\""}
                  )
                  label(value: Int!): String! @resolver(script: "'value-' + value")
                }
                type Container { product: Product! }
                type Product { value: String! }
                """.trimIndent(),
                scriptBindings = mapOf(
                    "firstProviderArguments" to firstProviderArguments,
                    "secondProviderArguments" to secondProviderArguments,
                    "firstQueryValues" to firstQueryValues,
                    "secondQueryValues" to secondQueryValues,
                ),
            )
        val world = testWorld.assumptions
        val query = testWorld.schemas.fragmentFrom("fragment Result on Query { container { product { value } } }")
        val operation =
            SharedOperationContext.create(world, resolutionObserver = CorrectnessResolverObserver())
        val result = operation.resolveWithTestDispatcher(query.subselections)
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).value.get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container
                    .getCell(world.schema.contractKey("Container", "product"))
                    .value
                    .get(),
            )

        assertEquals(
            "value-9/value-10",
            product.getCell(world.schema.contractKey("Product", "value")).value.get(),
        )
        assertEquals(listOf(5), firstProviderArguments)
        assertEquals(listOf(9), secondProviderArguments)
        assertEquals(listOf("value-5/value-6"), firstQueryValues)
        assertEquals(listOf("value-9/value-10"), secondQueryValues)
        assertTrue(
            result.correctResolution(operation, query.subselections.merge(world.schema.requireQueryTypeDef())),
        )
    }

    @Test
    fun `embedded reference invokes namespace root field with query input consumer demand and arguments`() {
        val targetApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'product': ref('catalog.product', {'id': '42'})}")
                  catalog: Catalog! @resolver(script: "{:}")
                  suffix: String! @resolver(script: "'resolved'")
                }
                type Container { product: Product! }
                type Catalog {
                  prefix: String! @resolver(script: "'product'")
                  product(id: ID!): Product! @resolver(
                    isSelective: false
                    qf: "catalog { prefix } suffix"
                    script: "targetApplications.incrementAndGet(); {'value': queryValue.catalog.prefix + '-' + id + '-' + queryValue.suffix, 'omitted': 'must not be materialized'}"
                  )
                }
                type Product { value: String! omitted: String }
                """.trimIndent(),
                scriptBindings = mapOf("targetApplications" to targetApplications),
            )
        val world = testWorld.assumptions
        val query = testWorld.schemas.fragmentFrom("fragment Result on Query { container { product { value } } }")
        val operation =
            SharedOperationContext.create(world, resolutionObserver = CorrectnessResolverObserver())
        val result = operation.resolveWithTestDispatcher(query.subselections)
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).value.get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container.getCell(world.schema.contractKey("Container", "product")).value.get(),
            )

        assertEquals(
            "product-42-resolved",
            product.getCell(world.schema.contractKey("Product", "value")).value.get(),
        )
        assertEquals(setOf(world.schema.contractKey("Product", "value")), product.keys)
        assertEquals(1, targetApplications.get())
        assertEquals(
            true,
            result.correctResolution(operation, query.subselections.merge(world.schema.requireQueryTypeDef())),
        )
    }

    @Test
    fun `embedded reference reaches its target through three namespace fields`() {
        val applications = CopyOnWriteArrayList<ResolverInvocationObservation>()
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'product': ref('factories.commerce.products.create', {'id': '42'})}")
                  factories: Factories @resolver(script: "{:}")
                }
                type Container { product: Product! }
                type Factories { commerce: CommerceFactories @resolver(script: "{:}") }
                type CommerceFactories { products: ProductFactory @resolver(script: "{:}") }
                type ProductFactory {
                  create(id: ID!): Product! @resolver(script: "{'value': 'product-' + id}")
                }
                type Product { value: String! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions

        val query = testWorld.schemas.fragmentFrom("fragment Result on Query { container { product { value } } }")

        val recordingObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications.add(observation)
            }
        }
        val operation =
            SharedOperationContext.create(world, resolutionObserver = recordingObserver)
        val result =
            operation.resolveWithTestDispatcher(query.subselections)
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).value.get(),
            )
        val product =
            assertIs<ObjectEngineResult>(
                container
                    .getCell(world.schema.contractKey("Container", "product"))
                    .value
                    .get(),
            )
        val create = world.schema.requireObjectField("ProductFactory", "create")
        val targetApplication = applications.single { it.field == create }

        assertEquals(
            "product-42",
            product.getCell(world.schema.contractKey("Product", "value")).value.get(),
        )
        assertEquals(
            listOf("factories", "commerce", "products", "create"),
            targetApplication.occurrencePath.map { component ->
                assertIs<ObjectEngineResult.ObjectKey>(component).field.name
            },
        )
        assertEquals(
            listOf("container", "create"),
            applications.map { it.field.name },
        )
        assertTrue(
            result.correctResolution(operation, query.subselections.merge(world.schema.requireQueryTypeDef())),
        )
    }

    @Test
    fun `direct resolver result tail resolves every hop with a fresh occurrence root`() {
        val applications = CopyOnWriteArrayList<ResolverInvocationObservation>()
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  first: Product! @resolver(script: "ref('second')")
                  second: Product! @resolver(script: "ref('third')")
                  third: Product! @resolver(script: "{'value': 'done'}")
                }
                type Product { value: String! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions

        val query = testWorld.schemas.fragmentFrom("fragment Result on Query { first { value } }")

        val recordingObserver = object : CorrectnessResolverObserver() {
            override val onResolverInvocation: (ResolverInvocationObservation) -> Unit = { observation ->
                super.onResolverInvocation(observation)
                applications.add(observation)
            }
        }
        val operation =
            SharedOperationContext.create(world, resolutionObserver = recordingObserver)
        val result =
            operation.resolveWithTestDispatcher(query.subselections)
        val product =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "first")).value.get(),
            )

        assertEquals(
            "done",
            product.getCell(world.schema.contractKey("Product", "value")).value.get(),
        )
        assertEquals(listOf("first", "second", "third"), applications.map { it.field.name })
        assertNotEquals(applications[0].resolverOccurrenceId, applications[1].resolverOccurrenceId)
        assertNotEquals(applications[1].resolverOccurrenceId, applications[2].resolverOccurrenceId)
        assertEquals(
            true,
            result.correctResolution(operation, query.subselections.merge(world.schema.requireQueryTypeDef())),
        )
        assertEquals(
            3,
            result.registeredResolverOccurrenceApplicationIdentityCounts(operation).values.sum(),
        )
    }

    @Test
    fun `static resolver may passively override a registered list field with mixed references and values`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'products': [ref('product', {'id': 1}), {'value': 2}, ref('product', {'id': 3})]}")
                  product(id: Int!): Product! @resolver(script: "{'value': id}")
                }
                type Container {
                  products: [Product!]! @resolver(script: "throw 'passively overridden list resolver must not run'")
                }
                type Product { value: Int! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val result =
            resolve(
                testWorld,
                "fragment Result on Query { container { products { value } } }",
            )
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).value.get(),
            )
        val products =
            assertIs<ListEngineResult>(
                container.getCell(world.schema.contractKey("Container", "products")).value.get(),
            )

        assertEquals(
            listOf(1, 2, 3),
            products.map { cell ->
                val product = assertIs<ObjectEngineResult>(cell.value.get())
                product.getCell(world.schema.contractKey("Product", "value")).value.get()
            },
        )
    }

    @Test
    fun `failing list-element reference preserves passive and successful siblings`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'products': [ref('product', {'id': 1}), ref('product', {'id': 2}), {'value': 3}]}")
                  product(id: Int!): Product! @resolver(script: "id == 2 ? error : {'value': id}")
                }
                type Container {
                  products: [Product!]! @resolver(script: "throw 'passively overridden list resolver must not run'")
                }
                type Product { value: Int! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val query =
            testWorld.schemas.fragmentFrom("fragment Result on Query { container { products { value } } }")
        val operation =
            SharedOperationContext.create(world, resolutionObserver = CorrectnessResolverObserver())
        val result = operation.resolveWithTestDispatcher(query.subselections)
        val container =
            assertIs<ObjectEngineResult>(
                result.getCell(world.schema.contractKey("Query", "container")).value.get(),
            )
        val products =
            assertIs<ListEngineResult>(
                container.getCell(world.schema.contractKey("Container", "products")).value.get(),
            )
        val first = assertIs<ObjectEngineResult>(products[0].value.get())
        val third = assertIs<ObjectEngineResult>(products[2].value.get())

        assertEquals(
            1,
            first.getCell(world.schema.contractKey("Product", "value")).value.get(),
        )
        assertIs<ErrorEngineResult>(products[1].value.get())
        assertEquals(
            3,
            third.getCell(world.schema.contractKey("Product", "value")).value.get(),
        )
        assertTrue(
            result.correctResolution(operation, query.subselections.merge(world.schema.requireQueryTypeDef())),
        )
    }

    @Test
    fun `task-local cancellation becomes a list-element field error and preserves siblings`(): Unit =
        runBlocking {
            listOf(1, 4).forEach { threadCount ->
                ResolutionDispatcherFactory.create(threadCount).use { dispatcher ->
                    val successfulReferenceCompleted = CompletableDeferred<Unit>()
                    val testWorld =
                        listReferenceFailureWorld(
                            fatal = false,
                            successfulReferenceCompleted = successfulReferenceCompleted,
                        )
                    val world = testWorld.assumptions
                    val requestJob = Job()
                    val requestScope = CoroutineScope(dispatcher + requestJob)

                    try {
                        val root =
                            SharedOperationContext.create(world).startResolve(
                                testWorld.schemas.fragmentFrom(
                                    "fragment Result on Query { container { numbers } }",
                                ).subselections,
                                requestScope,
                            )
                        val numbers = root.awaitReferenceNumbers(world)
                        withTimeout(5_000) { successfulReferenceCompleted.await() }

                        assertEquals(1, withTimeout(5_000) { numbers[0].value.await() })
                        val error =
                            assertIs<ErrorEngineResult>(
                                withTimeout(5_000) { numbers[1].value.await() },
                            )
                        assertEquals(
                            "list reference cancelled",
                            assertIs<CancellationException>(error.errorData.cause).message,
                        )
                        assertEquals(3, withTimeout(5_000) { numbers[2].value.await() })
                        assertTrue(requestJob.isActive, "threadCount=$threadCount")
                    } finally {
                        requestJob.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun `list-element exception becomes an error value and preserves siblings`(): Unit =
        runBlocking {
            listOf(1, 4).forEach { threadCount ->
                ResolutionDispatcherFactory.create(threadCount).use { dispatcher ->
                    val successfulReferenceCompleted = CompletableDeferred<Unit>()
                    val testWorld =
                        listReferenceFailureWorld(
                            fatal = true,
                            successfulReferenceCompleted = successfulReferenceCompleted,
                        )
                    val world = testWorld.assumptions
                    val requestJob = Job()
                    val requestScope = CoroutineScope(dispatcher + requestJob)

                    try {
                        val root =
                            SharedOperationContext.create(world).startResolve(
                                testWorld.schemas.fragmentFrom(
                                    "fragment Result on Query { container { numbers } }",
                                ).subselections,
                                requestScope,
                            )
                        val numbers = root.awaitReferenceNumbers(world)
                        withTimeout(5_000) { successfulReferenceCompleted.await() }
                        assertEquals(1, withTimeout(5_000) { numbers[0].value.await() })
                        val error =
                            assertIs<ErrorEngineResult>(
                                withTimeout(5_000) { numbers[1].value.await() },
                            )
                        assertTrue(
                            assertIs<IllegalStateException>(error.errorData.cause)
                                .message
                                .orEmpty()
                                .contains(
                                    "VariablesProvider returned invalid variables. " +
                                        "Missing keys: ready",
                                ),
                            "threadCount=$threadCount",
                        )
                        assertEquals(3, withTimeout(5_000) { numbers[2].value.await() })
                        assertTrue(requestJob.isActive, "threadCount=$threadCount")
                    } finally {
                        requestJob.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun `argument condition activates an embedded root-field-reference occurrence`() {
        listOf(false, true).forEach { enabled ->
            val targetApplications = AtomicInteger()
            val testWorld =
                TestWorld.fromDSL(
                    """
                    extend type Query {
                      result(enabled: Boolean!): Int! @resolver(
                        of: "container { product @include(if: ${'$'}enabled) { value } }"
                        script: "1"
                      )
                      container: Container! @resolver(script: "{'product': ref('product')}")
                      product: Merchandise! @resolver(script: "targetApplications.incrementAndGet(); {'__typename': 'Product', 'value': 'target'}")
                    }
                    type Container {
                      product: Merchandise! @resolver(script: "throw 'passively overridden consumer resolver must not run'")
                    }
                    interface Merchandise { value: String! }
                    type Product implements Merchandise { value: String! }
                    type Service implements Merchandise { value: String! }
                    """.trimIndent(),
                    scriptBindings = mapOf("targetApplications" to targetApplications),
                )
            val world = testWorld.assumptions
            val query =
                testWorld.schemas.fragmentFrom(
                    "fragment Result on Query { result(enabled: $enabled) }",
                )
            val operation =
                SharedOperationContext.create(world, resolutionObserver = CorrectnessResolverObserver())
            val resolved = operation.resolveWithTestDispatcher(query.subselections)
            val container =
                assertIs<ObjectEngineResult>(
                    resolved.getCell(world.schema.contractKey("Query", "container")).value.get(),
                )
            val referenceCell =
                container.getCell(world.schema.contractKey("Container", "product"))

            assertEquals(if (enabled) 1 else 0, targetApplications.get())
            if (enabled) {
                assertTrue(runBlocking { referenceCell.fetchActivated() })
            } else {
                assertFalse(runBlocking { referenceCell.fetchActivated() })
            }
            assertTrue(
                resolved.correctResolution(
                    operation,
                    query.subselections.merge(world.schema.requireQueryTypeDef()),
                ),
            )
        }
    }

    @Test
    fun `excluded passive list does not invoke its root-field-reference elements`() {
        val resolution = resolveConditionalPassiveListReference(enabled = false)

        assertEquals(0, resolution.targetApplications.get())
    }

    @Test
    fun `included passive list invokes its root-field-reference elements`() {
        val resolution = resolveConditionalPassiveListReference(enabled = true)

        assertEquals(1, resolution.targetApplications.get())
    }

    @Test
    fun `application-count oracle excludes references beneath an excluded passive list`() {
        val resolution = resolveConditionalPassiveListReference(enabled = false)
        val expectedApplications =
            resolution.result.registeredResolverOccurrenceApplicationIdentityCounts(resolution.operation)

        assertEquals(
            emptySet(),
            expectedApplications.keys
                .mapTo(linkedSetOf()) { identity ->
                    identity.applicationIdentity.key.field
                }.filterTo(linkedSetOf()) { field -> field.fieldName == "product" },
        )
    }

    @Test
    fun `application-count oracle rejects an unowned root-reference observation`() {
        val resolution = resolveConditionalPassiveListReference(enabled = true)
        val observation =
            assertIs<CorrectnessResolverObserver>(resolution.operation.resolutionObserver)
                .rootFieldReferenceInvocations()
                .single()
        val malformedObserver = CorrectnessResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(observation)
        malformedObserver.onRootFieldReferenceInvocation(
            observation.copy(
                invocationRoot =
                    ObjectEngineResult.of(resolution.operation.world.schema.requireQueryTypeDef()),
            )
        )
        val validationOperation =
            SharedOperationContext.create(
                world = resolution.operation.world,
                variableBindings = resolution.operation.variableBindings,
                resolutionObserver = malformedObserver,
            )
        val expectedApplications =
            resolution.result.registeredResolverOccurrenceApplicationIdentityCounts(validationOperation)

        assertEquals(
            1,
            expectedApplications
                .filterKeys { identity ->
                    identity.applicationIdentity.key.field.fieldName == "product"
                }.values.sum(),
        )
    }

    @Test
    fun `error-valued resolver occurrence owns references in its query fragment`() {
        val queryFragmentSource =
            "fragment ConsumerQuery on Query { container { product { value } } }"
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  consumer(value: Int!): Int! @resolver(
                    qf: "container { product { value } }"
                    script: "throw 'error-valued consumer must not run'"
                  )
                  container: Container! @resolver(script: "{'product': ref('product')}")
                  product: Product! @resolver(script: "{'value': 'target'}")
                }
                type Container { product: Product! }
                type Product { value: String! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world, resolutionObserver = observer)
        val queryFragment = testWorld.schemas.fragmentFrom(queryFragmentSource)
        val queryResult = operation.resolveWithTestDispatcher(queryFragment.subselections)
        assertEquals(1, observer.rootFieldReferenceInvocations().size)

        val primaryRoot =
            ObjectEngineResult.of(
                operation.world.schema.requireQueryTypeDef(),
                mutable = true,
            )
        val consumer = operation.world.schema.requireObjectField("Query", "consumer")
        val variable =
            Arguments.Variable.of(consumer, "provided").instantiate(
                ResolverOccurrenceId.at(primaryRoot, emptyList()),
            )
        operation.variableBindings.bindVariable(
            requireNotNull(variable.instanceId),
            VariableBinding.Error,
        )
        val consumerKey =
            ObjectEngineResult.ObjectKey.of(
                consumer,
                Arguments.of(consumer, mapOf("value" to variable)),
            )
        primaryRoot.setCellValue(consumerKey, ErrorEngineResult.of(EngineErrorData.of()))
        val owner = ResolverOccurrenceId.at(primaryRoot, listOf(consumerKey))
        val owningOccurrence = OEROccurrence(primaryRoot, emptyList(), primaryRoot)
        observer.onQueryFragmentPrepared(owner, queryResult, owningOccurrence)
        observer.onQueryFragmentOwnerAddress(owner, owningOccurrence, consumerKey)
        val selections = selectionForestOf().merge(operation.world.schema.requireQueryTypeDef())

        assertTrue(primaryRoot.correctResolution(operation, selections))
        assertEquals(
            1,
            primaryRoot.registeredResolverOccurrenceApplicationIdentityCounts(operation).filterKeys { identity ->
                identity.applicationIdentity.key.field.fieldName == "product"
            }.values.sum(),
        )
    }

    @Test
    fun `correctness replays a root reference with canonical rather than observed demand`() {
        val resolution = resolveConditionalPassiveListReference(enabled = true)
        val observation =
            assertIs<CorrectnessResolverObserver>(resolution.operation.resolutionObserver)
                .rootFieldReferenceInvocations()
                .single()
        val malformedObserver = CorrectnessResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(observation.copy(suppliedDemand = selectionForestOf()))
        val validationOperation =
            SharedOperationContext.create(
                world = resolution.operation.world,
                variableBindings = resolution.operation.variableBindings,
                resolutionObserver = malformedObserver,
            )
        val query = resolution.query

        assertTrue(
            resolution.result.correctResolution(
                validationOperation,
                query.subselections.merge(validationOperation.world.schema.requireQueryTypeDef()),
            ),
        )
    }

    @Test
    fun `correctness rejects a root-reference witness with a noncanonical invocation path`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'product': ref('product')}")
                  product: Product! @resolver(script: "{'value': 'target'}")
                }
                type Container { product: Product! }
                type Product { value: String! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val query = testWorld.schemas.fragmentFrom("fragment Result on Query { container { product { value } } }")
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world, resolutionObserver = observer)
        val result = operation.resolveWithTestDispatcher(query.subselections)
        val observation = observer.rootFieldReferenceInvocations().single()
        val malformedObserver = CorrectnessResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(
            observation.copy(
                invocationPath =
                    listOf(ListEngineResult.Index.of(0)) + observation.invocationPath,
            )
        )
        val validationOperation =
            SharedOperationContext.create(
                world = world,
                variableBindings = operation.variableBindings,
                resolutionObserver = malformedObserver,
            )

        assertFalse(
            result.correctResolution(
                validationOperation,
                query.subselections.merge(world.schema.requireQueryTypeDef()),
            ),
        )
    }

    @Test
    fun `correctness rejects sibling root references that reuse one invocation root`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "var reference = ref('product'); {'first': reference, 'second': reference}")
                  product: Product! @resolver(script: "{'value': 'target'}")
                }
                type Container { first: Product! second: Product! }
                type Product { value: String! }
                """.trimIndent(),
            )
        val world = testWorld.assumptions
        val query =
            testWorld.schemas.fragmentFrom(
                "fragment Result on Query { container { first { value } second { value } } }",
            )
        val observer = CorrectnessResolverObserver()
        val operation = SharedOperationContext.create(world, resolutionObserver = observer)
        val result = operation.resolveWithTestDispatcher(query.subselections)
        val observations = observer.rootFieldReferenceInvocations()
        assertEquals(2, observations.size)
        val malformedObserver = CorrectnessResolverObserver()
        malformedObserver.onRootFieldReferenceInvocation(observations[0])
        malformedObserver.onRootFieldReferenceInvocation(
            observations[1].copy(
                invocationRoot = observations[0].invocationRoot,
                invocationPath = observations[0].invocationPath,
                invocationKey = observations[0].invocationKey,
            )
        )
        val validationOperation =
            SharedOperationContext.create(
                world = world,
                variableBindings = operation.variableBindings,
                resolutionObserver = malformedObserver,
            )

        assertFalse(
            result.correctResolution(
                validationOperation,
                query.subselections.merge(world.schema.requireQueryTypeDef()),
            ),
        )
    }

    private fun resolveConditionalPassiveListReference(
        enabled: Boolean,
        withChecker: Boolean = false,
    ): ConditionalPassiveListResolution {
        val targetApplications = AtomicInteger()
        val checkerApplications = AtomicInteger()
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  result(enabled: Boolean!): Int! @resolver(
                    of: "container { products @include(if: ${'$'}enabled) { value } }"
                    script: "1"
                  )
                  container: Container! @resolver(script: "{'products': [ref('product')]}")
                  product: Product! @resolver(script: "targetApplications.incrementAndGet(); {'value': 'target'}")
                }
                type Container {
                  products: [Product!]! ${if (withChecker) "@checker(script: \"checkerApplications.incrementAndGet(); allow\")" else ""}
                }
                type Product { value: String! }
                """.trimIndent(),
                scriptBindings = mapOf(
                    "targetApplications" to targetApplications,
                    "checkerApplications" to checkerApplications,
                ),
            )
        val world = testWorld.assumptions
        val query = testWorld.schemas.fragmentFrom("fragment Result on Query { result(enabled: $enabled) }")
        val operation = SharedOperationContext.create(world, resolutionObserver = CorrectnessResolverObserver())
        val result = operation.resolveWithTestDispatcher(query.subselections)
        return ConditionalPassiveListResolution(result, operation, targetApplications, checkerApplications, query)
    }

    private fun listReferenceFailureWorld(
        fatal: Boolean,
        successfulReferenceCompleted: CompletableDeferred<Unit>,
    ): TestWorld =
        TestWorld.fromSDL(
            schemaSDL =
                """
                type Query {
                  container: Container!
                  number(id: Int!): Int!
                  dependency: Int!
                }

                type Container {
                  numbers: [Int!]!
                }
                """.trimIndent(),
            fieldResolvers = { schema ->
                val container = schema.loweredSchema.requireObjectField("Query", "container")
                val number = schema.loweredSchema.requireObjectField("Query", "number")
                val dependency = schema.loweredSchema.requireObjectField("Query", "dependency")
                mapOf(
                    container to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ ->
                            schema.loweredSchema.objectOf("Container") {
                                "numbers" setTo
                                    listOf(
                                        RootFieldReferenceData.of(
                                            listOf(number),
                                            mapOf("id" to 1),
                                        ),
                                        RootFieldReferenceData.of(
                                            listOf(number),
                                            mapOf("id" to 2),
                                        ),
                                        3,
                                    )
                            }
                        },
                    number to
                        fieldResolverOf(
                            objectFragment = schema.loweredSchema.emptyFragmentOf("Query"),
                            queryFragment =
                                schema.fragmentFrom(
                                    "fragment NumberQuery on Query { " +
                                        "dependency @include(if: ${'$'}ready) }",
                                    variableField = number,
                                ),
                        ) { _, _, arguments ->
                            val id = arguments.fieldValues.getValue("id") as Int
                            if (id == 1) successfulReferenceCompleted.complete(Unit)
                            id
                        }.withVariablesProvider(setOf("ready")) { arguments ->
                            val id = arguments.fieldValues.getValue("id") as Int
                            if (id == 2) {
                                successfulReferenceCompleted.await()
                                if (fatal) {
                                    emptyMap()
                                } else {
                                    throw CancellationException("list reference cancelled")
                                }
                            } else {
                                mapOf("ready" to false)
                            }
                        },
                    dependency to
                        fieldResolverOf(schema.loweredSchema.emptyFragmentOf("Query")) { _, _ -> 0 },
                )
            },
        )

    private suspend fun ObjectEngineResult.awaitReferenceNumbers(world: Assumptions): ListEngineResult {
        val container =
            assertIs<ObjectEngineResult>(
                withTimeout(5_000) {
                    getCell(world.schema.contractKey("Query", "container")).value.await()
                },
            )
        return assertIs(
            withTimeout(5_000) {
                container
                    .getCell(world.schema.contractKey("Container", "numbers"))
                    .value
                    .await()
            },
        )
    }

    @Test
    fun `referenced resolver cannot declare an object fragment`() {
        val testWorld =
            TestWorld.fromDSL(
                """
                extend type Query {
                  container: Container! @resolver(script: "{'product': ref('product')}")
                  product: Product! @resolver(of: "prefix", script: "{'value': 'unreachable'}")
                  prefix: String! @resolver(script: "'prefix'")
                }
                type Container { product: Product! }
                type Product { value: String! }
                """.trimIndent(),
            )

        val result =
            resolve(
                testWorld,
                "fragment Result on Query { container { product { value } } }",
            )
        val container =
            assertIs<ObjectEngineResult>(
                result
                    .getCell(testWorld.schema.contractKey("Query", "container"))
                    .value
                    .get(),
            )
        val error =
            assertIs<ErrorEngineResult>(
                container
                    .getCell(testWorld.schema.contractKey("Container", "product"))
                    .value
                    .get(),
            )
        val failure = assertIs<IllegalArgumentException>(error.errorData.cause)
        assertEquals(
            "Root-field-reference target Query/product must not declare an object fragment",
            failure.message,
        )
    }

    private fun resolve(
        testWorld: TestWorld,
        query: String,
    ): ObjectEngineResult {
        val fragment = testWorld.schemas.fragmentFrom(query)
        return SharedOperationContext.create(testWorld.assumptions).resolveWithTestDispatcher(fragment.subselections)
    }

    @Test
    fun `list-element reference target that calls ctx_query completes without error`() {
        val tasks = ConcurrentHashMap<String, FieldResolverTask>()
        val testWorld =
            TestWorld.fromSDL(
                schemaSDL = """
                    type Query {
                      numbers: [Int]
                      target: Int
                      dependency: Int
                    }
                """.trimIndent(),
                fieldResolvers = { schemas ->
                    val schema = schemas.loweredSchema
                    val empty = schema.emptyFragmentOf("Query")
                    val target = schema.requireObjectField("Query", "target")
                    val nestedFragment = schemas.fragmentFrom("fragment Nested on Query { dependency }")
                    mapOf(
                        schema.requireObjectField("Query", "numbers") to
                            fieldResolverOf(empty) { _, _, ctx ->
                                tasks["numbers"] = assertIs<FieldResolverTask>(ctx)
                                listOf(RootFieldReferenceData.of(listOf(target), emptyMap()))
                            },
                        target to
                            fieldResolverOf(empty) { _, _, executionContext ->
                                tasks["target"] = assertIs<FieldResolverTask>(executionContext)
                                executionContext.resolveSelectionSet(nestedFragment.materializeSelections).get("dependency")
                            },
                        schema.requireObjectField("Query", "dependency") to
                            fieldResolverOf(empty) { _, _, ctx ->
                                tasks["dependency"] = assertIs<FieldResolverTask>(ctx)
                                7
                            },
                    )
                },
            )
        val operation = SharedOperationContext.create(testWorld.assumptions)
        val result = operation.resolveWithTestDispatcher(
            testWorld.schemas.fragmentFrom("fragment Result on Query { numbers }").subselections,
        )
        val numbers =
            assertIs<ListEngineResult>(
                result.getCell(testWorld.schema.contractKey("Query", "numbers")).value.get(),
            )
        assertEquals(7, numbers[0].value.get())
        val producer = tasks.getValue("numbers")
        val reference = tasks.getValue("target")
        val nested = assertIs<OrchestrationTask>(tasks.getValue("dependency").creator)
        assertSame(producer, reference.creator)
        assertSame(reference, nested.creator)
        tasks.values.forEach { assertSame(it, it.publication.task) }
    }

    private data class ConditionalPassiveListResolution(
        val result: ObjectEngineResult,
        val operation: SharedOperationContext<*>,
        val targetApplications: AtomicInteger,
        val checkerApplications: AtomicInteger,
        val query: Fragment,
    )
}
