package viaduct.tenant.codegen.bytecode.config

import kotlinx.metadata.KmType
import kotlinx.metadata.isNullable
import viaduct.codegen.SchemaAnalysis
import viaduct.codegen.utils.Km
import viaduct.graphql.schema.ViaductSchema

/** is this TypeDef the BackingData scalar? Delegates to the shared [SchemaAnalysis.isBackingDataType]. */
val ViaductSchema.TypeDef.isBackingDataType: Boolean get() =
    SchemaAnalysis.isBackingDataType(this)

/** is this Field a customized BackingData scalar. Delegates to the shared [SchemaAnalysis.isBackingDataField]. */
val ViaductSchema.Field.isBackingDataType: Boolean get() =
    SchemaAnalysis.isBackingDataField(this)

/**
 * Only check for BackingData scalar type in codegen. No need to check for @backingData directive.
 * This is because if the field has BackingData base type but no @backingData directive, it will skip
 * codegen and be caught in the schema validation. If a field has @backingData directive but not BackingData
 * base type, codegen will proceed as usual and the violation will be caught in the schema validation.
 */
val ViaductSchema.Record.codegenIncludedFields: Iterable<ViaductSchema.Field> get() =
    fields.filter { !it.isBackingDataType }

/** derive a BackingData instance from the AppliedDirective's, if one exists */
val Iterable<ViaductSchema.AppliedDirective<*>>.backingData: BackingData?
    get() = firstNotNullOfOrNull { if (it.name == BackingData.name) BackingData.parse(it) else null }

data class BackingData(val fqClass: String) {
    companion object {
        val name: String = "backingData"

        fun parse(dir: ViaductSchema.AppliedDirective<*>): BackingData {
            require(dir.name == name)
            return BackingData((dir.arguments["class"] as ViaductSchema.StringLiteral).value)
        }
    }
}

/** create a KmType describing the customized BackingData */
fun ViaductSchema.TypeExpr<*>.backingDataType(): KmType {
    return Km.ANY.asType().apply { isNullable = baseTypeNullable }
}
