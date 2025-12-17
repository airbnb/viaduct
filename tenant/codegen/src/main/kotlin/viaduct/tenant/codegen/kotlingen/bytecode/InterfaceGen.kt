package viaduct.tenant.codegen.kotlingen.bytecode

// See README.md for the patterns that guided this file

import viaduct.apiannotations.TestingApi
import viaduct.codegen.km.getterName
import viaduct.codegen.km.kotlinTypeString
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.codegen.utils.JavaName
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg
import viaduct.tenant.codegen.bytecode.config.isNode
import viaduct.tenant.codegen.bytecode.config.kmType

@TestingApi
fun KotlinGRTFilesBuilder.interfaceKotlinGen(typeDef: ViaductSchema.Interface) = STContents(interfaceSTGroup, InterfaceModelImpl(typeDef, pkg, reflectedTypeGen(typeDef), baseTypeMapper))

private interface InterfaceModel {
    /** Packege into which code will be generated. */
    val pkg: String

    /** Name of the class to be generated. */
    val className: String

    /** Comma-separated list of supertypes of
     *  this class by virtue of GraphQL `implements`
     *  clauses in the GraphQL schema, plus tagging interfaces.
     */
    val superTypes: String

    /** Submodels for each field that needs a suspending getter. */
    val fieldsNeedingGetter: List<FieldModel>

    /** A rendered template string that describes this types Reflection object */
    val reflection: String

    /** Submodel for "fields" in this type. */
    class FieldModel(
        pkg: String,
        fieldDef: ViaductSchema.Field,
        baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
    ) {
        /** Field getter name. */
        val getterName: String = getterName(fieldDef.name)

        /** Kotlin GRT-type of this field. */
        val kotlinType: String = fieldDef.kmType(JavaName(pkg).asKmName, baseTypeMapper).kotlinTypeString
    }
}

private val interfaceSTGroup =
    stTemplate(
        """
    @file:Suppress("warnings")

    package <mdl.pkg>

    interface <mdl.className> : <mdl.superTypes> {
        <mdl.fieldsNeedingGetter: { f |
          suspend fun <f.getterName>(alias: String?): <f.kotlinType>
          suspend fun <f.getterName>(): <f.kotlinType>
        }; separator="\n">

        <mdl.reflection>
    }
"""
    )

private class InterfaceModelImpl(
    private val typeDef: ViaductSchema.Interface,
    override val pkg: String,
    reflectedType: STContents,
    baseTypeMapper: viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
) : InterfaceModel {
    override val className = typeDef.name

    override val reflection: String = reflectedType.toString()

    override val superTypes = run {
        val result = mutableListOf(cfg.INTERFACE_GRT.toString())

        // Add NodeCompositeOutput for Node interfaces
        if (typeDef.isNode) {
            result.add("viaduct.api.types.NodeCompositeOutput")
        }

        for (s in typeDef.supers) {
            result.add("$pkg.${s.name}")
        }
        result.joinToString(", ")
    }

    override val fieldsNeedingGetter: List<InterfaceModel.FieldModel> =
        typeDef.fields.filter { !it.isOverride }.map { InterfaceModel.FieldModel(pkg, it, baseTypeMapper) }
}
