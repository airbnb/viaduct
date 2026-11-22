package viaduct.engine.runtime2.correctresolution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import viaduct.engine.api.CheckerResult
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Promise
import viaduct.engine.runtime2.model.ResolverOccurrenceId
import viaduct.engine.runtime2.model.ResolverTarget
import viaduct.engine.runtime2.model.VariableBinding
import viaduct.engine.runtime2.model.materializeSelectionForestOf
import viaduct.engine.runtime2.model.merge
import viaduct.engine.runtime2.model.registry.CheckerResolverBase
import viaduct.engine.runtime2.model.registry.FieldCheckerResolver
import viaduct.engine.runtime2.model.registry.ResolverFragmentTemplates
import viaduct.engine.runtime2.model.registry.TypeCheckerResolver
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.requireType
import viaduct.engine.runtime2.model.testing.TestWorld
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.resolution.framework.SharedOperationContext
import viaduct.engine.runtime2.resolution.framework.VariableBindingsState
import viaduct.engine.runtime2.schema.ViaductAndGJSchema
import viaduct.engine.runtime2.schema.operationSelectionsFrom

/** Forged bindings must not become assumptions of the checker relation being judged. */
class CheckerProviderCorrectResolutionTest {
    @Test
    fun `type checker replay rejects forged provider values in either named input`() {
        checkProviderBindings(typeChecker = true)
    }

    @Test
    fun `field checker replay rejects forged provider values in either named input`() {
        checkProviderBindings(typeChecker = false)
    }

    private fun checkProviderBindings(typeChecker: Boolean) {
        val providerReplays = mutableMapOf<String, Int>()

        fun templates(
            schema: ViaductAndGJSchema,
            target: ResolverTarget,
        ): Map<String, ResolverFragmentTemplates> =
            listOf("object", "query").associateWith { name ->
                val input = schema.fragmentFrom(
                    "fragment Input on ${if (name == "object") "Item" else "Query"} { echo(value: ${'$'}v) }",
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

        val worldFixture = TestWorld.fromDSL(
            """
            extend type Query {
              item: Item! @resolver(script: "{:}")
              echo(value: [Int]): Int! @resolver(script: "1")
            }
            type Item {
              value(id: Int!): Int! @resolver(script: "1")
              echo(value: [Int]): Int! @resolver(script: "1")
            }
            """.trimIndent(),
            fieldCheckers = { schema ->
                if (typeChecker) {
                    emptyMap()
                } else {
                    val field = schema.loweredSchema.requireObjectField("Item", "value")
                    mapOf(
                        field to FieldCheckerResolver.of(
                            field,
                            schema.loweredSchema.requireQueryTypeDef(),
                            templates(schema, ResolverTarget.FieldCheckerTarget(field))
                        ) { _, _, _ -> CheckerResult.Success }
                    )
                }
            },
            typeCheckers = { schema ->
                if (!typeChecker) {
                    emptyMap()
                } else {
                    val query = schema.loweredSchema.requireQueryTypeDef()
                    val item = schema.loweredSchema.requireType("Item") as viaduct.graphql.schema.ViaductSchema.Object
                    mapOf(item to TypeCheckerResolver.of(item, query, templates(schema, ResolverTarget.TypeCheckerTarget(item))) { _, _ -> CheckerResult.Success })
                }
            },
        )
        val world = worldFixture.assumptions
        val query = worldFixture.schema.requireQueryTypeDef()
        val value = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Item", "value"), mapOf("id" to 7))
        val itemType = worldFixture.schema.requireType("Item") as viaduct.graphql.schema.ViaductSchema.Object
        val item = ObjectEngineResult.of(itemType, typeCheckerResult = Promise.of(if (typeChecker) CheckerResult.Success else null), mutable = true)
        val itemKey = ObjectEngineResult.GroundKey.of(world.schema.requireObjectField("Query", "item"), emptyMap())
        val root = ObjectEngineResult.of(query, values = mapOf(itemKey to item))
        item.reserveCell(value).apply {
            this.value.set(1)
            fieldCheckerResult.complete(if (typeChecker) null else CheckerResult.Success)
        }
        val checker: CheckerResolverBase<*> = if (typeChecker) {
            world.resolverRegistry.typeChecker(itemType)!!
        } else {
            world.resolverRegistry.fieldChecker(value.field)!!
        }
        val path = if (typeChecker) listOf(itemKey) else listOf(itemKey, value)
        val fragments = checker.instantiateFragmentsAt(root, path)
        fragments.objectFragment.constructionSelections.merge(itemType).byKey().keys.forEach { key ->
            item.reserveCell(key).apply {
                this.value.set(1)
                fieldCheckerResult.complete(null)
            }
        }
        item.freeze()
        val associatedQuery = ObjectEngineResult.of(
            query,
            values = fragments.queryFragment.constructionSelections.merge(query).byKey().keys.associateWith { 1 },
        )
        val observer = CorrectnessResolverObserver().apply {
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
            val operation = SharedOperationContext.create(world, variableBindings = bindings, resolutionObserver = observer)
            val selections = worldFixture.schemas.operationSelectionsFrom("{ item { value(id: 7) } }").merge(query)
            return root.correctResolution(operation, selections)
        }

        assertTrue(accepted(), "The unchanged provider relation and null binding must be accepted")
        assertFalse(accepted("object"), "A forged object-pair provider binding must be rejected even when echo and the checker return the same values")
        assertFalse(accepted("query"), "A forged query-pair provider binding must be rejected independently of the other pair's identically named variable")
        assertEquals(mapOf("object" to 3, "query" to 3), providerReplays, "Each judgment replays each named provider once")
    }
}
