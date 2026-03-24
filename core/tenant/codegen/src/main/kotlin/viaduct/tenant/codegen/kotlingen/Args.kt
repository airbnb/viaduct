package viaduct.tenant.codegen.kotlingen

import java.io.File
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper

class Args(
    val tenantPackage: String,
    val tenantPackagePrefix: String,
    val tenantName: String,
    val grtPackage: String,
    val modernModuleGeneratedDir: File?,
    val metainfGeneratedDir: File?,
    val resolverGeneratedDir: File,
    val isFeatureAppTest: Boolean = false,
    val baseTypeMapper: BaseTypeMapper,
)
