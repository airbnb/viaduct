package viaduct.tenant.codegen.kotlingen

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import viaduct.graphql.schema.ViaductSchema
import viaduct.tenant.codegen.bytecode.config.ViaductBaseTypeMapper

class ArgsTest {
    private fun mkFile(): File = File.createTempFile("pre", "post")

    @Test
    fun properties() {
        val modernModuleGeneratedDir = mkFile()
        val metainfGeneratedDir = mkFile()
        val resolverGeneratedDir = mkFile()

        val args = Args(
            "TENANT PACKAGE",
            "TENANT PACKAGE PREFIX",
            "TENANT NAME",
            "GRT PACKAGE",
            modernModuleGeneratedDir,
            metainfGeneratedDir,
            resolverGeneratedDir,
            baseTypeMapper = ViaductBaseTypeMapper(ViaductSchema.Empty)
        )

        assertEquals("TENANT PACKAGE", args.tenantPackage)
        assertEquals("TENANT PACKAGE PREFIX", args.tenantPackagePrefix)
        assertEquals("TENANT NAME", args.tenantName)
        assertEquals("GRT PACKAGE", args.grtPackage)
        assertEquals(modernModuleGeneratedDir, args.modernModuleGeneratedDir)
        assertEquals(metainfGeneratedDir, args.metainfGeneratedDir)
        assertEquals(resolverGeneratedDir, args.resolverGeneratedDir)
    }
}
