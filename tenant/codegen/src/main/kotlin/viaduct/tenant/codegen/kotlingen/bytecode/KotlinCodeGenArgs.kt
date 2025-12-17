package viaduct.tenant.codegen.kotlingen.bytecode

import java.io.File
import viaduct.apiannotations.TestingApi
import viaduct.tenant.codegen.bytecode.config.BaseTypeMapper
import viaduct.utils.timer.Timer

@TestingApi
data class KotlinCodeGenArgs(
    val pkgForGeneratedClasses: String,
    val dirForOutput: File,
    val timer: Timer,
    val baseTypeMapper: BaseTypeMapper,
)
