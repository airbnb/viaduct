package viaduct.tenant.codegen.kotlingen.bytecode

// See README.md for the patterns that guided this file

import viaduct.apiannotations.TestingApi
import viaduct.codegen.st.STContents
import viaduct.codegen.st.stTemplate
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.cfg

@TestingApi
fun KotlinGRTFilesBuilder.enumKotlinGen(typeDef: ViaductSchema.Enum) = STContents(enumSTGroup, EnumModelImpl(typeDef, pkg, reflectedTypeGen(typeDef)))

interface EnumModel {
    /** Packege into which code will be generated. */
    val pkg: String

    /** Name of the class to be generated. */
    val className: String

    /** Names of the values of this enumeration. */
    val valueNames: List<String>

    /** A rendered template string that describes this types Reflection object */
    val reflection: String
}

private val enumSTGroup = stTemplate(
    """
    @file:Suppress("warnings")

    package <mdl.pkg>

    enum class <mdl.className> : ${cfg.ENUM_GRT} {
        <mdl.valueNames: {valueName | <valueName>}; separator=",\n">;

        <mdl.reflection>
    }
"""
)

private class EnumModelImpl(
    private val typeDef: ViaductSchema.Enum,
    override val pkg: String,
    reflectedType: STContents
) : EnumModel {
    override val className get() = typeDef.name
    override val valueNames get() = typeDef.values.map { it.name }
    override val reflection: String = reflectedType.toString()
}
