package viaduct.graphql.schema.test

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductReverseSchema
import viaduct.graphql.schema.ViaductSchema

/**
 * A contract test suite for [ViaductReverseSchema] implementations.
 *
 * This interface provides a comprehensive set of JUnit 5 tests that verify
 * the behavioral correctness of any [ViaductReverseSchema] implementation.
 * Implementers need only provide a [createReverseSchema] factory method,
 * and they receive extensive test coverage for:
 *
 * - Type reference edges (field, field arg, directive arg)
 * - Implements edges
 * - Union membership edges
 * - Directive application edges
 * - `inboundDefs` completeness
 * - `referencingTopLevelDefs` containment chain
 *
 * ## Usage
 *
 * ```kotlin
 * class DefaultReverseSchemaContractTest : ViaductReverseSchemaContract {
 *     override fun createReverseSchema(sdl: String): ViaductReverseSchema {
 *         val schema = createSchema(sdl)
 *         return ViaductReverseSchema.from(schema)
 *     }
 * }
 * ```
 */
interface ViaductReverseSchemaContract {
    /**
     * Create a [ViaductReverseSchema] from the given SDL.
     * Implementations provide their own factory; the default
     * implementation can use [ViaductReverseSchema.from].
     */
    fun createReverseSchema(sdl: String): ViaductReverseSchema

    // =========================================================================
    // Type references — field.type.baseTypeDef
    // =========================================================================

    @Test
    fun `field type reference produces inbound field`() {
        val rev = createReverseSchema(
            """
            type Foo { bar: Baz }
            type Baz { x: String }
            type Query { foo: Foo }
            """.trimIndent()
        )
        val baz = rev.schema.types["Baz"]!!
        val fields = rev.inboundFields(baz)
        assertEquals(1, fields.size, "inboundFields(Baz)")
        assertEquals("bar", fields.first().name)

        // Foo.bar should also appear in inboundDefs
        assertTrue(rev.inboundDefs(baz).any { it is ViaductSchema.Field && it.name == "bar" })
    }

    @Test
    fun `field type references across scalar, enum, object, interface, input, union targets`() {
        val rev = createReverseSchema(
            """
            scalar Custom
            enum E { A }
            interface I { id: ID! }
            input In { x: Int }
            type Obj implements I { id: ID! }
            union U = Obj
            type Query {
                s: Custom
                e: E
                i: I
                o: Obj
                u: U
                f(input: In): String
            }
            """.trimIndent()
        )
        // Each type referenced by a field (or field arg) in Query
        for (name in listOf("Custom", "E", "I", "Obj", "U")) {
            val typeDef = rev.schema.types[name]!!
            assertTrue(
                rev.inboundFields(typeDef).isNotEmpty(),
                "Expected inboundFields for $name"
            )
        }
        // Input referenced by field arg
        val input = rev.schema.types["In"]!!
        assertTrue(rev.inboundFieldArgs(input).isNotEmpty(), "inboundFieldArgs(In)")
    }

    @Test
    fun `field arg type reference produces inbound field arg`() {
        val rev = createReverseSchema(
            """
            input Filter { x: Int }
            type Query { search(filter: Filter): String }
            """.trimIndent()
        )
        val filter = rev.schema.types["Filter"]!!
        val args = rev.inboundFieldArgs(filter)
        assertEquals(1, args.size)
        assertEquals("filter", args.first().name)
    }

    @Test
    fun `directive arg type reference produces inbound directive arg`() {
        val rev = createReverseSchema(
            """
            input Config { x: Int }
            directive @d(config: Config) on FIELD_DEFINITION
            type Query { field: String }
            """.trimIndent()
        )
        val config = rev.schema.types["Config"]!!
        val args = rev.inboundDirectiveArgs(config)
        assertEquals(1, args.size)
        assertEquals("config", args.first().name)
    }

    @Test
    fun `list and nullable type expressions reference base type`() {
        val rev = createReverseSchema(
            """
            type Foo { x: String }
            type Query {
                nullable: Foo
                nonNull: Foo!
                list: [Foo]
                listNonNull: [Foo!]!
            }
            """.trimIndent()
        )
        val foo = rev.schema.types["Foo"]!!
        val fieldNames = rev.inboundFields(foo).map { it.name }.toSet()
        assertEquals(setOf("nullable", "nonNull", "list", "listNonNull"), fieldNames)
    }

    // =========================================================================
    // Implements edges — outputRecord.supers
    // =========================================================================

    @Test
    fun `object implementing interface produces inbound type def`() {
        val rev = createReverseSchema(
            """
            interface Node { id: ID! }
            type User implements Node { id: ID!, name: String }
            type Query { user: User }
            """.trimIndent()
        )
        val node = rev.schema.types["Node"]!!
        val typeDefs = rev.inboundTypeDefs(node)
        assertEquals(1, typeDefs.size)
        assertEquals("User", typeDefs.first().name)
    }

    @Test
    fun `object implementing multiple interfaces`() {
        val rev = createReverseSchema(
            """
            interface A { a: String }
            interface B { b: String }
            type Obj implements A & B { a: String, b: String }
            type Query { obj: Obj }
            """.trimIndent()
        )
        val a = rev.schema.types["A"]!!
        val b = rev.schema.types["B"]!!
        assertEquals(setOf("Obj"), rev.inboundTypeDefs(a).map { it.name }.toSet())
        assertEquals(setOf("Obj"), rev.inboundTypeDefs(b).map { it.name }.toSet())
    }

    @Test
    fun `interface implementing interface - syntactic vs semantic`() {
        val rev = createReverseSchema(
            """
            interface Base { id: ID! }
            interface Sub implements Base { id: ID!, extra: String }
            type Obj implements Sub & Base { id: ID!, extra: String }
            type Query { obj: Obj }
            """.trimIndent()
        )
        val base = rev.schema.types["Base"]!!
        val typeDefNames = rev.inboundTypeDefs(base).map { it.name }.toSet()
        // Syntactic: both Sub (interface) and Obj (object) list Base in implements
        assertTrue(typeDefNames.contains("Sub"), "Sub should be in inboundTypeDefs(Base)")
        assertTrue(typeDefNames.contains("Obj"), "Obj should be in inboundTypeDefs(Base)")
    }

    @Test
    fun `extension adding implements clause`() {
        val rev = createReverseSchema(
            """
            interface Node { id: ID! }
            interface Named { name: String }
            type User implements Node { id: ID! }
            extend type User implements Named { name: String }
            type Query { user: User }
            """.trimIndent()
        )
        val named = rev.schema.types["Named"]!!
        assertEquals(
            setOf("User"),
            rev.inboundTypeDefs(named).map { it.name }.toSet()
        )
    }

    // =========================================================================
    // Union membership edges — union.possibleObjectTypes
    // =========================================================================

    @Test
    fun `union members produce inbound type defs on the member objects`() {
        val rev = createReverseSchema(
            """
            type Cat { meow: String }
            type Dog { bark: String }
            union Pet = Cat | Dog
            type Query { pet: Pet }
            """.trimIndent()
        )
        val cat = rev.schema.types["Cat"]!!
        val dog = rev.schema.types["Dog"]!!
        assertEquals(setOf("Pet"), rev.inboundTypeDefs(cat).map { it.name }.toSet())
        assertEquals(setOf("Pet"), rev.inboundTypeDefs(dog).map { it.name }.toSet())
    }

    @Test
    fun `union extension adding members`() {
        val rev = createReverseSchema(
            """
            type A { a: String }
            type B { b: String }
            union U = A
            extend union U = B
            type Query { u: U }
            """.trimIndent()
        )
        val b = rev.schema.types["B"]!!
        assertEquals(setOf("U"), rev.inboundTypeDefs(b).map { it.name }.toSet())
    }

    @Test
    fun `object in multiple unions`() {
        val rev = createReverseSchema(
            """
            type A { x: String }
            type B { y: String }
            union U1 = A
            union U2 = A | B
            type Query { u1: U1, u2: U2 }
            """.trimIndent()
        )
        val a = rev.schema.types["A"]!!
        val unionNames = rev.inboundTypeDefs(a).map { it.name }.toSet()
        assertEquals(setOf("U1", "U2"), unionNames)
    }

    // =========================================================================
    // Directive application edges — def.appliedDirectives[].directive
    // =========================================================================

    @Test
    fun `directive applied to type definitions`() {
        val rev = createReverseSchema(
            """
            directive @d on OBJECT | INTERFACE | ENUM | INPUT_OBJECT | SCALAR | UNION
            scalar S @d
            enum E @d { A }
            input I @d { x: Int }
            interface Iface @d { x: String }
            type Obj implements Iface @d { x: String }
            union U @d = Obj
            type Query { s: S, e: E, i: Iface, o: Obj, u: U, f(input: I): String }
            """.trimIndent()
        )
        val d = rev.schema.directives["d"]!!
        val names = rev.inboundDefs(d).map { (it as ViaductSchema.TypeDef).name }.toSet()
        assertEquals(setOf("S", "E", "I", "Iface", "Obj", "U"), names)
    }

    @Test
    fun `directive applied to field and field arg`() {
        val rev = createReverseSchema(
            """
            directive @d on FIELD_DEFINITION | ARGUMENT_DEFINITION
            type Query { f(a: Int @d): String @d }
            """.trimIndent()
        )
        val d = rev.schema.directives["d"]!!
        val defs = rev.inboundDefs(d)
        assertTrue(defs.any { it is ViaductSchema.Field && it.name == "f" }, "field")
        assertTrue(defs.any { it is ViaductSchema.FieldArg && it.name == "a" }, "arg")
    }

    @Test
    fun `directive applied to enum value`() {
        val rev = createReverseSchema(
            """
            directive @d on ENUM_VALUE
            enum E { V1 @d, V2 }
            type Query { e: E }
            """.trimIndent()
        )
        val d = rev.schema.directives["d"]!!
        val defs = rev.inboundDefs(d)
        assertTrue(
            defs.any { it is ViaductSchema.EnumValue && it.name == "V1" },
            "V1 should reference @d"
        )
        assertTrue(
            defs.none { it is ViaductSchema.EnumValue && it.name == "V2" },
            "V2 should not reference @d"
        )
    }

    @Test
    fun `directive application on directive arg`() {
        val rev = createReverseSchema(
            """
            directive @meta(info: String!) on ARGUMENT_DEFINITION
            directive @validated(min: Int @meta(info: "minimum")) on FIELD_DEFINITION
            type Query { field: String }
            """.trimIndent()
        )
        val meta = rev.schema.directives["meta"]!!
        val validated = rev.schema.directives["validated"]!!
        val minArg = validated.args.first { it.name == "min" }

        assertTrue(
            rev.inboundDefs(meta).any { it === minArg },
            "@validated.min should appear in inboundDefs(@meta)"
        )
    }

    @Test
    fun `directive arg type reference to enum produces inbound directive arg`() {
        // Tests the type-reference edge for directive args with a non-scalar type.
        val rev = createReverseSchema(
            """
            enum Status { A, B }
            directive @filter(status: Status) on FIELD_DEFINITION
            type Query { field: String }
            """.trimIndent()
        )
        val status = rev.schema.types["Status"]!!
        val args = rev.inboundDirectiveArgs(status)
        assertEquals(1, args.size, "directive arg should reference Status")
        assertEquals("status", args.first().name)
    }

    @Test
    fun `repeatable directive applied multiple times`() {
        val rev = createReverseSchema(
            """
            directive @tag(name: String!) repeatable on FIELD_DEFINITION
            type Query { f: String @tag(name: "a") @tag(name: "b") }
            """.trimIndent()
        )
        val tag = rev.schema.directives["tag"]!!
        // The field appears once in inboundDefs (deduplicated by identity set)
        val defs = rev.inboundDefs(tag)
        assertEquals(1, defs.size, "field should appear once despite two @tag applications")
        assertTrue(defs.first() is ViaductSchema.Field)
    }

    // =========================================================================
    // inboundDefs completeness
    // =========================================================================

    @Test
    fun `inboundDefs is union of all filtered methods`() {
        val rev = createReverseSchema(
            """
            interface I { id: ID! }
            type Obj implements I { id: ID!, ref: I }
            union U = Obj
            directive @d on OBJECT
            type Tagged implements I @d { id: ID! }
            type Query { obj: Obj, u: U, tagged: Tagged }
            """.trimIndent()
        )
        val i = rev.schema.types["I"]!!
        val allInbound = rev.inboundDefs(i).toSet()
        val fields = rev.inboundFields(i).toSet()
        val fieldArgs = rev.inboundFieldArgs(i).toSet()
        val directiveArgs = rev.inboundDirectiveArgs(i).toSet()
        val typeDefs = rev.inboundTypeDefs(i).toSet()

        val reassembled = fields + fieldArgs + directiveArgs + typeDefs
        assertEquals(
            allInbound,
            reassembled,
            "inboundDefs should equal union of filtered methods"
        )
    }

    @Test
    fun `empty results for target with no inbound references`() {
        val rev = createReverseSchema(
            """
            type Orphan { x: String }
            type Query { field: String }
            """.trimIndent()
        )
        val orphan = rev.schema.types["Orphan"]!!
        assertTrue(rev.inboundDefs(orphan).isEmpty())
        assertTrue(rev.inboundFields(orphan).isEmpty())
        assertTrue(rev.inboundTypeDefs(orphan).isEmpty())
    }

    @Test
    fun `empty results for structurally meaningless queries`() {
        val rev = createReverseSchema(
            """
            type Query { f: String }
            """.trimIndent()
        )
        val field = (rev.schema.types["Query"]!! as ViaductSchema.Record).field("f")!!
        // Fields are never the base type of another field's type expression
        assertTrue(rev.inboundFields(field).isEmpty())
        assertTrue(rev.inboundFieldArgs(field).isEmpty())
        assertTrue(rev.inboundTypeDefs(field).isEmpty())
    }

    // =========================================================================
    // referencingTopLevelDefs
    // =========================================================================

    @Test
    fun `field reference collapses to containing record`() {
        val rev = createReverseSchema(
            """
            type Foo { bar: Baz }
            type Baz { x: String }
            type Query { foo: Foo }
            """.trimIndent()
        )
        val baz = rev.schema.types["Baz"]!! as ViaductSchema.TopLevelDef
        val refs = rev.referencingTopLevelDefs(baz)
        assertEquals(setOf("Foo"), refs.map { it.name }.toSet())
    }

    @Test
    fun `field arg reference collapses to containing record - two hops`() {
        val rev = createReverseSchema(
            """
            input Filter { x: Int }
            type Query { search(f: Filter): String }
            """.trimIndent()
        )
        val filter = rev.schema.types["Filter"]!! as ViaductSchema.TopLevelDef
        val refs = rev.referencingTopLevelDefs(filter)
        assertEquals(setOf("Query"), refs.map { it.name }.toSet())
    }

    @Test
    fun `directive arg reference collapses to containing directive`() {
        val rev = createReverseSchema(
            """
            input Config { x: Int }
            directive @d(c: Config) on FIELD_DEFINITION
            type Query { field: String }
            """.trimIndent()
        )
        val config = rev.schema.types["Config"]!! as ViaductSchema.TopLevelDef
        val refs = rev.referencingTopLevelDefs(config)
        assertEquals(setOf("d"), refs.map { it.name }.toSet())
    }

    @Test
    fun `implements reference collapses to itself`() {
        val rev = createReverseSchema(
            """
            interface Node { id: ID! }
            type User implements Node { id: ID!, name: String }
            type Query { user: User }
            """.trimIndent()
        )
        val node = rev.schema.types["Node"]!! as ViaductSchema.TopLevelDef
        val refs = rev.referencingTopLevelDefs(node)
        assertTrue(refs.map { it.name }.contains("User"))
    }

    @Test
    fun `union membership reference collapses to itself`() {
        val rev = createReverseSchema(
            """
            type Cat { meow: String }
            union Pet = Cat
            type Query { pet: Pet }
            """.trimIndent()
        )
        val cat = rev.schema.types["Cat"]!! as ViaductSchema.TopLevelDef
        val refs = rev.referencingTopLevelDefs(cat)
        assertEquals(setOf("Pet"), refs.map { it.name }.toSet())
    }

    @Test
    fun `enum value directive application collapses to containing enum`() {
        val rev = createReverseSchema(
            """
            directive @d on ENUM_VALUE
            enum E { V1 @d }
            type Query { e: E }
            """.trimIndent()
        )
        val d = rev.schema.directives["d"]!! as ViaductSchema.TopLevelDef
        val refs = rev.referencingTopLevelDefs(d)
        assertEquals(setOf("E"), refs.map { it.name }.toSet())
    }

    @Test
    fun `deduplication - multiple fields of same record referencing same target`() {
        val rev = createReverseSchema(
            """
            type Target { x: String }
            type Source { a: Target, b: Target, c: Target }
            type Query { source: Source }
            """.trimIndent()
        )
        val target = rev.schema.types["Target"]!! as ViaductSchema.TopLevelDef
        val refs = rev.referencingTopLevelDefs(target)
        assertEquals(1, refs.size, "Source should appear only once despite 3 fields referencing Target")
        assertEquals("Source", refs.first().name)
    }

    // =========================================================================
    // Edge cases
    // =========================================================================

    @Test
    fun `ViaductSchema Empty produces a valid reverse schema`() {
        // ViaductSchema.Empty has no types and no directives, so there
        // are no defs to query.  The test verifies construction succeeds
        // and the schema reference is preserved.
        val rev = ViaductReverseSchema.from(ViaductSchema.Empty)
        assertTrue(rev.schema === ViaductSchema.Empty)
    }

    @Test
    fun `unreferenced type has empty reverse results for all methods`() {
        val rev = createReverseSchema("type Query { f: String }")
        val query = rev.schema.types["Query"]!!

        assertTrue(rev.inboundDefs(query).isEmpty(), "inboundDefs should be empty for unreferenced type")
        assertTrue(rev.inboundFields(query).isEmpty(), "inboundFields should be empty")
        assertTrue(rev.inboundFieldArgs(query).isEmpty(), "inboundFieldArgs should be empty")
        assertTrue(rev.inboundDirectiveArgs(query).isEmpty(), "inboundDirectiveArgs should be empty")
        assertTrue(rev.inboundTypeDefs(query).isEmpty(), "inboundTypeDefs should be empty")
        assertTrue(
            rev.referencingTopLevelDefs(query as ViaductSchema.TopLevelDef).isEmpty(),
            "referencingTopLevelDefs should be empty"
        )
    }

    @Test
    fun `self-referential type`() {
        val rev = createReverseSchema(
            """
            type Node { children: [Node] }
            type Query { root: Node }
            """.trimIndent()
        )
        val node = rev.schema.types["Node"]!!
        // Node.children references Node, so Node should have an inbound field
        val fields = rev.inboundFields(node)
        assertTrue(fields.any { it.name == "children" })

        // referencingTopLevelDefs should include Node itself (self-ref)
        val refs = rev.referencingTopLevelDefs(node as ViaductSchema.TopLevelDef)
        assertTrue(refs.any { it.name == "Node" })
    }

    // =========================================================================
    // Schema affinity — cross-schema errors
    // =========================================================================

    @Test
    fun `inboundDefs throws for def from different schema`() {
        val rev = createReverseSchema("type Query { f: String }")
        val otherRev = createReverseSchema("type Other { x: Int }\ntype Query { o: Other }")
        val otherType = otherRev.schema.types["Other"]!!
        assertThrows<IllegalArgumentException> { rev.inboundDefs(otherType) }
    }

    @Test
    fun `inboundFields throws for def from different schema`() {
        val rev = createReverseSchema("type Query { f: String }")
        val otherRev = createReverseSchema("type Other { x: Int }\ntype Query { o: Other }")
        val otherType = otherRev.schema.types["Other"]!!
        assertThrows<IllegalArgumentException> { rev.inboundFields(otherType) }
    }

    @Test
    fun `referencingTopLevelDefs throws for def from different schema`() {
        val rev = createReverseSchema("type Query { f: String }")
        val otherRev = createReverseSchema("type Other { x: Int }\ntype Query { o: Other }")
        val otherType = otherRev.schema.types["Other"]!! as ViaductSchema.TopLevelDef
        assertThrows<IllegalArgumentException> { rev.referencingTopLevelDefs(otherType) }
    }
}
