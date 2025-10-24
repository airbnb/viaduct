package viaduct.tenant.runtime.featuretests

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import viaduct.tenant.runtime.featuretests.fixtures.FeatureTestBuilder

@ExperimentalCoroutinesApi
class TrivialScalarTypeResolverTest {
    @Test
    fun `trivial resolver returns a static value`() {
        FeatureTestBuilder("extend type Query { field: Int }")
            .resolver("Query" to "field") { 42 }
            .build()
            .assertJson("{data: {field:42}}", "{field}")
    }

    @Test
    fun `resolver returns string value`() {
        FeatureTestBuilder("extend type Query { stringField: String }")
            .resolver("Query" to "stringField") { "test string value" }
            .build()
            .assertJson("{data: {stringField:\"test string value\"}}", "{stringField}")
    }

    @Test
    fun `resolver returns computed int value`() {
        FeatureTestBuilder("extend type Query { computedField: Int }")
            .resolver("Query" to "computedField") { 10 + 32 }
            .build()
            .assertJson("{data: {computedField:42}}", "{computedField}")
    }

    @Test
    fun `resolver returns enum value`() {
        FeatureTestBuilder("enum TestEnum { VALUE_A VALUE_B } extend type Query { enumField: TestEnum }")
            .resolver("Query" to "enumField") { "VALUE_A" }
            .build()
            .assertJson("{data: {enumField:\"VALUE_A\"}}", "{enumField}")
    }
}
