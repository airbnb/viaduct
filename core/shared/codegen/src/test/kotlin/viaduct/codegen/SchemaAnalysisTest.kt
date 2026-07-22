package viaduct.codegen

import graphql.schema.idl.SchemaParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.graphql.schema.ViaductSchema
import viaduct.graphql.schema.graphqljava.extensions.fromTypeDefinitionRegistry

/**
 * Behavior-driven tests for the language-neutral [SchemaAnalysis] predicates.
 *
 * Schemas are built directly from SDL via [ViaductSchema.fromTypeDefinitionRegistry], the same
 * pattern used by the codegen golden tests. Because that path is a *raw* (parsed-but-unvalidated)
 * schema, every applied directive must be declared in the SDL, so the fixtures below declare
 * `@resolver`, `@idOf`, `@connection`, and `@edge` inline (mirroring the framework's defaults).
 */
class SchemaAnalysisTest {
    // ---------------------------------------------------------------------------------------------
    // Fixtures
    // ---------------------------------------------------------------------------------------------

    /**
     * Shared schema exercising most predicates: the Node interface, a direct Node implementor, a
     * transitive Node (interface-extends-Node, object-implements-that-interface), non-Node types,
     * BackingData, `@idOf`, `@resolver`, `@connection`/`@edge`, and a spread of field type shapes.
     */
    private val schema: ViaductSchema = parse(
        """
        directive @resolver(isSelective: Boolean! = false, isBatching: Boolean! = false)
            on OBJECT | FIELD_DEFINITION
        directive @idOf(type: String!) on FIELD_DEFINITION | INPUT_FIELD_DEFINITION | ARGUMENT_DEFINITION
        directive @connection on OBJECT
        directive @edge on OBJECT
        directive @oneOf on INPUT_OBJECT

        interface Node { id: ID! }

        scalar BackingData

        # A direct Node implementor.
        type Entity implements Node {
            id: ID!
            name: String
            backing: BackingData
            owner: ID @idOf(type: "Entity")
        }

        # Transitive Node: interface extends Node, object implements that interface.
        interface Identifiable implements Node { id: ID! }
        type Widget implements Identifiable & Node {
            id: ID!
        }

        # A plain object (not a Node) and a non-Node interface.
        type Plain {
            scalarField: String
            nonNullScalar: String!
            listField: [String]
            listOfNonNull: [String!]
        }
        interface Named { name: String }

        type Selective { x: Int @resolver(isSelective: true) }
        type Batching { x: Int @resolver(isBatching: true) }
        type Defaulted { x: Int @resolver }
        type Plainfield { x: Int }

        type WidgetConnection @connection {
            edges: [WidgetEdge]
        }
        type WidgetEdge @edge {
            cursor: String!
            node: Widget
        }
        # A @connection with no `edges` field, and an @edge with no `node` field.
        type EmptyConnection @connection {
            nope: Int
        }
        type BadEdge @edge {
            cursor: String!
        }

        enum Color { RED }
        input Filter { term: String }
        input OneOfFilter @oneOf { byId: ID, byName: String }
        union Searchable = Entity | Plain

        type Query { entity: Entity }
        """
    )

    /** Separate schema to exercise the legacy `selective` alias for `isSelective`. */
    private val legacyResolverSchema: ViaductSchema = parse(
        """
        directive @resolver(selective: Boolean! = false) on FIELD_DEFINITION | OBJECT
        type Query { x: Int @resolver(selective: true) }
        """
    )

    private fun parse(sdl: String): ViaductSchema = ViaductSchema.fromTypeDefinitionRegistry(SchemaParser().parse(sdl.trimIndent()))

    private fun ViaductSchema.type(name: String): ViaductSchema.TypeDef = types[name] ?: error("Type $name not found in test schema")

    private fun ViaductSchema.obj(name: String): ViaductSchema.Object = type(name) as ViaductSchema.Object

    private fun ViaductSchema.field(
        typeName: String,
        fieldName: String,
    ): ViaductSchema.Field =
        (type(typeName) as ViaductSchema.Record).field(fieldName)
            ?: error("Field $typeName.$fieldName not found in test schema")

    // ---------------------------------------------------------------------------------------------
    // 1. isNode
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `isNode is true for the Node interface itself`() {
        assertTrue(SchemaAnalysis.isNode(schema.type("Node")))
    }

    @Test
    fun `isNode is true for a direct Node implementor`() {
        assertTrue(SchemaAnalysis.isNode(schema.type("Entity")))
    }

    @Test
    fun `isNode is true transitively for an interface that extends Node and an object implementing it`() {
        assertTrue(SchemaAnalysis.isNode(schema.type("Identifiable")))
        assertTrue(SchemaAnalysis.isNode(schema.type("Widget")))
    }

    @Test
    fun `isNode is false for a plain object and a non-Node interface`() {
        assertFalse(SchemaAnalysis.isNode(schema.type("Plain")))
        assertFalse(SchemaAnalysis.isNode(schema.type("Named")))
    }

    // ---------------------------------------------------------------------------------------------
    // 2. isIdScalar / isBackingDataType / isBackingDataField
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `isIdScalar distinguishes the ID scalar`() {
        assertTrue(SchemaAnalysis.isIdScalar(schema.type("ID")))
        assertFalse(SchemaAnalysis.isIdScalar(schema.type("BackingData")))
        assertFalse(SchemaAnalysis.isIdScalar(schema.type("Entity")))
    }

    @Test
    fun `isBackingDataType distinguishes the BackingData scalar`() {
        assertTrue(SchemaAnalysis.isBackingDataType(schema.type("BackingData")))
        assertFalse(SchemaAnalysis.isBackingDataType(schema.type("ID")))
    }

    @Test
    fun `isBackingDataField is true only for a BackingData-typed field`() {
        assertTrue(SchemaAnalysis.isBackingDataField(schema.field("Entity", "backing")))
        assertFalse(SchemaAnalysis.isBackingDataField(schema.field("Entity", "name")))
    }

    // ---------------------------------------------------------------------------------------------
    // 3. resolverDirectiveConfigOrNull / isSelectiveResolver / isBatchingResolver
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resolverDirectiveConfigOrNull is null when no resolver directive is applied`() {
        assertNull(SchemaAnalysis.resolverDirectiveConfigOrNull(schema.field("Plainfield", "x")))
    }

    @Test
    fun `resolverDirectiveConfigOrNull defaults both flags to false when args are absent`() {
        val config = SchemaAnalysis.resolverDirectiveConfigOrNull(schema.field("Defaulted", "x"))
        assertEquals(
            SchemaAnalysis.ResolverDirectiveConfig(isSelective = false, isBatching = false),
            config,
        )
    }

    @Test
    fun `isSelectiveResolver decodes the isSelective flag`() {
        assertTrue(SchemaAnalysis.isSelectiveResolver(schema.field("Selective", "x")))
        assertFalse(SchemaAnalysis.isSelectiveResolver(schema.field("Batching", "x")))
        assertFalse(SchemaAnalysis.isSelectiveResolver(schema.field("Plainfield", "x")))
    }

    @Test
    fun `isSelectiveResolver honors the legacy selective alias`() {
        assertTrue(SchemaAnalysis.isSelectiveResolver(legacyResolverSchema.field("Query", "x")))
    }

    @Test
    fun `isBatchingResolver decodes the isBatching flag`() {
        assertTrue(SchemaAnalysis.isBatchingResolver(schema.field("Batching", "x")))
        assertFalse(SchemaAnalysis.isBatchingResolver(schema.field("Selective", "x")))
        assertFalse(SchemaAnalysis.isBatchingResolver(schema.field("Plainfield", "x")))
    }

    // ---------------------------------------------------------------------------------------------
    // 4. idOfTypeName
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `idOfTypeName returns the idOf type argument when present`() {
        assertEquals("Entity", SchemaAnalysis.idOfTypeName(schema.field("Entity", "owner")))
    }

    @Test
    fun `idOfTypeName is null when no idOf directive is applied`() {
        assertNull(SchemaAnalysis.idOfTypeName(schema.field("Entity", "name")))
    }

    // ---------------------------------------------------------------------------------------------
    // 5. globalIdTargetTypeName
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `globalIdTargetTypeName resolves a Node id field to its containing type`() {
        assertEquals("Entity", SchemaAnalysis.globalIdTargetTypeName(schema.field("Entity", "id")))
    }

    @Test
    fun `globalIdTargetTypeName resolves an idOf field to the idOf type`() {
        assertEquals("Entity", SchemaAnalysis.globalIdTargetTypeName(schema.field("Entity", "owner")))
    }

    @Test
    fun `globalIdTargetTypeName is null for an ordinary field`() {
        assertNull(SchemaAnalysis.globalIdTargetTypeName(schema.field("Entity", "name")))
    }

    @Test
    fun `globalIdTargetTypeName rejects idOf on a Node id field`() {
        val conflicting = parse(
            """
            directive @idOf(type: String!) on FIELD_DEFINITION
            interface Node { id: ID! }
            type Conflict implements Node {
                id: ID! @idOf(type: "Conflict")
            }
            type Query { c: Conflict }
            """
        )
        assertThrows<IllegalArgumentException> {
            SchemaAnalysis.globalIdTargetTypeName(conflicting.field("Conflict", "id"))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 6. hasConnectionDirective / hasEdgeDirective
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `hasConnectionDirective is true only for an object carrying connection`() {
        assertTrue(SchemaAnalysis.hasConnectionDirective(schema.type("WidgetConnection")))
        assertFalse(SchemaAnalysis.hasConnectionDirective(schema.type("WidgetEdge")))
        assertFalse(SchemaAnalysis.hasConnectionDirective(schema.type("Plain")))
    }

    @Test
    fun `hasEdgeDirective is true only for an object carrying edge`() {
        assertTrue(SchemaAnalysis.hasEdgeDirective(schema.type("WidgetEdge")))
        assertFalse(SchemaAnalysis.hasEdgeDirective(schema.type("WidgetConnection")))
        assertFalse(SchemaAnalysis.hasEdgeDirective(schema.type("Plain")))
    }

    @Test
    fun `hasOneOfDirective is true only for an input carrying oneOf`() {
        assertTrue(SchemaAnalysis.hasOneOfDirective(schema.type("OneOfFilter")))
        assertFalse(SchemaAnalysis.hasOneOfDirective(schema.type("Filter")))
        assertFalse(SchemaAnalysis.hasOneOfDirective(schema.type("Plain")))
    }

    // ---------------------------------------------------------------------------------------------
    // 7. edgeNodeTypeName
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `edgeNodeTypeName returns the node field's base type`() {
        assertEquals("Widget", SchemaAnalysis.edgeNodeTypeName(schema.obj("WidgetEdge")))
    }

    @Test
    fun `edgeNodeTypeName throws when there is no node field`() {
        assertThrows<IllegalStateException> {
            SchemaAnalysis.edgeNodeTypeName(schema.obj("BadEdge"))
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 8. connectionEdgeTypeName
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `connectionEdgeTypeName returns the edges field base type for a connection`() {
        assertEquals("WidgetEdge", SchemaAnalysis.connectionEdgeTypeName(schema.obj("WidgetConnection")))
    }

    @Test
    fun `connectionEdgeTypeName is null for a non-connection object`() {
        assertNull(SchemaAnalysis.connectionEdgeTypeName(schema.obj("Plain")))
    }

    // ---------------------------------------------------------------------------------------------
    // 9. buildTimeTenantModule
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `buildTimeTenantModule extracts the module from a build-time source name`() {
        assertEquals(
            "presentation/reviews",
            SchemaAnalysis.buildTimeTenantModule("modules/presentation/reviews/schema/reviews.graphqls"),
        )
    }

    @Test
    fun `buildTimeTenantModule strips a trailing src segment`() {
        assertEquals(
            "data/trip",
            SchemaAnalysis.buildTimeTenantModule("modules/data/trip/src/main/schema/trip.graphqls"),
        )
    }

    @Test
    fun `buildTimeTenantModule is null for a non-matching source name`() {
        assertNull(SchemaAnalysis.buildTimeTenantModule("some/other/path/schema.graphqls"))
    }

    // ---------------------------------------------------------------------------------------------
    // 10. resolverClassName / argumentsTypeName
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `resolverClassName upper-cases the first character of a lowercase field name`() {
        assertEquals("Profile", SchemaAnalysis.resolverClassName("profile"))
        assertEquals("Orders", SchemaAnalysis.resolverClassName("orders"))
    }

    @Test
    fun `resolverClassName leaves an already-capitalized field name unchanged`() {
        assertEquals("Profile", SchemaAnalysis.resolverClassName("Profile"))
    }

    @Test
    fun `resolverClassName capitalizes a single-character field name`() {
        assertEquals("X", SchemaAnalysis.resolverClassName("x"))
    }

    @Test
    fun `argumentsTypeName joins the containing type and capitalized field name`() {
        assertEquals("User_Profile_Arguments", SchemaAnalysis.argumentsTypeName("User", "profile"))
    }

    @Test
    fun `argumentsTypeName derives the containing type and field name from a field`() {
        assertEquals("Selective_X_Arguments", SchemaAnalysis.argumentsTypeName(schema.field("Selective", "x")))
    }
}
