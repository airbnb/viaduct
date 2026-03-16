package viaduct.engine.runtime.tenantloading

/**
 * A directed graph that tracks dependencies between resolvers and access checkers, and can
 * detect cycles in those dependencies.
 *
 * Each node in the graph sits at a coordinate and represents a resolver or a checker. A
 * coordinate is either a field like (Foo, x) or a type like (Foo, null). A type coordinate
 * appears when a selection set traverses into a composite-typed field — for example,
 * selecting `foo { x }` where `foo:Foo` produces both (Foo, null) and (Foo, x). The type
 * coordinate represents accessing an instance of that type, which matters because a
 * type-level access checker may need to run. Selecting a scalar field like `y:Int` produces
 * only the field coordinate (Foo, y) with no type coordinate.
 *
 * Each node carries a set of coordinates that it selects — its "selected coordinates."
 * Edges point from a node to other registered nodes at the coordinates it selects.
 *
 * ## Example
 * Given this schema:
 * ```graphql
 * type Query {
 *   foo:Foo @resolver
 * }
 * type Foo {
 *   x:Int @resolver("y")
 *   y:Int @resolver
 *   parent:Foo
 * }
 * ```
 *
 * A graph could be built as follows:
 * ```
 * graph.addResolverNode((Query, foo), {})                           // Query.foo has no selections
 * graph.addResolverNode((Foo, x),     {(Foo, y)})                   // Foo.x selects y
 * graph.addResolverNode((Foo, y),     {})                           // Foo.y has no selections
 * graph.addCheckerNode((Foo, null),   {(Foo, x)})                   // Foo type checker selects x
 * graph.addCheckerNode((Foo, x),      {(Foo, parent), (Foo, null)}) // Foo.x checker selects parent
 * graph.addCheckerNode((Foo, y),      {})                           // Foo.y checker has no selections
 * ```
 *
 * ## Edge rules
 *
 * Whether a selected coordinate produces an edge depends on the source node's role (resolver
 * or checker) and the kind of coordinate (field or type):
 *
 * - **Resolver selecting a field coordinate** -> creates edges to both resolvers and
 *   checkers at that coordinate. For example, resolver(Foo, x) selects (Foo, y), so
 *   there is an edge to resolver(Foo, y) and checker(Foo, y).
 *
 * - **Resolver selecting a type coordinate** -> creates edges to checkers at that coordinate.
 *   For example, if resolver(Foo, x) selected `parent { y }`, its selected coordinates would
 *   include the type coordinate (Foo, null), producing an edge to checker(Foo, null).
 *   Type checkers are currently the only component that may have an RSS at a type coordinate,
 *   though if NodeResolvers could have RSSes then edges would be created for them as well.
 *
 * - **Checker selecting a field coordinate** -> creates edges to resolvers at that coordinate.
 *   For example, checker(Foo, null) selects (Foo, x), so there is an edge to resolver(Foo, x)
 *   but not to checker(Foo, x).
 *
 * - **Checker selecting a type coordinate** -> creates no edges. For example, checker(Foo, x)
 *   selects (Foo, null), but this produces no edge to checker(Foo, null) or anything else.
 */
class RequiredSelectionSetGraph {
    /**
     * Each call to [addResolverNode]/[addCheckerNode] adds a GraphNode. Multiple nodes can
     * share the same (coord, isChecker) pair; identical nodes (same coord and selectedCoords)
     * are deduplicated.
     */
    private val resolverNodes = mutableMapOf<TypeOrFieldCoordinate, MutableSet<GraphNode>>()

    /** @see resolverNodes */
    private val checkerNodes = mutableMapOf<TypeOrFieldCoordinate, MutableSet<GraphNode>>()

    /**
     * Cached reverse edges of the "abstract" graph, invalidated when nodes are added.
     *
     * The abstract graph has one node per (coord, isChecker) pair, even if multiple GraphNodes
     * share that pair. Its forward edges are the union of all their selected coordinates. For
     * example, if two resolver nodes are registered at (Foo, x) — one selecting (Foo, y) and
     * another selecting (Foo, z) — the abstract graph has a single resolver(Foo, x) with
     * forward edges to both (Foo, y) and (Foo, z).
     *
     * This map stores the *reverse* of those edges: each key maps to the set of nodes that
     * point to it. In the example above, the reverse map would contain (Foo, y) -> resolver(Foo, x)
     * and (Foo, z) -> resolver(Foo, x). This allows [computeAncestors] to walk backwards from
     * a target node to find everything that can reach it.
     */
    private var cachedReverseEdges: Map<
        Pair<TypeOrFieldCoordinate, Boolean>,
        Set<Pair<TypeOrFieldCoordinate, Boolean>>
    >? = null

    /**
     * Inverted index: for a given coordinate, which nodes select it?
     *
     * Using the example in this class' kdocs, the entry for (Foo, x) would be
     * {checker(Foo, null)} — because checker(Foo, null) includes (Foo, x) in its
     * selected coordinates.
     *
     * This is used by [computeAncestors] to find "seed" parents of a target that doesn't
     * yet exist in the graph. The reverse edge cache ([cachedReverseEdges]) only covers
     * nodes that are already registered, so this index fills the gap for new nodes.
     */
    private val selectorsBySelectedCoord = mutableMapOf<
        TypeOrFieldCoordinate,
        MutableSet<Pair<TypeOrFieldCoordinate, Boolean>>
    >()

    /** A resolver or checker at [coord], with outgoing edges determined by [selectedCoords]. */
    private data class GraphNode(
        val coord: TypeOrFieldCoordinate,
        val isChecker: Boolean,
        val selectedCoords: Set<TypeOrFieldCoordinate>
    )

    /**
     * Add a resolver node at [coord] with the given [selectedCoords].
     *
     * @param coord the (typeName, fieldName) coordinate for this resolver
     * @param selectedCoords the coordinates this node selects
     */
    fun addResolverNode(
        coord: TypeOrFieldCoordinate,
        selectedCoords: Set<TypeOrFieldCoordinate>
    ) = addNode(resolverNodes, coord, selectedCoords, isChecker = false)

    /**
     * Add a checker node at [coord] with the given [selectedCoords].
     *
     * For field checkers, [coord] is a (typeName, fieldName) pair.
     * For type checkers, [coord] is a (typeName, null) pair.
     *
     * @param coord the coordinate for this checker
     * @param selectedCoords the coordinates this node selects
     */
    fun addCheckerNode(
        coord: TypeOrFieldCoordinate,
        selectedCoords: Set<TypeOrFieldCoordinate>
    ) = addNode(checkerNodes, coord, selectedCoords, isChecker = true)

    private fun addNode(
        nodes: MutableMap<TypeOrFieldCoordinate, MutableSet<GraphNode>>,
        coord: TypeOrFieldCoordinate,
        selectedCoords: Set<TypeOrFieldCoordinate>,
        isChecker: Boolean
    ) {
        cachedReverseEdges = null
        nodes.getOrPut(coord) { mutableSetOf() }.add(GraphNode(coord, isChecker, selectedCoords))
        selectedCoords.forEach {
            selectorsBySelectedCoord.getOrPut(it) { mutableSetOf() }.add(coord to isChecker)
        }
    }

    /**
     * Walks the full graph and throws [RequiredSelectionsCycleException] if it finds any cycle.
     *
     * Using the example from the class docs, the graph is acyclic — all paths eventually
     * reach nodes with no outgoing edges, such as checker(Foo, y) and resolver(Query, foo).
     *
     * @throws RequiredSelectionsCycleException if a cycle is detected
     */
    fun assertAcyclic() {
        val visited = mutableSetOf<GraphNode>()
        val allNodes = (resolverNodes.values + checkerNodes.values).flatten()
        for (node in allNodes) {
            if (node !in visited) {
                dfs(node, mutableListOf(), mutableSetOf(), visited)?.let { cyclePath ->
                    throw RequiredSelectionsCycleException(cyclePath.map { it.toPath() })
                }
            }
        }
    }

    /**
     * Answers: "if I register a new node at [coord], which coordinates must it avoid selecting
     * to keep the graph acyclic?" It does this by finding all nodes that can reach [coord]
     * through the existing edges.
     *
     * Continuing the class-level example, suppose we want to add a new resolver at (Foo, y).
     * Calling `getBlockedCoordinates((Foo, y), isChecker = false)` returns
     * {(Foo, y), (Foo, x), (Foo, null)}:
     *
     * - **(Foo, y)** — a resolver always blocks its own coordinate to prevent a self-loop.
     * - **(Foo, x)** — resolver(Foo, x) reaches (Foo, y) in one step, so selecting it back
     *   would create a cycle.
     * - **(Foo, null)** — checker(Foo, null) reaches (Foo, y) through resolver(Foo, x), and
     *   a resolver selecting a type coordinate creates an edge to the type checker, so this
     *   would cycle.
     *
     * Note that (Query, foo) is *not* blocked even though resolver(Query, foo) exists —
     * it has no selections and cannot reach (Foo, y), so it is not an ancestor.
     *
     * By contrast, `getBlockedCoordinates((Foo, y), isChecker = true)` returns only
     * {(Foo, x)}:
     *
     * - **(Foo, x)** is blocked because resolver(Foo, x) is an ancestor, and a checker
     *   selecting a field coordinate creates an edge to the resolver there.
     * - **(Foo, null)** is not blocked — a checker selecting a type coordinate produces no
     *   edge, so it cannot create a cycle through the type checker.
     * - **(Foo, y)** is not blocked — checkers have no edges to other checkers, so a
     *   checker selecting its own coordinate is harmless.
     *
     * This uses a conservative (over-approximating) approach based on an abstract graph where
     * each (coord, isChecker) pair is a single node whose edges are the union of all registered
     * selectedCoords for that pair.
     */
    fun getBlockedCoordinates(
        coord: TypeOrFieldCoordinate,
        isChecker: Boolean
    ): Set<TypeOrFieldCoordinate> {
        val blocked = mutableSetOf<TypeOrFieldCoordinate>()
        // Resolvers are always blocked from selecting their own coord (direct self-loop creates a cycle)
        if (!isChecker) blocked.add(coord)

        val ancestors = computeAncestors(coord, isChecker)

        for ((ancestorCoord, ancestorIsChecker) in ancestors) {
            if (!isChecker) {
                // For resolver:
                // - Block field coord C if ANY node (resolver or checker) at C is an ancestor,
                //   because resolver(C) -> resolver(coord) and checker(C) -> resolver(coord) both
                //   create cycles when the new resolver selects C.
                // - Block type coord (T, null) only if the CHECKER at (T, null) is an ancestor,
                //   because selecting (T, null) adds an edge to checker(T, null) only.
                if (ancestorCoord.second != null) {
                    blocked.add(ancestorCoord)
                } else if (ancestorIsChecker) {
                    blocked.add(ancestorCoord)
                }
            } else {
                // For checker:
                // - Block field coord C only if the RESOLVER at C is an ancestor, because
                //   selecting C adds an edge to resolver(C) only (checkers have no edges to checkers).
                // - Type coords are never blocked for checkers.
                if (ancestorCoord.second != null && !ancestorIsChecker) {
                    blocked.add(ancestorCoord)
                }
            }
        }

        return blocked
    }

    /**
     * Computes all abstract nodes that can reach ([targetCoord], [targetIsChecker]) in the
     * existing abstract forward graph, using BFS on the cached reverse graph.
     *
     * Returns a set of (TypeOrFieldCoordinate, isChecker) pairs, excluding the target itself.
     */
    private fun computeAncestors(
        targetCoord: TypeOrFieldCoordinate,
        targetIsChecker: Boolean,
    ): Set<Pair<TypeOrFieldCoordinate, Boolean>> {
        val baseReverseEdges = getReverseEdges()

        // Compute seed parents: existing abstract nodes that would gain an edge to the target
        // once it is registered. These aren't in the cached reverse graph because the target
        // doesn't yet exist as a node.
        val seedParents = mutableSetOf<Pair<TypeOrFieldCoordinate, Boolean>>()
        for ((selectorCoord, selectorIsChecker) in selectorsBySelectedCoord[targetCoord] ?: emptySet()) {
            val wouldEdge = if (!selectorIsChecker) {
                // Resolver selecting targetCoord: edges to resolver(target) for field coords,
                // edges to checker(target) always
                if (!targetIsChecker) targetCoord.second != null else true
            } else {
                // Checker selecting targetCoord: edges to resolver(target) for field coords only
                !targetIsChecker && targetCoord.second != null
            }
            if (wouldEdge) {
                seedParents.add(selectorCoord to selectorIsChecker)
            }
        }

        // BFS from (targetCoord, targetIsChecker) in the reverse graph
        val ancestors = mutableSetOf<Pair<TypeOrFieldCoordinate, Boolean>>()
        val queue = ArrayDeque<Pair<TypeOrFieldCoordinate, Boolean>>()
        queue.add(targetCoord to targetIsChecker)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            if (ancestors.add(current)) {
                baseReverseEdges[current]?.forEach { queue.add(it) }
                // Apply seed edges only for the target node itself
                if (current == targetCoord to targetIsChecker) {
                    seedParents.forEach { queue.add(it) }
                }
            }
        }
        ancestors.remove(targetCoord to targetIsChecker)
        return ancestors
    }

    /**
     * Returns the cached reverse abstract edge graph, building it if needed.
     */
    private fun getReverseEdges(): Map<
        Pair<TypeOrFieldCoordinate, Boolean>,
        Set<Pair<TypeOrFieldCoordinate, Boolean>>
    > {
        if (cachedReverseEdges != null) {
            return cachedReverseEdges!!
        }
        val result = mutableMapOf<
            Pair<TypeOrFieldCoordinate, Boolean>,
            MutableSet<Pair<TypeOrFieldCoordinate, Boolean>>
        >()
        for (abstractCoord in resolverNodes.keys) {
            for (target in getAbstractEdges(abstractCoord, false)) {
                result.getOrPut(target) { mutableSetOf() }.add(abstractCoord to false)
            }
        }
        for (abstractCoord in checkerNodes.keys) {
            for (target in getAbstractEdges(abstractCoord, true)) {
                result.getOrPut(target) { mutableSetOf() }.add(abstractCoord to true)
            }
        }
        cachedReverseEdges = result
        return result
    }

    /**
     * Returns the abstract edges from ([coord], [isChecker]) in the abstract graph.
     *
     * The abstract graph uses the union of selectedCoords across all nodes at
     * ([coord], [isChecker]). Edge targets that have no registered nodes are omitted.
     */
    private fun getAbstractEdges(
        coord: TypeOrFieldCoordinate,
        isChecker: Boolean,
    ): List<Pair<TypeOrFieldCoordinate, Boolean>> {
        val allSelectedCoords = (if (isChecker) checkerNodes[coord] else resolverNodes[coord])
            ?.flatMap { it.selectedCoords }
            ?.toSet()
            ?: return emptyList()

        return buildList {
            for (c in allSelectedCoords) {
                val fieldName = c.second
                if (fieldName != null) {
                    // Field coord: resolver has edges to resolver(c) and/or checker(c)
                    if (!isChecker) {
                        if (resolverNodes.containsKey(c)) add(c to false)
                        if (checkerNodes.containsKey(c)) add(c to true)
                    } else {
                        // Checker only has edges to resolver(c)
                        if (resolverNodes.containsKey(c)) add(c to false)
                    }
                } else {
                    // Type coord (typeName, null): resolver has edges to checker(c) only
                    if (!isChecker) {
                        if (checkerNodes.containsKey(c)) add(c to true)
                    }
                    // Checker: no edges for type coords
                }
            }
        }
    }

    /**
     * DFS for cycle detection.
     *
     * @return the cycle path if a cycle is found, null otherwise
     */
    private fun dfs(
        node: GraphNode,
        path: MutableList<GraphNode>,
        visiting: MutableSet<GraphNode>,
        visited: MutableSet<GraphNode>,
    ): List<GraphNode>? {
        if (node in visiting) {
            val cycleStart = path.indexOf(node)
            return path.subList(cycleStart, path.size) + node
        }

        path.add(node)
        visiting.add(node)

        for (edge in getEdges(node)) {
            if (edge !in visited) {
                dfs(edge, path, visiting, visited)?.let { return it }
            }
        }

        path.removeLast()
        visiting.remove(node)
        visited.add(node)
        return null
    }

    /**
     * Returns the concrete graph edges from [node].
     *
     * For a resolver node:
     * - Field coord (S, g): edges to all resolver nodes at (S, g) + all checker nodes at (S, g)
     * - Type coord (S, null): edges to all checker nodes at (S, null)
     *
     * For a checker node:
     * - Field coord (S, g): edges to all resolver nodes at (S, g) only
     * - Type coord (S, null): no edges
     */
    private fun getEdges(node: GraphNode): List<GraphNode> =
        buildList {
            for (c in node.selectedCoords) {
                val fieldName = c.second
                if (fieldName != null) {
                    if (!node.isChecker) {
                        resolverNodes[c]?.forEach { add(it) }
                        checkerNodes[c]?.forEach { add(it) }
                    } else {
                        resolverNodes[c]?.forEach { add(it) }
                    }
                } else {
                    if (!node.isChecker) {
                        checkerNodes[c]?.forEach { add(it) }
                    }
                }
            }
        }

    private fun GraphNode.toPath(): String {
        val (typeName, fieldName) = coord
        return if (fieldName == null) typeName else "$typeName.$fieldName"
    }
}

/** Thrown by [RequiredSelectionSetGraph.assertAcyclic] when a cycle is detected in the dependency graph. */
class RequiredSelectionsCycleException(val path: List<String>) : Exception() {
    override val message: String
        get() {
            val pathString = path.joinToString(" -> ")
            return "Cyclic @Resolver selections detected in path: $pathString"
        }
}
