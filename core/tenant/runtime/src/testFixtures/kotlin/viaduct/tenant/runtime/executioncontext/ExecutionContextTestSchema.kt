package viaduct.tenant.runtime.executioncontext

import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.api.testing.TestSchema
import viaduct.api.testing.featureapp.KotlinFeatureAppTestContractBase
import viaduct.engine.api.ViaductSchema
import viaduct.graphql.utils.DefaultSchemaFactory

@TestSchema(
    """
extend type Query {
  intField: Int
}

extend type Mutation {
  mutfield(x: Int!): String!
}

extend interface Node {
    nodeSelf: Node!
}

type Foo implements Node {
  id: ID!
  nodeSelf: Node!
  fooId: ID!
  fooSelf: Foo!
}

type Bar {
  bar: Bar!
}

union FooOrBar = Foo | Bar

"""
)
object ExecutionContextTestSchema : KotlinFeatureAppTestContractBase() {
    public override fun sdl(): String = super.sdl()

    val schema: ViaductSchema by lazy {
        ViaductSchema(
            UnExecutableSchemaGenerator.makeUnExecutableSchema(
                SchemaParser().parse(sdl()).apply {
                    DefaultSchemaFactory.addDefaults(this)
                }
            )
        )
    }
}
