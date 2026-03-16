package viaduct.engine.runtime.tenantloading

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows

class RequiredSelectionSetGraphTest {
    private fun RequiredSelectionsCycleException.assertErrorPath(vararg expectedPath: String) {
        assertEquals(expectedPath.toList(), this.path.toList())
    }

    @Test
    fun `valid -- empty graph`() {
        assertDoesNotThrow { RequiredSelectionSetGraph().assertAcyclic() }
    }

    @Test
    fun `valid -- single resolver with no dependencies`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", emptySet())
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- resolver depends on sibling`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- chained resolvers with no cycle`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addResolverNode("T" to "b", setOf("T" to "c"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- field checker references self`() {
        // Checkers only edge to resolvers (not other checkers), so self-reference is not a cycle
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Subject" to "x", setOf("Subject" to "x"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- two field checkers mutually select each other`() {
        // checker(x) selects y, checker(y) selects x — checkers don't edge to checkers, so no cycle
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addCheckerNode("Subject" to "y", setOf("Subject" to "x"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- mixed field checker and resolver with no cycle`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addResolverNode("Subject" to "y", setOf("Subject" to "z"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- resolver selecting type coord without cycle`() {
        // resolver(x) selects type coord (T, null), but no type checker exists, so no edge
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to null, "T" to "y"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- type checker coord produces no edges from checker node`() {
        // type coord (T, null) in a checker's objectCoords produces no edges, so no cycle
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Subject" to "x", setOf("Subject" to null))
        graph.addCheckerNode("Subject" to null, setOf("Subject" to "x"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- multiple RSSes for same coordinate`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- duplicate registrations with chained resolver`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to "y"))
        graph.addResolverNode("T" to "x", setOf("T" to "y"))
        graph.addResolverNode("T" to "y", setOf("T" to "z"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- different selections for same coordinate`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to "y"))
        graph.addResolverNode("T" to "x", setOf("T" to "y", "T" to "z"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- multiple coordinates each with duplicate registrations`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to "y"))
        graph.addResolverNode("T" to "x", setOf("T" to "y"))
        graph.addResolverNode("T" to "y", setOf("T" to "z"))
        graph.addResolverNode("T" to "y", setOf("T" to "z"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- field checker references self with resolver at same coordinate`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("T" to "x", setOf("T" to "x"))
        graph.addResolverNode("T" to "x", setOf("T" to "y"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- checker with multiple selected coordinates`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("T" to "x", setOf("T" to "y", "T" to "z"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- multiple checkers for same coordinate`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("T" to "x", setOf("T" to "y"))
        graph.addCheckerNode("T" to "x", setOf("T" to "z"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `valid -- checker and resolver on independent coordinates`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("T" to "x", setOf("T" to "y"))
        graph.addResolverNode("T" to "z", setOf("T" to "y"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `invalid -- self loop`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "x"))
        assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
    }

    @Test
    fun `invalid -- two-node cycle`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addResolverNode("Subject" to "y", setOf("Subject" to "x"))
        assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
    }

    @Test
    fun `invalid -- three-node cycle`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addResolverNode("T" to "b", setOf("T" to "c"))
        graph.addResolverNode("T" to "c", setOf("T" to "a"))
        assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
    }

    @Test
    fun `invalid -- checker-resolver cycle`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addResolverNode("Subject" to "y", setOf("Subject" to "x"))
        assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
    }

    @Test
    fun `invalid -- cycle through type checker`() {
        // resolver(x) selects type coord (T, null); checker(T, null) selects x via field coord
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to null))
        graph.addCheckerNode("T" to null, setOf("T" to "x"))
        assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
    }

    @Test
    fun `invalid -- multiple RSSes for same coordinate with cycle in one`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addResolverNode("Subject" to "y", setOf("Subject" to "x"))
        assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
    }

    @Test
    fun `error path -- two-node cycle`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))
        graph.addResolverNode("Subject" to "y", setOf("Subject" to "x"))
        val err = assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
        err.assertErrorPath("Subject.x", "Subject.y", "Subject.x")
    }

    @Test
    fun `error path -- cross-type cycle`() {
        // Mirrors RequiredSelectionsAreAcyclicTest's "error path -- selection set traversal":
        //   resolver(Foo.x) selects bar { x } -> objectCoords = {(Bar, null), (Bar, x)}
        //   resolver(Bar.x) selects foo { x } -> objectCoords = {(Foo, null), (Foo, x)}
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Foo" to "x", setOf("Bar" to null, "Bar" to "x"))
        graph.addResolverNode("Bar" to "x", setOf("Foo" to null, "Foo" to "x"))
        val err = assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
        err.assertErrorPath("Foo.x", "Bar.x", "Foo.x")
    }

    @Test
    fun `error path -- longer cycle path includes all intermediate nodes`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addResolverNode("T" to "b", setOf("T" to "c"))
        graph.addResolverNode("T" to "c", setOf("T" to "d"))
        graph.addResolverNode("T" to "d", setOf("T" to "a"))
        val err = assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
        err.assertErrorPath("T.a", "T.b", "T.c", "T.d", "T.a")
    }

    @Test
    fun `error path -- cycle through type checker uses type name only`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to null))
        graph.addCheckerNode("T" to null, setOf("T" to "x"))
        val err = assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
        err.assertErrorPath("T.x", "T", "T.x")
    }

    @Test
    fun `getBlockedCoordinates -- empty graph, resolver always blocks self`() {
        val graph = RequiredSelectionSetGraph()
        val blocked = graph.getBlockedCoordinates("Subject" to "x", isChecker = false)
        assertTrue(
            ("Subject" to "x") in blocked,
            "Resolver should always block selecting its own coordinate"
        )
    }

    @Test
    fun `getBlockedCoordinates -- empty graph, checker blocks nothing`() {
        val graph = RequiredSelectionSetGraph()
        val blocked = graph.getBlockedCoordinates("Subject" to "x", isChecker = true)
        assertTrue(
            blocked.isEmpty(),
            "Checker with no graph should have no blocked coordinates"
        )
    }

    @Test
    fun `getBlockedCoordinates -- resolver blocks predecessor resolver`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))

        val blocked = graph.getBlockedCoordinates("Subject" to "y", isChecker = false)
        assertTrue(
            ("Subject" to "x") in blocked,
            "resolver(Subject, y) should block selecting (Subject, x) since resolver(Subject, x) selects (Subject, y)"
        )
        assertTrue(("Subject" to "y") in blocked)
    }

    @Test
    fun `getBlockedCoordinates -- checker blocks resolver predecessor`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("Subject" to "x", setOf("Subject" to "y"))

        val blocked = graph.getBlockedCoordinates("Subject" to "y", isChecker = true)
        assertTrue(
            ("Subject" to "x") in blocked,
            "checker(Subject, y) should block (Subject, x) since resolver(Subject, x) selects Subject.y (a checker dependency)"
        )
    }

    @Test
    fun `getBlockedCoordinates -- resolver blocks type traversal that creates cycle via type checker`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Subject" to null, setOf("Subject" to "x"))
        graph.addResolverNode("Subject" to "x", setOf("Subject" to null))

        val blocked = graph.getBlockedCoordinates("Subject" to "x", isChecker = false)
        assertTrue(
            ("Subject" to null) in blocked,
            "resolver(Subject, x) should block type traversal into Subject since " +
                "checker(Subject, null) can reach resolver(Subject, x)"
        )
    }

    @Test
    fun `getBlockedCoordinates -- checker does not block checker ancestor at field coord`() {
        // checker(x) selects field coord (y). A new checker at (y) should NOT be blocked
        // from selecting (x), because checker(x) only has edges to resolver(y), not checker(y).
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("T" to "x", setOf("T" to "y"))

        val blocked = graph.getBlockedCoordinates("T" to "y", isChecker = true)
        assertTrue(
            ("T" to "x") !in blocked,
            "checker(T, y) should not block (T, x) since checker(T, x) only has edges to resolvers"
        )
    }

    @Test
    fun `getBlockedCoordinates -- checker does not block type coords`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Subject" to null, setOf("Subject" to "x"))

        val blocked = graph.getBlockedCoordinates("Subject" to "x", isChecker = true)
        assertTrue(
            ("Subject" to null) !in blocked,
            "Checker should never block type coordinates"
        )
    }

    @Test
    fun `getBlockedCoordinates -- new checker blocked by resolver that selects coord with existing checker`() {
        // resolver(T, a) selects (T, b), and checker(T, b) also exists. The abstract graph
        // has an edge from resolver(T, a) to checker(T, b), so resolver(T, a) is an ancestor
        // of checker(T, b). A new checker at (T, b) should block (T, a).
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addCheckerNode("T" to "b", setOf("T" to "c"))

        val blocked = graph.getBlockedCoordinates("T" to "b", isChecker = true)
        assertTrue(
            ("T" to "a") in blocked,
            "resolver(T, a) reaches checker(T, b) via abstract edge, so (T, a) should be blocked"
        )
    }

    @Test
    fun `getBlockedCoordinates -- transitive ancestors are blocked`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addResolverNode("T" to "b", setOf("T" to "c"))

        val blocked = graph.getBlockedCoordinates("T" to "c", isChecker = false)
        assertTrue(("T" to "b") in blocked)
        assertTrue(("T" to "a") in blocked)
        assertTrue(("T" to "c") in blocked)
    }

    @Test
    fun `getBlockedCoordinates -- type checker coord itself is blocked as type coord for resolvers`() {
        val graph = RequiredSelectionSetGraph()
        graph.addCheckerNode("Foo" to null, setOf("Foo" to "x"))
        graph.addResolverNode("Foo" to "x", setOf("Foo" to null))

        val blocked = graph.getBlockedCoordinates("Foo" to "x", isChecker = false)
        assertTrue(("Foo" to null) in blocked)
    }

    @Test
    fun `getBlockedCoordinates -- type checker target blocks nothing`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to "y"))

        val blocked = graph.getBlockedCoordinates("T" to null, isChecker = true)
        assertTrue(
            blocked.isEmpty(),
            "A type checker should never have blocked coordinates since type coords produce no edges"
        )
    }

    @Test
    fun `getBlockedCoordinates -- multiple RSSes for same coord unioned in abstract graph`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addResolverNode("T" to "a", setOf("T" to "c"))

        val blockedForB = graph.getBlockedCoordinates("T" to "b", isChecker = false)
        assertTrue(
            ("T" to "a") in blockedForB,
            "(T, a) should be blocked for resolver(T, b) since one RSS for resolver(T, a) selects (T, b)"
        )
        val blockedForC = graph.getBlockedCoordinates("T" to "c", isChecker = false)
        assertTrue(
            ("T" to "a") in blockedForC,
            "(T, a) should be blocked for resolver(T, c) since another RSS for resolver(T, a) selects (T, c)"
        )
    }

    @Test
    fun `getBlockedCoordinates -- mixed field and type coord ancestors blocked simultaneously`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "x", setOf("T" to "y"))
        graph.addCheckerNode("T" to null, setOf("T" to "y"))

        val blocked = graph.getBlockedCoordinates("T" to "y", isChecker = false)
        assertTrue(
            ("T" to "x") in blocked,
            "(T, x) should be blocked: resolver(T, x) selects (T, y) [field coord ancestor]"
        )
        assertTrue(
            ("T" to null) in blocked,
            "(T, null) should be blocked: checker(T, null) selects (T, y) [type coord ancestor]"
        )
    }

    @Test
    fun `getBlockedCoordinates -- blocked coord causes cycle when registered`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addResolverNode("T" to "b", setOf("T" to "c"))

        val blocked = graph.getBlockedCoordinates("T" to "c", isChecker = false)
        assertTrue(("T" to "b") in blocked)

        graph.addResolverNode("T" to "c", setOf("T" to "b"))
        assertThrows<RequiredSelectionsCycleException> { graph.assertAcyclic() }
    }

    @Test
    fun `getBlockedCoordinates -- unblocked coord is safe to register`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))
        graph.addResolverNode("T" to "b", setOf("T" to "c"))

        val blocked = graph.getBlockedCoordinates("T" to "c", isChecker = false)
        // (T, d) is not in the blocked set
        assertTrue(("T" to "d") !in blocked)

        // Registering with an unblocked coord should not create a cycle
        graph.addResolverNode("T" to "c", setOf("T" to "d"))
        assertDoesNotThrow { graph.assertAcyclic() }
    }

    @Test
    fun `getBlockedCoordinates -- results update after adding new nodes`() {
        val graph = RequiredSelectionSetGraph()
        graph.addResolverNode("T" to "a", setOf("T" to "b"))

        // Before adding the b->c edge, c has no ancestors beyond self-block
        val blockedBefore = graph.getBlockedCoordinates("T" to "c", isChecker = false)
        assertEquals(setOf("T" to "c"), blockedBefore)

        // Add b->c edge; now a is transitively an ancestor of c
        graph.addResolverNode("T" to "b", setOf("T" to "c"))

        val blockedAfter = graph.getBlockedCoordinates("T" to "c", isChecker = false)
        assertTrue(
            ("T" to "a") in blockedAfter,
            "After adding resolver(T, b) -> (T, c), resolver(T, a) should be a transitive ancestor of (T, c)"
        )
        assertTrue(("T" to "b") in blockedAfter)
        assertTrue(("T" to "c") in blockedAfter)
    }
}
