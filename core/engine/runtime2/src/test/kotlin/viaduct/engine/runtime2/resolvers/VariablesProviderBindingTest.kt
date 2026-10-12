package viaduct.engine.runtime2.resolvers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import viaduct.engine.runtime2.correctresolution.CorrectnessResolverObserver
import viaduct.engine.runtime2.correctresolution.conformsToResolvers
import viaduct.engine.runtime2.model.EngineErrorData
import viaduct.engine.runtime2.model.ErrorEngineResult
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.RootFieldReferenceData
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.emptyFragmentOf
import viaduct.engine.runtime2.model.registry.VariablesProviderFunction
import viaduct.engine.runtime2.model.registry.fieldResolverOf
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.selectionForestOf
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.RootFieldReferenceInvocationObservation
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext

class VariablesProviderBindingTest {
    @Test
    fun `a repeated occurrence is rejected before calling its provider again`() {
        var calls = 0
        val fixture = providerWorld {
            calls++
            mapOf("provided" to 7)
        }
        val operation = SharedOperationContext.create(fixture.assumptions)
        val root = ObjectEngineResult.of(fixture.schema.requireQueryTypeDef())
        val key = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "result"), emptyMap())

        listOf(key).bindResolverVariables(operation, root, emptyList())
        assertFailsWith<IllegalStateException> { listOf(key).bindResolverVariables(operation, root, emptyList()) }

        assertEquals(1, calls)
    }

    @Test
    fun `callback local cancellation completes provider bindings as errors`() {
        val fixture = providerWorld { throw CancellationException("cancel provider") }
        val operation = SharedOperationContext.create(fixture.assumptions)
        val root = ObjectEngineResult.of(fixture.schema.requireQueryTypeDef())
        val key = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "result"), emptyMap())

        listOf(key).bindResolverVariables(operation, root, emptyList())

        val definition = fixture.resolverRegistry.resolver(key.field).instantiatedVariableDefinitions(ResolverOccurrenceId.at(root, listOf(key))).single()
        assertEquals(VariableBinding.Error, operation.variableBindings.getBinding(requireNotNull(definition.variable.instanceId)))
    }

    @Test
    fun `a cancelled request scope prevents provider entry`() {
        var calls = 0
        val fixture = providerWorld {
            calls++
            mapOf("provided" to 7)
        }
        val job = Job().also { it.cancel() }
        val operation = object : GroundedVariablesProviderContext, SharedOperationContext<Nothing> by SharedOperationContext.create(fixture.assumptions) {
            override val variablesProviderContext = job
        }
        val root = ObjectEngineResult.of(fixture.schema.requireQueryTypeDef())
        val key = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "result"), emptyMap())

        assertFailsWith<CancellationException> { listOf(key).bindResolverVariables(operation, root, emptyList()) }

        assertEquals(0, calls)
    }

    @Test
    fun `correctness replay rejects forged provider failure bindings`() {
        val fixture = providerWorld { mapOf("provided" to 7) }
        val key = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "result"), emptyMap())
        val root = ObjectEngineResult.of(fixture.schema.requireQueryTypeDef(), mapOf(key to ErrorEngineResult.of(EngineErrorData.of())))
        val operation = SharedOperationContext.create(fixture.assumptions)
        val definition = fixture.resolverRegistry.resolver(key.field).instantiatedVariableDefinitions(ResolverOccurrenceId.at(root, listOf(key))).single()
        operation.variableBindings.bindVariable(requireNotNull(definition.variable.instanceId), VariableBinding.Error)

        assertFalse(root.conformsToResolvers(operation))
    }

    @Test
    fun `correctness replay rejects forged reference provider failure bindings`() {
        val fixture = referenceProviderWorld { mapOf("provided" to 7, "other" to 8) }
        val (root, operation) = failedReferenceResult(fixture, listOf(VariableBinding.Error, VariableBinding.Error))

        assertFalse(root.conformsToResolvers(operation))
    }

    @Test
    fun `reference provider failure replay requires the complete error binding set`() {
        val fixture = referenceProviderWorld { error("provider failed") }
        listOf(null, VariableBinding.of(8)).forEach { otherBinding ->
            val (root, operation) = failedReferenceResult(fixture, listOf(VariableBinding.Error, otherBinding))

            assertFalse(root.conformsToResolvers(operation))
        }
        val (root, operation) = failedReferenceResult(fixture, listOf(VariableBinding.Error, VariableBinding.Error))

        assertTrue(root.conformsToResolvers(operation))
    }

    private fun failedReferenceResult(
        fixture: TestWorld,
        providerBindings: List<VariableBinding?>,
    ): Pair<ObjectEngineResult, SharedOperationContext<*>> {
        val referenceKey = ObjectEngineResult.GroundKey.of(fixture.schema.requireObjectField("Query", "reference"), emptyMap())
        val root = ObjectEngineResult.of(fixture.schema.requireQueryTypeDef(), mapOf(referenceKey to ErrorEngineResult.of(EngineErrorData.of())))
        val invocationRoot = ObjectEngineResult.of(fixture.schema.requireQueryTypeDef())
        val target = fixture.schema.requireObjectField("Query", "result")
        val invocationKey = ObjectEngineResult.GroundKey.of(target, emptyMap())
        val observer = CorrectnessResolverObserver()
        observer.onRootFieldReferenceInvocation(
            RootFieldReferenceInvocationObservation(
                publicationRoot = root,
                publicationPath = listOf(referenceKey),
                reference = RootFieldReferenceData.of(listOf(target), emptyMap()),
                invocationRoot = invocationRoot,
                invocationPath = listOf(invocationKey),
                invocationKey = invocationKey,
                suppliedDemand = selectionForestOf(),
            ),
        )
        val operation = SharedOperationContext.create(fixture.assumptions, resolverObserver = observer)
        fixture.resolverRegistry.resolver(target)
            .instantiatedVariableDefinitions(ResolverOccurrenceId.at(invocationRoot, listOf(invocationKey)))
            .zip(providerBindings)
            .forEach { (definition, binding) ->
                if (binding != null) operation.variableBindings.bindVariable(requireNotNull(definition.variable.instanceId), binding)
            }
        return root to operation
    }

    private fun referenceProviderWorld(provider: VariablesProviderFunction) =
        TestWorld.fromSDL(
            schemaSDL = "type Query { reference: Int, result: Int, echo(value: Int): Int }",
            fieldResolvers = { schemas ->
                val schema = schemas.loweredSchema
                val target = schema.requireObjectField("Query", "result")
                mapOf(
                    target to
                        fieldResolverOf(
                            objectFragment = schema.emptyFragmentOf("Query"),
                            queryFragment = schemas.fragmentFrom("fragment Input on Query { one: echo(value: ${'$'}provided) two: echo(value: ${'$'}other) }"),
                        ) { _, _, _ -> 7 }.withVariablesProvider(setOf("provided", "other"), provider),
                    schema.requireObjectField("Query", "reference") to
                        fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> RootFieldReferenceData.of(listOf(target), emptyMap()) },
                )
            },
        )

    private fun providerWorld(provider: VariablesProviderFunction) =
        TestWorld.fromSDL(
            schemaSDL = "type Query { result: Int, echo(value: Int): Int }",
            fieldResolvers = { schemas ->
                mapOf(
                    schemas.loweredSchema.requireObjectField("Query", "result") to
                        fieldResolverOf(
                            objectFragment = schemas.loweredSchema.emptyFragmentOf("Query"),
                            queryFragment = schemas.fragmentFrom("fragment Input on Query { echo(value: ${'$'}provided) }"),
                        ) { _, _, _ -> 7 }.withVariablesProvider(setOf("provided"), provider),
                )
            },
        )
}
