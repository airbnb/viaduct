package viaduct.engine.runtime

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolverType
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.select.EngineSelectionSetImpl

class ResolverSelectionProjectorTest {
    private val schema = MockSchema.mk(
        """
            interface Contact {
              label: String
            }

            type User implements Contact & Node {
              id: ID!
              label: String
              name: String
            }

            type LocalContact implements Contact {
              label: String
              hours: String
            }

            type OtherContact implements Contact {
              label: String
              note: String
            }

            type Address {
              city(language: String): String
              country: String
            }

            type Metadata {
              score: Int
            }

            type Price {
              amount: Int
            }

            type Listing {
              title: String
              address: Address
              metadata: Metadata
              host: User
              price: Price
              contact: Contact
            }
        """.trimIndent()
    )

    @Test
    fun `projection retains embedded fields and stops before field and node boundaries`() {
        val dispatcherRegistry = FakeDispatcherRegistry(
            fieldBoundaries = setOf("Metadata" to "score", "Listing" to "price"),
            nodeBoundaries = setOf("User"),
        )
        val projected = project(
            """
                title
                address { city }
                metadata { score }
                host { name }
                price { amount }
                contact {
                  label
                  ... on User { name }
                  ... on LocalContact { hours }
                }
            """.trimIndent(),
            dispatcherRegistry,
        )

        assertEquals(
            listOf("title", "address", "metadata", "contact"),
            projected.selections().map { it.fieldName },
        )
        assertTrue(projected.selectionSetForField("Listing", "address").containsField("Address", "city"))

        val metadata = projected.selectionSetForField("Listing", "metadata")
        assertEquals(listOf("__typename"), metadata.selections().map { it.fieldName })

        val contact = projected.selectionSetForField("Listing", "contact")
        assertEquals(
            listOf("label", "hours"),
            contact.selectionSetForType("LocalContact").selections().map { it.fieldName },
        )
        assertTrue(contact.selectionSetForType("User").isEmpty())
    }

    @Test
    fun `abstract field is omitted when every concrete type is a node boundary`() {
        val projected = project(
            "contact { label }",
            FakeDispatcherRegistry(nodeBoundaries = setOf("User", "LocalContact", "OtherContact")),
        )

        assertFalse(projected.containsField("Listing", "contact"))
    }

    @Test
    fun `abstract projection does not add unrequested concrete branches`() {
        val projected = project(
            "contact { ... on LocalContact { hours } }",
            FakeDispatcherRegistry(nodeBoundaries = setOf("User")),
        )
        val contact = projected.selectionSetForField("Listing", "contact")

        assertEquals(
            listOf("hours"),
            contact.selectionSetForType("LocalContact").selections().map { it.fieldName },
        )
        assertTrue(contact.selectionSetForType("OtherContact").isEmpty())
    }

    @Test
    fun `abstract root omits node owned concrete types`() {
        val projected = project(
            """
                label
                ... on User { name }
                ... on LocalContact { hours }
            """.trimIndent(),
            FakeDispatcherRegistry(nodeBoundaries = setOf("User")),
            typeName = "Contact",
        )

        assertTrue(projected.selectionSetForType("User").isEmpty())
        assertEquals(
            listOf("label", "hours"),
            projected.selectionSetForType("LocalContact").selections().map { it.fieldName },
        )
    }

    @Test
    fun `abstract projection checks field boundary on concrete parent type`() {
        val projected = project(
            "label",
            FakeDispatcherRegistry(fieldBoundaries = setOf("LocalContact" to "label")),
            typeName = "Contact",
        )

        assertTrue(projected.selectionSetForType("User").containsField("User", "label"))
        assertFalse(projected.selectionSetForType("LocalContact").containsField("LocalContact", "label"))
        assertTrue(projected.selectionSetForType("LocalContact").containsField("LocalContact", "__typename"))
    }

    @Test
    fun `abstract field retains typename when runtime directives remove every child`() {
        val projected = project(
            "contact { label @skip(if: \$skipLabel) }",
            FakeDispatcherRegistry(nodeBoundaries = setOf("User")),
            variables = mapOf("skipLabel" to true),
        )
        val contact = projected.selectionSetForField("Listing", "contact")

        assertTrue(projected.containsField("Listing", "contact"))
        assertTrue(contact.selectionSetForType("User").isEmpty())
        assertTrue(contact.selectionSetForType("LocalContact").containsField("LocalContact", "__typename"))
        assertTrue(contact.selectionSetForType("OtherContact").containsField("OtherContact", "__typename"))
    }

    @Test
    fun `conditional exclusions preserve accumulated abstract constraints`() {
        val projected = project(
            """
                contact {
                  ... on LocalContact {
                    ... on Contact {
                      label @skip(if: true)
                    }
                  }
                }
            """.trimIndent(),
            DispatcherRegistry.Empty,
        )
        val contact = projected.selectionSetForField("Listing", "contact")

        assertTrue(contact.selectionSetForType("LocalContact").containsField("LocalContact", "__typename"))
        assertTrue(contact.selectionSetForType("OtherContact").isEmpty())
    }

    @Test
    fun `node root remains owned while field root stops at the same node boundary`() {
        val dispatcherRegistry = FakeDispatcherRegistry(nodeBoundaries = setOf("User"))

        val fieldProjection = project(
            "id name",
            dispatcherRegistry,
            typeName = "User",
        )
        val nodeProjection = project(
            "id name",
            dispatcherRegistry,
            typeName = "User",
            resolverType = ResolverType.NODE,
        )

        assertTrue(fieldProjection.isEmpty())
        assertEquals(listOf("name"), nodeProjection.selections().map { it.fieldName })
    }

    @Test
    fun `projection preserves executable field metadata and source order`() {
        val retained = project(
            """
                selected: address @include(if: true) {
                  first: city(language: "en")
                  last: country
                }
            """.trimIndent(),
            FakeDispatcherRegistry(),
        )
        val rootField = (retained as EngineSelectionSetImpl).selections.single().field
        val children = retained.selectionSetForSelection("Listing", "selected")

        assertEquals("selected", rootField.alias)
        assertEquals(listOf("include"), rootField.directives.map { it.name })
        assertEquals(listOf("first", "last"), children.selections().map { it.selectionName })
        assertEquals(
            mapOf("language" to "en"),
            children.argumentsOfSelection("Address", "first"),
        )
    }

    @Test
    fun `field boundary avoids node classification and abstract classification is cached`() {
        val dispatcherRegistry = FakeDispatcherRegistry(
            fieldBoundaries = setOf("Listing" to "host"),
            nodeBoundaries = setOf("User"),
        )
        val projector = ResolverSelectionProjector(schema, dispatcherRegistry)

        projector.project(selectionSet("host { name }"), ResolverType.FIELD)
        assertFalse("User" in dispatcherRegistry.checkedTypes)

        projector.project(selectionSet("contact { label }"), ResolverType.FIELD)
        projector.project(selectionSet("contact { label }"), ResolverType.FIELD)

        assertEquals(1, dispatcherRegistry.checkedTypes.count { it == "User" })
        assertEquals(1, dispatcherRegistry.checkedTypes.count { it == "LocalContact" })
    }

    private fun project(
        selections: String,
        dispatcherRegistry: DispatcherRegistry,
        typeName: String = "Listing",
        resolverType: ResolverType = ResolverType.FIELD,
        variables: Map<String, Any?> = emptyMap(),
    ): EngineSelectionSet =
        ResolverSelectionProjector(schema, dispatcherRegistry)
            .project(selectionSet(selections, typeName, variables), resolverType)

    private fun selectionSet(
        selections: String,
        typeName: String = "Listing",
        variables: Map<String, Any?> = emptyMap(),
    ): EngineSelectionSetImpl =
        EngineSelectionSetImpl.create(
            SelectionsParser.parse(typeName, selections),
            variables,
            schema,
        )

    private class FakeDispatcherRegistry(
        private val fieldBoundaries: Set<Coordinate> = emptySet(),
        private val nodeBoundaries: Set<String> = emptySet(),
    ) : DispatcherRegistry by DispatcherRegistry.Empty {
        val checkedTypes = mutableListOf<String>()
        private val fieldDispatcher = mockk<FieldResolverDispatcher>()
        private val nodeDispatcher = mockk<NodeResolverDispatcher>()

        override fun getFieldResolverDispatcher(
            typeName: String,
            fieldName: String,
        ): FieldResolverDispatcher? = fieldDispatcher.takeIf { typeName to fieldName in fieldBoundaries }

        override fun getNodeResolverDispatcher(typeName: String): NodeResolverDispatcher? {
            checkedTypes += typeName
            return nodeDispatcher.takeIf { typeName in nodeBoundaries }
        }
    }
}
