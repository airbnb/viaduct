package viaduct.tenant.tutorial12

import viaduct.api.testing.TestSchema
import viaduct.tenant.runtime.fixtures.FeatureAppTestBase

/**
 * Contract test for [ConnectionsFeatureAppTest].
 *
 * This class exists solely to hold the `@TestSchema` annotation for codegen.
 * The actual tests and resolvers remain in the subclass for readability.
 */
@TestSchema(
    """
    enum Genre { FICTION NON_FICTION SCIENCE }

    type Book implements Node @resolver {
      id: ID!
      title: String!
      genre: Genre!
      year: Int!
    }

    type BookEdge @edge {
      node: Book
      cursor: String!
      reason: String
    }

    type BookConnection @connection {
      edges: [BookEdge!]!
      pageInfo: PageInfo!
    }

    extend type Query {
      books(first: Int, after: String, last: Int, before: String): BookConnection! @resolver
      booksByGenre(genre: Genre!, first: Int, after: String): BookConnection! @resolver
      highlightedBooks(first: Int, after: String): BookConnection! @resolver
    }
"""
)
abstract class ConnectionsContractTest : FeatureAppTestBase()
