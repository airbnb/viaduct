package viaduct.engine.runtime.execution

/**
 * Section 6.4.5
 * Given a fieldDetailsList (for which all entries are expected to have the same response key),
 * compute the defer usage set according to the normalization rules of @defer execution.
 *
 * This set determines which execution group owns the field. The original FieldDetails are
 * preserved, including their defer usages, so their subfields can still be collected later.
 *
 * Concretely:
 *  1. if a field is selected in both deferred and non-deferred contexts, then return an empty usage set
 *
 *      Example:
 *          Given:
 *          ```
 *              { a, ... @defer { a } }
 *          ```
 *          Then the usage set for `a` is empty, so `a` is owned by the non-deferred context.
 *          Both field selections are retained for subfield collection.
 *
 *  2. if a field is selected in both an ancestor and descendant defer, then remove the descendant usage
 *
 *      Example:
 *          Given:
 *              { ... @defer(label:"A") { a, ... @defer(label:"B") { a } } }
 *          Then the usage set for `a` is {A}, so `a` is owned by `A`.
 *          B is removed only from this field's usage set; its field selection is retained.
 *
 * The labels above are shorthand for DeferUsage records, not the identities used for grouping.
 */
object GetFilteredDeferUsageSet {
    operator fun invoke(fieldDetailsList: List<FieldDetails>): Set<DeferUsage> {
        val filteredDeferUsageSet = mutableSetOf<DeferUsage>()
        for (fieldDetails in fieldDetailsList) {
            /**
             * fieldDetailsList contains all the mergeable selections for a given field
             * If any of these are in a non-deferred context, then the field can be executed
             * in a non-deferred context and is not considered to be deferred, even if other
             * usages are deferred.
             *
             * For example, given this selection set:
             *   { x ... @defer { x } }
             *
             * The usage set for x is empty. Both selections are retained, but x itself
             * does not require deferral.
             */
            if (fieldDetails.deferUsage == null) {
                return emptySet()
            }
            filteredDeferUsageSet.add(fieldDetails.deferUsage)
        }

        val iterator = filteredDeferUsageSet.iterator()
        while (iterator.hasNext()) {
            val deferUsage = iterator.next()
            var parent = deferUsage.parent
            /**
             * If the current entry in filteredDeferUsageSet has any ancestor that is
             * also in this set, then remove the current entry through the iterator.
             *
             * For example, given this selection set:
             *   {
             *     ... @defer(label:"A") {
             *       x
             *       ... @defer(label:"B") {
             *         ... @defer(label:"C") {
             *           x
             *         }
             *       }
             *     }
             *   }
             *
             * The usage set for x starts as {A, C}. Remove C because A is an ancestor,
             * even though the immediate parent B is absent. The resulting set is {A}.
             */
            while (parent != null) {
                if (parent in filteredDeferUsageSet) {
                    iterator.remove()
                    break
                }
                parent = parent.parent
            }
        }
        return filteredDeferUsageSet
    }
}
