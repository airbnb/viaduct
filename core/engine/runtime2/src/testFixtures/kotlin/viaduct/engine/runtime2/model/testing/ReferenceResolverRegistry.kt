package viaduct.engine.runtime2.model.testing

import java.util.WeakHashMap
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.forEachPossibleObjectField
import viaduct.engine.runtime2.model.registry.ConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.FragmentConstructionDemandTemplate
import viaduct.engine.runtime2.model.registry.ResolverRegistry
import viaduct.graphql.schema.ViaductSchema

/** Reference-family enrichment of the direct production inputs. */
class ReferenceConstructionDemandTemplate internal constructor(
    val direct: ConstructionDemandTemplate,
    val objectDemand: ReferenceFragmentConstructionDemandTemplate,
    val queryDemand: ReferenceFragmentConstructionDemandTemplate,
    val queryRootDemand: ReferenceFragmentConstructionDemandTemplate?,
    val hasVariables: Boolean,
)

class ReferenceFragmentConstructionDemandTemplate internal constructor(
    private val direct: FragmentConstructionDemandTemplate,
    val dependencies: Map<ObjectEngineResult.GroundKey, ReferenceConstructionDemandTemplate>,
    val reusableCheckerKeys: Set<ObjectEngineResult.GroundKey>,
) {
    val reusableDemand get() = direct.reusableDemand
    val templatedDemand get() = direct.templatedDemand
    internal val reusableParentDemand get() = direct.reusableParentDemand
    internal val templatedParentDemand get() = direct.templatedParentDemand
}

private val referenceTemplates = WeakHashMap<ResolverRegistry, MutableMap<ViaductSchema.ObjectField, ReferenceConstructionDemandTemplate>>()

fun ResolverRegistry.referenceConstructionDemand(field: ViaductSchema.ObjectField): ReferenceConstructionDemandTemplate? =
    synchronized(referenceTemplates) {
        if (precomputedConstructionDemand(field, context = null) == null) return@synchronized null
        val cache = referenceTemplates.getOrPut(this) { mutableMapOf() }
        val pending = ArrayDeque<ViaductSchema.ObjectField>()
        pending.add(field)
        while (pending.isNotEmpty()) {
            val current = pending.last()
            if (current in cache) {
                pending.removeLast()
                continue
            }
            val direct = requireNotNull(precomputedConstructionDemand(current, context = null))
            val fragments = listOfNotNull(direct.objectDemand, direct.queryDemand, direct.queryRootDemand)
            val dependency = fragments.asSequence().flatMap { it.reusableDemand.groundKeys() }
                .firstOrNull { it.field in this && it.field !in cache }
            if (dependency != null) {
                pending.add(dependency.field)
                continue
            }

            fun enrich(fragment: FragmentConstructionDemandTemplate): ReferenceFragmentConstructionDemandTemplate =
                ReferenceFragmentConstructionDemandTemplate(
                    direct = fragment,
                    dependencies = fragment.reusableDemand.groundKeys().filter { it.field in this }.associateWith { cache.getValue(it.field) },
                    reusableCheckerKeys = fragment.reusableDemand.groundKeys().filterTo(linkedSetOf()) { fieldChecker(it.field) != null },
                )

            val objectDemand = enrich(direct.objectDemand)
            val queryDemand = enrich(direct.queryDemand)
            cache[current] = ReferenceConstructionDemandTemplate(
                direct = direct,
                objectDemand = objectDemand,
                queryDemand = queryDemand,
                queryRootDemand = when {
                    direct.queryRootDemand == null -> null
                    direct.queryRootDemand === direct.objectDemand -> objectDemand
                    direct.queryRootDemand === direct.queryDemand -> queryDemand
                    else -> enrich(requireNotNull(direct.queryRootDemand))
                },
                hasVariables = resolver(current).declaredDependencies.variables.isNotEmpty(),
            )
            pending.removeLast()
        }
        cache.getValue(field)
    }

/** Conservative direct resolver dependencies for generated-world witnesses. */
fun ResolverRegistry.mayDemandFrom(field: ViaductSchema.ObjectField): Set<ViaductSchema.ObjectField> {
    require(field in this) { "Resolver field is not registered" }
    val inputs = resolver(field).declaredDependencies
    return buildSet {
        listOf(inputs.objectFragment, inputs.queryFragment).forEach { fragment ->
            fragment.forEachPossibleObjectField { dependency ->
                if (dependency in this@mayDemandFrom) add(dependency)
            }
        }
    }
}
