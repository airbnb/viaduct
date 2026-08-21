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
import viaduct.codegen.GeneratedAccessorNames
import viaduct.codegen.km.CustomClassBuilder
import viaduct.codegen.km.getterName
import viaduct.codegen.utils.Km
import viaduct.codegen.utils.KmName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.AccessorForm
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

        // Override fields are checked but not emitted: they inherit an accessor that a sibling field
        // can still collide with.
        val fieldsToEmit = def.codegenIncludedFields.filterNot { f -> f.isOverride }
        GeneratedAccessorNames.validateNoCollisions(
            def.name,
            def.codegenIncludedFields.associate { f -> f.name to getterName(f.name) },
            cfg.FIELD_ACCESSOR_SUFFIXES
        )

        for (f in fieldsToEmit) {
            for (form in AccessorForm.entries) {
                it.addGetterFun(f, pkg, baseTypeMapper, form)
                it.addGetterFun(
                    f,
                    pkg,
                    baseTypeMapper,
                    form,
                    KmValueParameter("alias").also { p -> p.type = Km.STRING.asNullableType() }
                )
            }
        }

        this.reflectedTypeGen(def, it)
        this.fieldsObjectGen(def, it)
    }
}

private fun CustomClassBuilder.addGetterFun(
    field: ViaductSchema.Field,
    pkg: KmName,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper,
    form: AccessorForm,
    valueParam: KmValueParameter? = null
): CustomClassBuilder {
    val methodName = form.methodName(getterName(field.name))
    val getter = KmFunction(methodName).also {
        it.visibility = Visibility.PUBLIC
        it.modality = Modality.ABSTRACT
        it.isSuspend = false
        it.returnType = field.kmType(pkg, baseTypeMapper).also { t ->
            if (form.nullable) t.isNullable = true
        }
    }
    valueParam?.let { getter.valueParameters.add(it) }

    this.addFunction(getter)
    return this
}
