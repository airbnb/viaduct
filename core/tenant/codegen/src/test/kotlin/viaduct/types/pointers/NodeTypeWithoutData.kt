package viaduct.types.pointers

import viaduct.components.base.QueryDescriptor
import viaduct.components.base.QuerySelectionContext

typealias GlobalID = String

interface BaseNodeTypeWithoutData<OutputType> {
    fun getForSubselection(
        subselectionFieldNames: List<String>,
        querySelectionContext: QuerySelectionContext?
    ) = this
}

interface NodeTypeWithoutData<OutputType> : BaseNodeTypeWithoutData<OutputType> {
    suspend fun getKey(): QueryDescriptor<GlobalID> {
        throw NotImplementedError(
            "Attempting to call getKey in the stub NodeTypeWithoutData"
        )
    }

    suspend fun getFieldByName(fieldName: String): Any? {
        throw NotImplementedError(
            "Attempting to call getFieldByName in the stub NodeTypeWithoutData"
        )
    }
}
