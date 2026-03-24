package viaduct.arbitrary.graphql

import graphql.schema.GraphQLInputObjectType
import graphql.schema.GraphQLList
import graphql.schema.GraphQLNonNull
import graphql.schema.GraphQLTypeUtil
import graphql.schema.GraphQLUnmodifiedType
import kotlin.reflect.KClass
import viaduct.engine.api.ViaductSchema

/**
 * Represents groups of input object types that participate in recursive cycles.
 *
 * Types within a group are mutually reachable from each other through the edges
 * defined by the factory method that created this instance (e.g., [mandatoryInputCycles] or [allInputCycles]).
 * Each group represents a Strongly Connected Component (SCC) in the input object dependency graph.
 */
internal class CycleGroups(val map: Map<String, Set<String>>) {
    fun isEmpty(): Boolean = map.isEmpty()

    operator fun get(name: String): Set<String> = map[name] ?: emptySet()

    override fun toString(): String = map.toString()

    companion object {
        val Empty: CycleGroups = CycleGroups(emptyMap())

        /**
         * Returns a [CycleGroups] that identifies cycles formed by mandatory input edges.
         *
         * A mandatory edge represents a structural dependency that must be satisfied
         * to produce a valid input value. This includes:
         * - Non-nullable fields with no default value in a standard input object.
         * - All fields of a `@oneOf` input object (as the `@oneOf` constraint requires
         *   exactly one field to be selected).
         *
         * List-typed fields (even non-nullable ones) are not considered mandatory edges
         * because an empty list successfully satisfies the requirement without recursing.
         *
         * These edges define paths that a generator may be "forced" to follow to produce
         * a well-formed value. A cycle of mandatory edges is only valid in a GraphQL
         * schema if it contains at least one `@oneOf` input object that provides a
         * non-cyclic "exit" field (such as a scalar or an optional dependency) to
         * terminate the recursion.
         */
        fun mandatoryInputCycles(schema: ViaductSchema): CycleGroups =
            buildScc(schema, GraphQLInputObjectType::class) { inputObject ->
                val edges = mutableSetOf<String>()

                for (field in inputObject.fields) {
                    if (field.hasSetDefaultValue()) continue

                    val fieldType = field.type
                    val unwrappedType = GraphQLTypeUtil.unwrapAll(fieldType)

                    if (inputObject.isOneOf) {
                        edges.add(unwrappedType.name)
                    } else if (fieldType is GraphQLNonNull && fieldType.wrappedType !is GraphQLList) {
                        edges.add(unwrappedType.name)
                    }
                }

                edges
            }

        /**
         * Returns a [CycleGroups] that identifies all recursive cycles between
         * input objects.
         *
         * This considers every reference between input objects as an edge, regardless
         * of nullability, list wrappers, or default values.
         */
        fun allInputCycles(schema: ViaductSchema): CycleGroups =
            buildScc(schema, GraphQLInputObjectType::class) { inputObject ->
                val edges = mutableSetOf<String>()

                for (field in inputObject.fields) {
                    val fieldType = GraphQLTypeUtil.unwrapAll(field.type)
                    if (fieldType is GraphQLInputObjectType) {
                        edges.add(fieldType.name)
                    }
                }

                edges
            }

        private fun <T : GraphQLUnmodifiedType> buildScc(
            schema: ViaductSchema,
            targetClass: KClass<T>,
            extractNeighbors: (T) -> MutableSet<String>
        ): CycleGroups {
            val types = schema.schema.allTypesAsList.filterIsInstance(targetClass.java)
            if (types.isEmpty()) return Empty

            val adj = mutableMapOf<String, MutableSet<String>>()
            val selfLoops = mutableSetOf<String>()
            for (type in types) {
                val neighbors = extractNeighbors(type)
                if (type.name in neighbors) {
                    selfLoops += type.name
                }
                adj[type.name] = neighbors
            }

            // Tarjan's SCC algorithm
            var index = 0
            val indices = mutableMapOf<String, Int>()
            val lowlinks = mutableMapOf<String, Int>()
            val onStack = mutableSetOf<String>()
            val stack = ArrayDeque<String>()
            val sccs = mutableListOf<Set<String>>()

            fun connect(v: String) {
                indices[v] = index
                lowlinks[v] = index
                index++
                stack.addLast(v)
                onStack.add(v)

                for (w in adj[v] ?: emptySet()) {
                    if (w !in indices) {
                        connect(w)
                        lowlinks[v] = minOf(lowlinks[v]!!, lowlinks[w]!!)
                    } else if (w in onStack) {
                        lowlinks[v] = minOf(lowlinks[v]!!, indices[w]!!)
                    }
                }

                if (lowlinks[v] == indices[v]) {
                    val scc = mutableSetOf<String>()
                    do {
                        val w = stack.removeLast()
                        onStack.remove(w)
                        scc.add(w)
                    } while (w != v)
                    sccs.add(scc)
                }
            }

            for (v in adj.keys) {
                if (v !in indices) {
                    connect(v)
                }
            }

            // Only keep cyclic SCCs: size > 1, or size 1 with self-loop
            val result = mutableMapOf<String, Set<String>>()
            for (scc in sccs) {
                if (scc.size > 1 || (scc.size == 1 && scc.first() in selfLoops)) {
                    for (name in scc) {
                        result[name] = scc
                    }
                }
            }

            if (result.isEmpty()) return Empty
            return CycleGroups(result)
        }
    }
}
