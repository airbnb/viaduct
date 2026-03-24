package viaduct.engine.runtime.tenantloading

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.mocks.MockTenantModuleBootstrapper
import viaduct.engine.api.mocks.fetchAs
import viaduct.engine.api.mocks.runFeatureTest

@ExperimentalCoroutinesApi
class CycleDetectorFeatureTest {
    @Test
    fun `cycles in resolver are detected`() {
        // This should throw an exception during construction due to the cycle:
        // foo requires bar, bar requires foo
        assertThrows<RequiredSelectionsCycleException> {
            MockTenantModuleBootstrapper("extend type Query { foo: Int, bar: Int }") {
                field("Query" to "foo") {
                    resolver {
                        objectSelections("bar") // foo requires bar
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("bar") }
                    }
                }
                field("Query" to "bar") {
                    resolver {
                        objectSelections("foo") // bar requires foo - creates cycle
                        fn { _, obj, _, _, _ -> obj.fetchAs<Int>("foo") }
                    }
                }
            }.runFeatureTest { } // Empty block - just triggers construction
        }
    }
}
