package viaduct.tenant.codegen.bytecode

import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg

internal class GRTClassFilesBuilder(
    args: CodeGenArgs,
) : GRTClassFilesBuilderBase(args) {
    protected override fun setup() {
        cfg.importedClasses.forEach { kmClassFilesBuilder.addImportedClass(it) }
    }

    protected override fun isGenerated(def: ViaductSchema.TypeDef): Boolean = !def.name.startsWith("__")

    override fun addEnum(def: ViaductSchema.Enum) {
        if (!isGenerated(def)) return
        enumGen(def)
    }

    override fun addUnion(def: ViaductSchema.Union) {
        if (!isGenerated(def)) return
        unionGenV2(def)
    }

    override fun addInput(def: ViaductSchema.Input) {
        if (!isGenerated(def)) return
        inputGen(def)
    }

    override fun addInterface(def: ViaductSchema.Interface) {
        if (!isGenerated(def)) return
        interfaceGen(def)
    }

    override fun addObject(def: ViaductSchema.Object) {
        if (!isGenerated(def)) return
        objectGenV2(def)

        for (field in def.fields) {
            if (!field.args.none()) {
                fieldArgumentsInputGen(field)
            }
        }
    }
}
