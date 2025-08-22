package viaduct.tenant.codegen.bytecode

import kotlinx.metadata.ClassKind
import viaduct.graphql.schema.ViaductExtendedSchema
import viaduct.tenant.codegen.bytecode.config.cfg

internal fun GRTClassFilesBuilder.unionGenV2(def: ViaductExtendedSchema.Union) {
    kmClassFilesBuilder.customClassBuilder(
        ClassKind.INTERFACE,
        def.name.kmFQN(pkg),
    ).also {
        it.addSupertype(cfg.UNION_GRT.asKmName.asType())
        reflectedTypeGen(def, it)
    }
}
