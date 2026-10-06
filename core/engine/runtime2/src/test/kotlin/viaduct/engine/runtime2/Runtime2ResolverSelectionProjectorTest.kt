package viaduct.engine.runtime2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import viaduct.engine.api.Coordinate
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ResolverType
import viaduct.engine.api.mocks.MockFieldUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockNodeUnbatchedResolverExecutor
import viaduct.engine.api.mocks.MockSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.DispatcherRegistry
import viaduct.engine.runtime.FieldResolverDispatcher
import viaduct.engine.runtime.FieldResolverDispatcherImpl
import viaduct.engine.runtime.NodeResolverDispatcher
import viaduct.engine.runtime.NodeResolverDispatcherImpl
import viaduct.engine.runtime.select.EngineSelectionSetImpl

class Runtime2ResolverSelectionProjectorTest {
    private val schema =
        MockSchema.mk(
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
            """.trimIndent(),
        )

    @Test
    fun `projection retains embedded fields and stops at dispatcher boundaries`() {
        val projected =
            project(
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
                FakeDispatcherRegistry(
                    fieldBoundaries = setOf("Metadata" to "score", "Listing" to "price"),
                    nodeBoundaries = setOf("User"),
                ),
            )

        assertEquals(
            listOf("title", "address", "metadata", "contact"),
            projected.selections().map { it.fieldName },
        )
        assertTrue(projected.selectionSetForField("Listing", "address").containsField("Address", "city"))
        assertEquals(
            listOf("__typename"),
            projected.selectionSetForField("Listing", "metadata").selections().map { it.fieldName },
        )
        val contact = projected.selectionSetForField("Listing", "contact")
        assertEquals(
            listOf("label", "hours"),
            contact.selectionSetForType("LocalContact").selections().map { it.fieldName },
        )
        assertTrue(contact.selectionSetForType("User").isEmpty())
    }

    @Test
    fun `abstract projection applies field boundaries to each concrete parent`() {
        val projected =
            project(
                selections = "label",
                dispatcherRegistry =
                    FakeDispatcherRegistry(
                        fieldBoundaries = setOf("LocalContact" to "label"),
                    ),
                typeName = "Contact",
            )

        assertTrue(projected.selectionSetForType("User").containsField("User", "label"))
        assertFalse(projected.selectionSetForType("LocalContact").containsField("LocalContact", "label"))
        assertTrue(projected.selectionSetForType("LocalContact").containsField("LocalContact", "__typename"))
    }

    @Test
    fun `node resolver owns its root except for id`() {
        val registry = FakeDispatcherRegistry(nodeBoundaries = setOf("User"))
        val fieldProjection = project("id name", registry, typeName = "User")
        val nodeProjection =
            project(
                "id name",
                registry,
                typeName = "User",
                resolverType = ResolverType.NODE,
            )

        assertTrue(fieldProjection.isEmpty())
        assertEquals(listOf("name"), nodeProjection.selections().map { it.fieldName })
    }

    @Test
    fun `projection preserves aliases arguments directives and source order`() {
        val projected =
            project(
                """
                selected: address @include(if: true) {
                  first: city(language: "en")
                  last: country
                }
                """.trimIndent(),
                FakeDispatcherRegistry(),
            )
        val rootField = (projected as EngineSelectionSetImpl).selections.single().field
        val children = projected.selectionSetForSelection("Listing", "selected")

        assertEquals("selected", rootField.alias)
        assertEquals(listOf("include"), rootField.directives.map { it.name })
        assertEquals(listOf("first", "last"), children.selections().map { it.selectionName })
        assertEquals(mapOf("language" to "en"), children.argumentsOfSelection("Address", "first"))
    }

    private fun project(
        selections: String,
        dispatcherRegistry: DispatcherRegistry,
        typeName: String = "Listing",
        resolverType: ResolverType = ResolverType.FIELD,
    ): EngineSelectionSet =
        Runtime2ResolverSelectionProjector(schema, dispatcherRegistry)
            .project(
                EngineSelectionSetImpl.create(
                    SelectionsParser.parse(typeName, selections),
                    emptyMap(),
                    schema,
                ),
                resolverType,
            )

    private class FakeDispatcherRegistry(
        private val fieldBoundaries: Set<Coordinate> = emptySet(),
        private val nodeBoundaries: Set<String> = emptySet(),
    ) : DispatcherRegistry by DispatcherRegistry.Empty {
        private val fieldDispatcher =
            FieldResolverDispatcherImpl(
                MockFieldUnbatchedResolverExecutor(resolverId = "boundary"),
            )
        private val nodeDispatcher =
            NodeResolverDispatcherImpl(
                MockNodeUnbatchedResolverExecutor(typeName = "Boundary"),
            )

        override fun getFieldResolverDispatcher(
            typeName: String,
            fieldName: String,
        ): FieldResolverDispatcher? = fieldDispatcher.takeIf { typeName to fieldName in fieldBoundaries }

        override fun getNodeResolverDispatcher(typeName: String): NodeResolverDispatcher? {
            return nodeDispatcher.takeIf { typeName in nodeBoundaries }
        }
    }
}
