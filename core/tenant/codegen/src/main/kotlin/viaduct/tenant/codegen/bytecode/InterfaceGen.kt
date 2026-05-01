package viaduct.tenant.codegen.bytecode

import kotlinx.metadata.ClassKind
import kotlinx.metadata.KmFunction
import kotlinx.metadata.KmValueParameter
import kotlinx.metadata.Modality
import kotlinx.metadata.Visibility
import kotlinx.metadata.isNullable
import kotlinx.metadata.isSuspend
import kotlinx.metadata.modality
import kotlinx.metadata.visibility
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.getterName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.codegenIncludedFields
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.kmType

internal fun GRTClassFilesBuilder.interfaceGen(def: ViaductSchema.Interface) {
    kmClassFilesBuilder.customClassBuilder(
        ClassKind.INTERFACE,
        def.name.kmFQN(pkg),
    ).also {
        it.addSupertype(cfg.INTERFACE_GRT.asKmName.asType())

        // Add NodeCompositeOutput for Node interfaces
        if (def.isNode) {
            it.addSupertype(cfg.NODE_COMPOSITE_OUTPUT_GRT.asKmName.asType())
        }

        if (def.supers.isNotEmpty()) {
            for (s in def.supers) {
                it.addSupertype(s.name.kmFQN(pkg).asType())
                this.addSchemaGRTReference(s)
            }
        }

        for (f in def.codegenIncludedFields) {
            if (f.isOverride) {
                continue
            }
            it.addSuspendingGetterFun(f, pkg, baseTypeMapper)
            it.addSuspendingGetterFun(
                f,
                pkg,
                baseTypeMapper,
                KmValueParameter("alias").also { it.type = Km.STRING.asNullableType() }
            )
            it.addSuspendingGetterFun(f, pkg, baseTypeMapper, orNull = true)
            it.addSuspendingGetterFun(
                f,
                pkg,
                baseTypeMapper,
                KmValueParameter("alias").also { it.type = Km.STRING.asNullableType() },
                orNull = true
            )
        }

        this.reflectedTypeGen(def, it)
        this.fieldsObjectGen(def, it)
    }
}

private fun CustomClassBuilder.addSuspendingGetterFun(
    field: ViaductSchema.Field,
    pkg: KmName,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    valueParam: KmValueParameter? = null,
    orNull: Boolean = false
): CustomClassBuilder {
    val suffix = if (orNull) "OrNull" else ""
    val methodName = getterName(field.name) + suffix
    val getter = KmFunction(methodName).also {
        it.visibility = Visibility.PUBLIC
        it.modality = Modality.ABSTRACT
        it.isSuspend = true
        it.returnType = field.kmType(pkg, baseTypeMapper).also { t ->
            if (orNull) t.isNullable = true
        }
    }
    valueParam?.let { getter.valueParameters.add(it) }

    this.addSuspendFunction(
        getter,
        returnTypeAsInputForSuspend = field.kmType(pkg, baseTypeMapper, isInput = true).also { t ->
            if (orNull) t.isNullable = true
        },
    )
    return this
}
