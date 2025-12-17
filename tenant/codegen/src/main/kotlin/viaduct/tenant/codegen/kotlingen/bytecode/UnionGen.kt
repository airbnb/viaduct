package viaduct.tenant.codegen.kotlingen.bytecode

// See README.md for the patterns that guided this file

import viaduct.apiannotations.TestingApi
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg

@TestingApi
fun KotlinGRTFilesBuilder.unionKotlinGen(typeDef: ViaductSchema.Union) = STContents(unionSTGroup, UnionModelImpl(typeDef, pkg, reflectedTypeGen(typeDef)))

private interface UnionModel {
    /** Packege into which code will be generated. */
    val pkg: String

    /** Name of the class to be generated. */
    val className: String

    /** A rendered template string that describes this types Reflection object */
    val reflection: String
}

private val unionSTGroup = stTemplate(
    """
    @file:Suppress("warnings")

    package <mdl.pkg>

    interface <mdl.className> : ${cfg.UNION_GRT} {
      <mdl.reflection>
    }
"""
)

private class UnionModelImpl(
    private val typeDef: ViaductSchema.Union,
    override val pkg: String,
    reflectedType: STContents
) : UnionModel {
    override val reflection: String = reflectedType.toString()

    override val className = typeDef.name
}
