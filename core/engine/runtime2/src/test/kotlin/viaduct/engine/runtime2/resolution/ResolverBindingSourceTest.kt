package viaduct.engine.runtime2.resolution

import kotlin.test.Test
import kotlin.test.assertEquals
import viaduct.engine.runtime2.coverage.ResolverBindingSource
import viaduct.engine.runtime2.coverage.maxProviderPathDepth
import viaduct.engine.runtime2.coverage.resolverBindingSources
import viaduct.engine.runtime2.model.ObjectEngineResult
import viaduct.engine.runtime2.model.registry.ProviderFragment
import viaduct.engine.runtime2.model.registry.VariableDefinition
import viaduct.engine.runtime2.model.requireObjectField
import viaduct.engine.runtime2.model.testing.TestWorld

class ResolverBindingSourceTest {
    @Test
    fun classifiesEachResolverVariableSource() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schemas.loweredSchema
        val resolverField = schema.requireObjectField("Item", "resolved")
        val definitions =
            listOf(
                VariableDefinition.FromProvider,
                VariableDefinition.FromArgument.of(resolverField.args.single()),
                VariableDefinition.FromField.of(
                    ProviderFragment.OBJECT,
                    listOf(ObjectEngineResult.ObjectKey.of(schema.requireObjectField("Item", "objectValue"), emptyMap())),
                    listOf("objectValue"),
                ),
                VariableDefinition.FromField.of(
                    ProviderFragment.QUERY,
                    listOf(ObjectEngineResult.ObjectKey.of(schema.requireObjectField("Query", "queryValue"), emptyMap())),
                    listOf("queryValue"),
                ),
            )

        assertEquals(
            setOf(
                ResolverBindingSource.PROVIDER,
                ResolverBindingSource.ARGUMENT,
                ResolverBindingSource.OBJECT_FIELD,
                ResolverBindingSource.QUERY_FIELD,
            ),
            definitions.resolverBindingSources(),
        )
    }

    @Test
    fun providerPathDepthIncludesObjectAndQueryFieldPaths() {
        val schema = TestWorld.fromSDL(SCHEMA_SDL).schemas.loweredSchema
        val objectPath =
            VariableDefinition.FromField.of(
                ProviderFragment.OBJECT,
                listOf(
                    ObjectEngineResult.ObjectKey.of(schema.requireObjectField("Item", "branch"), emptyMap()),
                    ObjectEngineResult.ObjectKey.of(schema.requireObjectField("Branch", "value"), emptyMap()),
                ),
                listOf("branch", "value"),
            )
        val queryPath =
            VariableDefinition.FromField.of(
                ProviderFragment.QUERY,
                listOf(
                    ObjectEngineResult.ObjectKey.of(schema.requireObjectField("Query", "branch"), emptyMap()),
                    ObjectEngineResult.ObjectKey.of(schema.requireObjectField("Branch", "value"), emptyMap()),
                ),
                listOf("branch", "value"),
            )

        assertEquals(2, listOf(objectPath, queryPath).maxProviderPathDepth())
    }
}

private val SCHEMA_SDL =
    """
    type Query {
      queryValue: Int
      branch: Branch
    }
    type Item {
      resolved(seed: Int): Int
      objectValue: Int
      branch: Branch
    }
    type Branch {
      value: Int
    }
    """.trimIndent()
