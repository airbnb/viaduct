package viaduct.tenant.codegen.kotlingen.bytecode

import java.io.File
import viaduct.apiannotations.TestingApi
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg

@TestingApi
internal class KotlinGRTFilesBuilderImpl(
    args: KotlinCodeGenArgs,
) : KotlinGRTFilesBuilder(args) {
    override fun addEnum(def: ViaductSchema.Enum) {
        if (!def.isGenerated) return
        val dst = File(args.dirForOutput, "${def.name}.kt")
        enumKotlinGen(def).write(dst)
    }

    override fun addInput(def: ViaductSchema.Input) {
        if (!def.isGenerated) return
        val desc = InputTypeDescriptor(def.name, def.fields, def)
        val dst = File(args.dirForOutput, "${def.name}.kt")
        inputKotlinGen(desc, cfg.INPUT_GRT.toString()).write(dst)
    }

    override fun addInterface(def: ViaductSchema.Interface) {
        if (!def.isGenerated) return
        val dst = File(args.dirForOutput, "${def.name}.kt")
        interfaceKotlinGen(def).write(dst)
    }

    override fun addObject(def: ViaductSchema.Object) {
        if (!def.isGenerated) return
        val dst = File(args.dirForOutput, "${def.name}.kt")
        objectKotlinGen(def).write(dst)

        for (field in def.fields) {
            if (!field.args.none()) {
                val n = cfg.argumentTypeName(field)
                val d = File(args.dirForOutput, "$n.kt")
                inputKotlinGen(
                    InputTypeDescriptor(n, field.args, null),
                    cfg.ARGUMENTS_GRT.toString()
                ).write(d)
            }
        }
    }

    override fun addUnion(def: ViaductSchema.Union) {
        if (!def.isGenerated) return
        val dst = File(args.dirForOutput, "${def.name}.kt")
        unionKotlinGen(def).write(dst)
    }
}
