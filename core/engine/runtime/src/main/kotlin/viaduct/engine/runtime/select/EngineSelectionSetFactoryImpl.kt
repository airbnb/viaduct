package viaduct.engine.runtime.select

import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.EngineSelectionSet
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.select.SelectionsParser
import viaduct.engine.runtime.dfe.engineExecutionContext

class EngineSelectionSetFactoryImpl(
    private val fullSchema: ViaductSchema,
) : EngineSelectionSet.Factory {
    /** create an [EngineSelectionSetImpl] from strings */
    override fun engineSelectionSet(
        typeName: String,
        selections: String,
        variables: Map<String, Any?>
    ): EngineSelectionSetImpl =
        EngineSelectionSetImpl.create(
            SelectionsParser.parse(typeName, selections),
            variables,
            fullSchema
        )

    override fun engineSelectionSet(
        selections: ParsedSelections,
        variables: Map<String, Any?>
    ): EngineSelectionSet =
        EngineSelectionSetImpl.create(
            selections,
            variables,
            fullSchema
        )

    /**
     * create an [EngineSelectionSetImpl] from a graphql-java DataFetchingEnvironment
     * or null if the executing type does not support selections.
     */
    override fun engineSelectionSet(env: DataFetchingEnvironment): EngineSelectionSetImpl? =
        env.executionStepInfo.type.let { type ->
            val unwrappedType = GraphQLTypeUtil.unwrapAll(type)
            if (unwrappedType !is GraphQLCompositeType) return null

            EngineSelectionSetImpl.create(
                SelectionsParser.fromDataFetchingEnvironment((unwrappedType as GraphQLCompositeType).name, env),
                env.engineExecutionContext.fieldScope.variables,
                fullSchema
            )
        }
}
