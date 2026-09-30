package semantics.correctresolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import model.Arguments
import model.ObjectEngineResult
import model.Promise
import model.ResolverOccurrenceId
import model.VariableBinding
import model.emptyFragmentOf
import model.fragmentFrom
import model.materializeSelectionForestOf
import model.merge
import model.operationSelectionsFrom
import model.registry.CheckerResolverBase
import model.registry.FieldCheckerResolver
import model.registry.ResolverFragmentTemplates
import model.registry.ResolverTarget
import model.registry.TypeCheckerResolver
import model.registry.VariableDefinition
import model.requireObjectField
import model.requireQueryTypeDef
import model.testing.TestWorld
import model.testing.fieldResolverOf
import semantics.shared.SharedOperationContext
import semantics.shared.VariableBindingsState
import viaduct.engine.api.CheckerResult
import viaduct.graphql.schema.ViaductSchema

/** Forged bindings must not become assumptions of the checker relation being judged. */
class CheckerProviderCorrectResolutionTest {
    @Test
    fun `type checker replay rejects forged provider values in either named input`() = checkProviderBindings(typeChecker = true)

    @Test
    fun `field checker replay rejects forged provider values in either named input`() = checkProviderBindings(typeChecker = false)

    private fun checkProviderBindings(typeChecker: Boolean) {
        val providerReplays = mutableMapOf<String, Int>()

        fun templates(
            schema: ViaductSchema,
            target: ResolverTarget,
        ): Map<String, ResolverFragmentTemplates> =
            listOf("object", "query").associateWith { name ->
                val input = schema.fragmentFrom(
                    "fragment Input on Query { echo(value: ${'$'}v) }",
                    variableTarget = target,
                ).materializeSelections
                ResolverFragmentTemplates(
                    if (name == "object") input else materializeSelectionForestOf(),
                    if (name == "query") input else materializeSelectionForestOf(),
                    mapOf(Arguments.Variable.of(target, "v") to VariableDefinition.FromProvider),
                    variablesProvider = { arguments ->
                        providerReplays[name] = (providerReplays[name] ?: 0) + 1
                        assertEquals(if (typeChecker) emptyMap() else mapOf("id" to 7), arguments.fieldValues)
                        mapOf("v" to if (name == "object") listOf(7, null) else null)
                    },
                )
            }

        val world = TestWorld.fromSDL(
            "type Query { value(id: Int!): Int! echo(value: [Int]): Int! }",
            fieldResolvers = { schema ->
                listOf("value", "echo").associate { name ->
                    schema.requireObjectField("Query", name) to fieldResolverOf(schema.emptyFragmentOf("Query")) { _, _ -> 1 }
                }
            },
            fieldCheckers = { schema ->
                if (typeChecker) {
                    emptyMap()
                } else {
                    val field = schema.requireObjectField("Query", "value")
                    mapOf(field to FieldCheckerResolver.of(field, schema.requireQueryTypeDef(), templates(schema, ResolverTarget.FieldCheckerTarget(field))) { _, _, _ -> CheckerResult.Success })
                }
            },
            typeCheckers = { schema ->
                if (!typeChecker) {
                    emptyMap()
                } else {
                    val query = schema.requireQueryTypeDef()
                    mapOf(query to TypeCheckerResolver.of(query, query, templates(schema, ResolverTarget.TypeCheckerTarget(query))) { _, _ -> CheckerResult.Success })
                }
            },
        ).assumptions
        val query = world.schema.requireQueryTypeDef()
        val value = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "value"), mapOf("id" to 7))
        val root = ObjectEngineResult.of(query, typeCheckerResult = Promise.of(if (typeChecker) CheckerResult.Success else null), mutable = true)
        root.reserveCell(value).apply {
            this.value.set(1)
            fieldCheckerResult.complete(if (typeChecker) null else CheckerResult.Success)
        }
        val checker: CheckerResolverBase<*> = if (typeChecker) {
            world.resolverRegistry.typeChecker(query)!!
        } else {
            world.resolverRegistry.fieldChecker(value.field)!!
        }
        val path = if (typeChecker) emptyList() else listOf(value)
        val fragments = checker.instantiateFragmentsAt(root, path)
        fragments.objectFragment.constructionSelections.merge(query).byKey().keys.forEach { key ->
            root.reserveCell(key).apply {
                this.value.set(1)
                fieldCheckerResult.complete(null)
            }
        }
        root.freeze()
        val associatedQuery = ObjectEngineResult.of(
            query,
            values = fragments.queryFragment.constructionSelections.merge(query).byKey().keys.associateWith { 1 },
        )
        val observer = CorrectnessCheckerObserver().apply {
            onCheckerQueryFragmentPrepared(checker.target, ResolverOccurrenceId.at(root, path), associatedQuery)
        }

        fun accepted(forgedPair: String? = null): Boolean {
            val bindings = VariableBindingsState()
            (fragments.objectFragment.variableDefinitions + fragments.queryFragment.variableDefinitions).distinctBy { it.variable }.forEach { definition ->
                val name = definition.variable.variableName
                val bindingValue = when (name) {
                    "$forgedPair:v" -> listOf(99)
                    "object:v" -> listOf(7, null)
                    "query:v" -> null
                    else -> error("Unexpected checker variable $name")
                }
                bindings.bindVariable(definition.variable.instanceId!!, VariableBinding.of(bindingValue))
            }
            val operation = SharedOperationContext.create(world, variableBindings = bindings, checkerObserver = observer)
            val selections = world.operationSelectionsFrom("{ value(id: 7) }").merge(query)
            return root.correctResolution(operation, selections)
        }

        assertTrue(accepted(), "The unchanged provider relation and null binding must be accepted")
        assertFalse(accepted("object"), "A forged object-pair provider binding must be rejected even when echo and the checker return the same values")
        assertFalse(accepted("query"), "A forged query-pair provider binding must be rejected independently of the other pair's identically named variable")
        assertEquals(mapOf("object" to 3, "query" to 3), providerReplays, "Each judgment replays each named provider once")
    }
}
