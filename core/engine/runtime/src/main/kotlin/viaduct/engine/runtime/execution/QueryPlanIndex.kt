package viaduct.engine.runtime.execution

import com.github.benmanes.caffeine.cache.Caffeine
import java.util.Collections
import java.util.IdentityHashMap
import viaduct.engine.api.RequiredSelectionSet

/** Indexes query plans by the [RequiredSelectionSet.Id] that produced them. */
interface QueryPlanIndex {
    /** Returns the query plan associated with [id], or `null` when no indexed plan exists. */
    fun find(id: RequiredSelectionSet.Id): QueryPlan?

    /** Returns an index that looks in [overrides] first, then this index. */
    fun merge(overrides: QueryPlanIndex): QueryPlanIndex =
        object : QueryPlanIndex {
            override fun find(id: RequiredSelectionSet.Id): QueryPlan? = overrides.find(id) ?: this@QueryPlanIndex.find(id)
        }

    /** Factory for building [QueryPlanIndex] instances. */
    interface Factory {
        /** Create a [QueryPlanIndex] for the provided [rootPlan]. */
        fun create(rootPlan: QueryPlan): QueryPlanIndex

        /**
         * A default [Factory] that creates a new [QueryPlanIndex] on every invocation.
         * This Factory will index every child plan reachable from a provided root QueryPlan,
         * with the exception of field type child plans which will not be indexed.
         */
        object Default : Factory {
            override fun create(rootPlan: QueryPlan): QueryPlanIndex {
                val indexedPlans = mutableMapOf<RequiredSelectionSet.Id, QueryPlan>()
                val visitedPlans = Collections.newSetFromMap(IdentityHashMap<QueryPlan, Boolean>())
                val pendingPlans = ArrayDeque<QueryPlan>()
                pendingPlans.add(rootPlan)

                fun enqueueSelectionSet(
                    selectionSet: QueryPlan.SelectionSet,
                    fragments: QueryPlan.Fragments,
                    visitedFragments: MutableSet<String>,
                ) {
                    for (selection in selectionSet.selections) {
                        when (selection) {
                            is QueryPlan.CollectedField -> {
                                pendingPlans.addAll(selection.childPlans)
                                selection.selectionSet?.let {
                                    enqueueSelectionSet(it, fragments, visitedFragments)
                                }
                            }
                            is QueryPlan.Field -> {
                                pendingPlans.addAll(selection.childPlans)
                                selection.selectionSet?.let {
                                    enqueueSelectionSet(it, fragments, visitedFragments)
                                }
                            }
                            is QueryPlan.InlineFragment -> {
                                enqueueSelectionSet(selection.selectionSet, fragments, visitedFragments)
                            }
                            is QueryPlan.FragmentSpread -> {
                                if (!visitedFragments.add(selection.name)) continue
                                val fragment = fragments[selection.name] ?: continue
                                pendingPlans.addAll(fragment.childPlans)
                                enqueueSelectionSet(fragment.selectionSet, fragments, visitedFragments)
                            }
                        }
                    }
                }

                while (pendingPlans.isNotEmpty()) {
                    val plan = pendingPlans.removeFirst()
                    if (!visitedPlans.add(plan)) continue

                    plan.requiredSelectionSetId?.let { indexedPlans[it] = plan }
                    pendingPlans.addAll(plan.childPlans)
                    enqueueSelectionSet(plan.selectionSet, plan.fragments, mutableSetOf())
                }

                return object : QueryPlanIndex {
                    override fun find(id: RequiredSelectionSet.Id): QueryPlan? = indexedPlans[id]
                }
            }
        }

        /** Wraps index creation with an instance-scoped cache keyed by [QueryPlan] identity. */
        class Cached(private val underlying: Factory = Default) : Factory {
            private val cache = Caffeine.newBuilder()
                .weakKeys()
                .build<QueryPlan, QueryPlanIndex>()

            override fun create(rootPlan: QueryPlan): QueryPlanIndex = cache.get(rootPlan) { underlying.create(it) }!!
        }
    }
}
