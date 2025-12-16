package viaduct.service.api.spi

import graphql.schema.DataFetchingEnvironment
import io.mockk.mockk
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class ResolverErrorBuilderTest {
    @Test
    fun testNoOp() {
        @Suppress("DEPRECATION")
        assertNull(
            ResolverErrorBuilder.Companion.NoOpResolverErrorBuilder.exceptionToGraphQLError(
                Throwable("Test Exception"),
                mockk<DataFetchingEnvironment>(),
                ErrorMetadata.EMPTY
            )
        )
    }
}
