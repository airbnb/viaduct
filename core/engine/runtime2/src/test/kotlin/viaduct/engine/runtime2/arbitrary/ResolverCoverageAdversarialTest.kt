@file:Suppress("ForbiddenImport")

package viaduct.engine.runtime2.arbitrary

import io.kotest.property.Arb
import io.kotest.property.RandomSource
import io.kotest.property.arbitrary.next
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import viaduct.engine.api.EngineObjectData
import viaduct.engine.runtime2.model.Arguments
import viaduct.engine.runtime2.model.Assumptions
import viaduct.engine.runtime2.model.engineObjectDataOf
import viaduct.engine.runtime2.model.registry.ResolutionExecutionContext
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.requireQueryTypeDef
import viaduct.engine.runtime2.model.testing.fragmentFrom
import viaduct.engine.runtime2.model.testing.objectOf
import viaduct.engine.runtime2.resolvers.resolver01.resolve as resolveObservedFields

class ResolverCoverageAdversarialTest {
    @Test
    fun `generated schemas include nested output lists`() =
        runBlocking {
            val config =
                Config.default +
                    (ArgumentsEnabled to false) +
                    (InterfacesEnabled to false) +
                    (UnionsEnabled to false) +
                    (ListsEnabled to true) +
                    (ListTypeWeight to 1.0) +
                    (MaxOutputListDepth to 2) +
                    (ListValueSize to 1..1) +
                    (NullValueWeight to 0.0) +
                    (ErrorValueWeight to 0.0) +
                    (NodeResolversEnabled to false) +
                    (RecursiveOutputEdgesEnabled to false) +
                    (SchemaObjectCount to 1..1) +
                    (ObjectFieldCount to 1..1) +
                    (QueryFieldCount to 1..1)
            val random = RandomSource.seeded(817_207L)
            val schema = Arb.schema(config).next(random)
            val field = schema.query.fields.single()

            assertEquals(2, field.type.listDepth)
            assertTrue(schema.sdl.contains("[[${field.type.namedType}"))

            val registry = schema.registry(config).next(random)
            val world = registry.world(schema)
            val canonicalField = world.schema.requireObjectField("Query", field.name)
            val value =
                world.resolverRegistry.resolver(canonicalField)(
                    input = world.schema.objectOf("Query"),
                    queryValue = engineObjectDataOf(world.schema.requireQueryTypeDef()),
                    arguments = Arguments.Resolved.of(canonicalField, emptyMap()),
                    selectiveResolvers = false,
                    executionContext = ResolutionExecutionContext.Unsupported,
                )
            val outer = assertIs<List<*>>(value)
            val inner = assertIs<List<*>>(outer.single())
            assertIs<EngineObjectData.Sync>(inner.single())
        }

    @Test
    fun `generated field and node resolvers retain distinct singular and list witness fields`() =
        runBlocking {
            listOf(false, true).forEachIndexed { index, listOutput ->
                val config =
                    Config.default +
                        (ArgumentsEnabled to false) +
                        (InterfacesEnabled to true) +
                        (ListsEnabled to listOutput) +
                        (ListTypeWeight to if (listOutput) 1.0 else 0.0) +
                        (MaxOutputListDepth to 1) +
                        (NullValueWeight to 0.0) +
                        (ErrorValueWeight to 0.0) +
                        (NodeResolversEnabled to true) +
                        (ResolverFragmentsEnabled to false) +
                        (SchemaObjectCount to 5..7) +
                        (QueryFieldCount to 4..6)
                val random = RandomSource.seeded(817_208L + index)
                val generated =
                    List(200) {
                        val schema = Arb.schema(config).next(random)
                        val registry = schema.registry(config).next(random)
                        val sourceField =
                            registry.fieldResolverCoordinates.firstOrNull { coordinate ->
                                val field =
                                    schema
                                        .objectNamed(coordinate.typeName)
                                        .fields
                                        .single { candidate -> candidate.name == coordinate.fieldName }
                                coordinate.typeName == "Query" &&
                                    field.type.listDepth == (if (listOutput) 1 else 0) &&
                                    schema.isComposite(field.type.namedType) &&
                                    schema.possibleObjects(field.type.namedType)
                                        .all { possible ->
                                            possible.name in registry.nodeResolverTypes
                                        }
                            }
                        sourceField?.let { Triple(schema, registry, it) }
                    }.firstNotNullOfOrNull { it }
                        ?: error("Could not generate a Node-valued resolver with list=$listOutput")
                val (schema, registry, sourceField) = generated
                val world = registry.world(schema)
                val producerField =
                    world.schema.requireObjectField(sourceField.typeName, sourceField.fieldName)
                registry.clearResolutionWitness()
                val operation = viaduct.engine.runtime2.resolution.framework.SharedOperationContext.create(
                    world = Assumptions.of(world.schema, world.resolverRegistry, false),
                    resolverObserver = registry.resolverObserver(),
                )
                operation.resolveObservedFields(
                    world.schemas.fragmentFrom("fragment Test on Query { ${producerField.name} { id } }").subselections,
                )

                assertEquals(
                    listOf(
                        sourceField,
                        FieldCoordinate("Query", "node"),
                    ),
                    registry.resolutionWitness().applications.map { application ->
                        application.key.field
                    },
                )
            }
        }

    @Test
    fun `Resolver03 witness profile activates polymorphic passive deepening`() {
        val config =
            Config.default +
                (SchemaObjectCount to 4..6) +
                (ObjectFieldCount to 4..6) +
                (FieldArgumentWeight to 0.8) +
                (ExplicitFieldResolverWeight to 0.8) +
                (DuplicateSelectionWeight to 0.8) +
                (ResolverFragmentsEnabled to true) +
                (ResolverFromArgumentVariablesEnabled to true) +
                (ResolverVariableWeight to 1.0) +
                (PassiveAbstractOutputTypeWeight to 1.0) +
                (NodeResolversEnabled to false)
        val random = RandomSource.seeded(817_209L)
        var passivePolymorphicDeepenings = 0

        repeat(12) {
            val schema = Arb.schema(config).next(random)
            repeat(2) {
                val registry = schema.registry(config).next(random)
                registry.world(schema)
                passivePolymorphicDeepenings +=
                    registry.objectFragments.values.sumOf { fragment ->
                        fragment.selections.sumOf { selection ->
                            selection.countPassivePolymorphicDeepenings(
                                schema = schema,
                                ownerName = fragment.ownerName,
                                resolverFields = registry.fieldResolverCoordinates,
                            )
                        }
                    }
            }
        }

        assertTrue(
            passivePolymorphicDeepenings > 0,
            "Resolver03 witness profile generated no passive abstract continuation with concrete branches",
        )
    }
}

private fun FragmentSelectionPlan.countPassivePolymorphicDeepenings(
    schema: ArbitrarySchema,
    ownerName: String,
    resolverFields: Set<FieldCoordinate>,
): Int {
    val selectionOwner = typeCondition ?: ownerName
    val field =
        schema
            .fieldsOn(selectionOwner)
            .singleOrNull { candidate -> candidate.name == fieldName }
            ?: return 0
    val outputType = field.type.namedType
    val isAbstract =
        schema.isComposite(outputType) &&
            schema.allObjects.none { objectType -> objectType.name == outputType }
    val reachesConcreteBranches =
        subselections.any { selection ->
            selection.typeCondition != null &&
                selection.fieldName == GENERATED_HASH_FIELD &&
                selection.subselections.singleOrNull()?.fieldName == GENERATED_HASH_FIELD
        }
    val current =
        if (field.coordinate !in resolverFields && isAbstract && reachesConcreteBranches) 1 else 0
    return current +
        subselections.sumOf { selection ->
            selection.countPassivePolymorphicDeepenings(
                schema = schema,
                ownerName = outputType,
                resolverFields = resolverFields,
            )
        }
}
