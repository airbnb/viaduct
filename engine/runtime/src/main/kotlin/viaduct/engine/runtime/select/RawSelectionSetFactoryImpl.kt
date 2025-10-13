package viaduct.engine.runtime.select

import graphql.schema.DataFetchingEnvironment
import graphql.schema.GraphQLCompositeType
import graphql.schema.GraphQLTypeUtil
import viaduct.engine.api.ParsedSelections
import viaduct.engine.api.RawSelectionSet
import viaduct.engine.api.ViaductSchema
import viaduct.engine.api.engineExecutionContext
import viaduct.engine.api.select.SelectionsParser

class RawSelectionSetFactoryImpl(
    private val fullSchema: ViaductSchema,
) : RawSelectionSet.Factory {
    /** create a [RawSelectionSetImpl] from strings */
    override fun rawSelectionSet(
        typeName: String,
        selections: String,
        variables: Map<String, Any?>
    ): RawSelectionSetImpl =
        RawSelectionSetImpl.create(
            SelectionsParser.parse(typeName, selections),
            variables,
            fullSchema
        )

    override fun rawSelectionSet(
        selections: ParsedSelections,
        variables: Map<String, Any?>
    ): RawSelectionSet =
        RawSelectionSetImpl.create(
            selections,
            variables,
            fullSchema
        )

    /**
     * create a [RawSelectionSetImpl] from a graphql-java DataFetchingEnvironment
     * or null if the executing type does not support selections.
     */
    override fun rawSelectionSet(env: DataFetchingEnvironment): RawSelectionSetImpl? =
        env.executionStepInfo.type.let { type ->
            val unwrappedType = GraphQLTypeUtil.unwrapAll(type)
            if (unwrappedType !is GraphQLCompositeType) return null

            RawSelectionSetImpl.create(
                SelectionsParser.fromDataFetchingEnvironment((unwrappedType as GraphQLCompositeType).name, env),
                env.engineExecutionContext.fieldScope.variables,
                fullSchema
            )
        }
}
