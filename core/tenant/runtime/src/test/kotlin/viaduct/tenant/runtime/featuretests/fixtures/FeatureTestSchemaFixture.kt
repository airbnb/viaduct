package viaduct.tenant.runtime.featuretests.fixtures

import graphql.schema.GraphQLSchema
import graphql.schema.idl.SchemaParser
import graphql.schema.idl.UnExecutableSchemaGenerator
import viaduct.graphql.utils.DefaultSchemaFactory

object FeatureTestSchemaFixture {
    val schema: GraphQLSchema by lazy {
        UnExecutableSchemaGenerator.makeUnExecutableSchema(
            SchemaParser().parse(sdl).apply {
                DefaultSchemaFactory.addDefaults(this)
            }
        )
    }

    val sdl: String by lazy {
        val featureAppTest = FeatureTestSchemaFeatureAppTest()
        featureAppTest.sdl
            .substringAfter("#START_SCHEMA")
            .substringBefore("#END_SCHEMA")
            .trim()
    }
}
