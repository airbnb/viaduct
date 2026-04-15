package viaduct.tenant.runtime.featuretests.fixtures

import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase

@TestSchema(
    """
extend type Query {
  string1: String @resolver
  string2: String @resolver
  hasArgs1(x: Int!): Int! @resolver
  hasArgs2(x: String): String! @resolver
  hasArgs3(x: Int!): Int! @resolver
  foo: Foo @resolver
  bar: Bar @resolver
  baz: Baz @resolver
  bazList: [Baz] @resolver
  iface: Interface @resolver
  union_: Union @resolver
  boo: Boo @resolver
  enumField: EnumType @resolver
  idField: ID @idOf(type: "Baz")
  items(first: Int, after: String): ItemsConnection @resolver
  itemsFromSlice(first: Int, after: String): ItemsConnection @resolver
  itemsFromEdges(first: Int, after: String, last: Int, before: String): ItemsConnection @resolver
  itemsBackward(last: Int, before: String): ItemsConnection @resolver
}

extend type Mutation {
  string1: String @resolver
  string2: String @resolver
}

interface Interface {
  value: String
}

union Union = Foo | Bar | Baz

type Foo implements Interface {
  value: String @resolver
  bar: Bar @resolver
  valueWithoutResolver: String
}
type Bar implements Interface {
  value: String @resolver
  foo: Foo @resolver
}

type Baz implements Node {
  id: ID!
  x: Int
  x2: String
  y: String @resolver
  anotherBaz: Baz @resolver
  z: Int @resolver
}

type Boo {
  value: Int
}

enum EnumType { A, B }

type ItemsConnection @connection {
  edges: [ItemEdge]
  pageInfo: PageInfo!
}

type ItemEdge @edge{
  cursor: String!
  node: Item
}

type Item {
  name: String
}
"""
)
object FeatureTestSchema : KotlinFeatureAppTestContractBase() {
    public override fun sdl(): String = super.sdl()
}
