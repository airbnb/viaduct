package viaduct.engine.runtime2.bootstrap

import viaduct.engine.runtime2.model.InclusionCondition
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.Selection
import viaduct.engine.runtime2.model.SelectionForest
import viaduct.engine.runtime2.model.isParentField
import viaduct.engine.runtime2.model.objectKey
import viaduct.engine.runtime2.model.registry.Assumptions
import viaduct.engine.runtime2.model.registry.CheckerResolverBase
import viaduct.engine.runtime2.model.registry.FieldValueResolver
import viaduct.engine.runtime2.model.registry.ParentConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.ParentSelectionDemand
import viaduct.engine.runtime2.model.registry.PreparedParentDemand
import viaduct.engine.runtime2.model.registry.PreparedParentSelection
import viaduct.engine.runtime2.resolution.ParentDemandApplication
import viaduct.engine.runtime2.resolution.withoutInclusionConditions
import viaduct.graphql.schema.ViaductSchema

internal class ParentConstructionDemandCompiler(private val world: Assumptions) {
    private val programs = mutableListOf<ParentConstructionDemandTemplate>()

    fun prepare(
        resolvers: Collection<FieldValueResolver>,
        checkers: Collection<CheckerResolverBase<*>>,
    ) {
        resolvers.forEach { resolver ->
            resolver.dependencyAlternatives.forEach { inputs ->
                prepare(inputs.objectParentDemand, inputs.objectFragment.withoutInclusionConditions(), checked = true)
                prepare(inputs.queryParentDemand, inputs.queryFragment.withoutInclusionConditions(), checked = true)
            }
        }
        checkers.forEach { checker ->
            val parents = world.resolverRegistry.checkerParentDemand(checker)
            prepare(parents.objectParentDemand, checker.objectFragment.withoutInclusionConditions(), checked = false)
            prepare(parents.queryParentDemand, checker.queryFragment.withoutInclusionConditions(), checked = false)
            checker.constructionDemandTemplate.let { template ->
                prepare(parents.staticObjectParentDemand, template.staticObjectDemand, checked = false)
                prepare(parents.dynamicObjectParentDemand, template.dynamicObjectDemand, checked = false)
                prepare(parents.staticQueryParentDemand, template.staticQueryDemand, checked = false)
                prepare(parents.dynamicQueryParentDemand, template.dynamicQueryDemand, checked = false)
            }
        }
    }

    fun compile(
        forest: SelectionForest,
        checked: Boolean = true
    ): ParentConstructionDemandTemplate = ParentConstructionDemandTemplate().also { prepare(it, forest, checked) }

    private fun prepare(
        template: ParentConstructionDemandTemplate,
        forest: SelectionForest,
        checked: Boolean,
    ) {
        programs += template
        template.selections = buildList {
            forest.forEach { selection ->
                selection.possibleTypes.forEach { type ->
                    add(prepareSelection(world, selection, type, checked, ::compile))
                }
            }
        }
        template.usesParent = template.selections.any { it.parentProducer != null || it.children.usesParent }
        template.mayInduceParentDemand = template.usesParent
    }

    fun finish(): Map<ViaductSchema.Object, Map<String, ParentSelectionDemand>> {
        val complete = mutableSetOf<ParentConstructionDemandTemplate>()
        val application = ParentDemandApplication(world, engineExecutionContext = null, useExecutionSelection = false)
        do {
            var changed = false
            programs.forEach { program ->
                val inducesParent = program.usesParent || program.dependencies.any { it.mayInduceParentDemand }
                val selectsInputs = inducesParent && (
                    program.dependencies.any { it.dependsOnExecutionSelection } ||
                        program.selections.any { selection ->
                            selection.resolver?.let { resolver ->
                                resolver.dependencyAlternatives.size > 1 && resolver.dependencyAlternatives.any { it.objectParentDemand.mayInduceParentDemand }
                            } == true
                        }
                )
                if (program.mayInduceParentDemand != inducesParent || program.dependsOnExecutionSelection != selectsInputs) changed = true
                program.mayInduceParentDemand = inducesParent
                program.dependsOnExecutionSelection = selectsInputs
            }
        } while (changed)

        fun summarize(program: ParentConstructionDemandTemplate) {
            if (!complete.add(program)) return
            program.dependencies.forEach(::summarize)
            program.summary = application.summarize(program)
        }
        programs.forEach(::summarize)
        val registry = world.resolverRegistry
        val fieldsByType = world.schema.types.values.filterIsInstance<ViaductSchema.Object>().associateWith { type ->
            type.fields.filterNot { it.isParentField() }.map { field ->
                val resolver = if (field in registry) registry.resolver(field) else null
                val checker = registry.fieldChecker(field)?.let { registry.checkerParentDemand(it).objectParentDemand }
                val typeCheckerPrograms = (field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes.orEmpty()
                    .mapNotNull { outputType -> registry.typeChecker(outputType)?.let { registry.checkerParentDemand(it).objectParentDemand } }
                val resolverSummary = resolver?.declaredDependencies?.objectParentDemand?.summary ?: PreparedParentDemand.EMPTY
                val checkerSummary = checker?.summary ?: PreparedParentDemand.EMPTY
                val typeCheckers = typeCheckerPrograms.fold(PreparedParentDemand.EMPTY) { demand, program -> application.combine(demand, program.summary) }
                ParentSelectionDemand(field, application.combine(resolverSummary, checkerSummary), resolverSummary, typeCheckers, resolver, checker, typeCheckerPrograms)
            }
        }

        fun ParentSelectionDemand.hasFixedDemand(): Boolean =
            checked !== PreparedParentDemand.EMPTY || typeCheckers !== PreparedParentDemand.EMPTY ||
                resolver?.dependencyAlternatives.orEmpty().any { it.objectParentDemand.mayInduceParentDemand } ||
                checker?.mayInduceParentDemand == true || typeCheckerPrograms.any { it.mayInduceParentDemand }

        fun ParentSelectionDemand.reaches(types: Set<ViaductSchema.Object>): Boolean = (field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes.orEmpty().any { it in types }

        // Clients cannot select @parent, so only paths to fixed fragment effects need runtime matching.
        val relevantTypes = fieldsByType.filterValues { fields -> fields.any { it.hasFixedDemand() } }.keys.toMutableSet()
        do {
            val added = fieldsByType.filter { (type, fields) -> type !in relevantTypes && fields.any { it.reaches(relevantTypes) } }.keys
            relevantTypes += added
        } while (added.isNotEmpty())
        return fieldsByType.filterKeys { it in relevantTypes }.mapValues { (_, fields) ->
            fields.filter { it.hasFixedDemand() || it.reaches(relevantTypes) }.associateBy { it.field.name }
        }
    }
}

private fun prepareSelection(
    world: Assumptions,
    selection: Selection,
    type: ViaductSchema.Object,
    checked: Boolean,
    compile: (SelectionForest, Boolean) -> ParentConstructionDemandTemplate,
): PreparedParentSelection {
    val key = selection.objectKey(type)
    val concrete = Selection.of(key, setOf(type), selection.subselections, selection.inclusionCondition)
    val parentProducer = (key as? ObjectEngineResult.ParentKey)?.let { world.parentFieldRelations.getValue(it.field) }
    val registry = world.resolverRegistry
    return PreparedParentSelection(
        selection = concrete,
        checked = checked,
        parentProducer = parentProducer,
        children = compile(selection.subselections, checked),
        resolver = if (parentProducer == null && key.field in registry) registry.resolver(key.field) else null,
        checker = if (parentProducer == null && checked) registry.fieldChecker(key.field)?.let { registry.checkerParentDemand(it).objectParentDemand } else null,
        typeCheckers = if (parentProducer == null && checked && selection.inclusionCondition !== InclusionCondition.Never) {
            (key.field.type.baseTypeDef as? ViaductSchema.CompositeTypeDef)?.possibleObjectTypes.orEmpty()
                .mapNotNull { registry.typeChecker(it)?.let { checker -> registry.checkerParentDemand(checker).objectParentDemand } }
        } else {
            emptyList()
        },
    )
}
