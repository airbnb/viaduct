package viaduct.tenant.codegen.bytecode.exercise

import org.junit.jupiter.api.Assertions.assertTrue
import viaduct.codegen.utils.JavaName
import viaduct.invariants.InvariantChecker

internal const val pkg = "pkg"
internal val pkgName = JavaName(pkg)

// internal val codeGenArgs: CodeGenArgs = CodeGenArgs(
//     moduleName = null,
//     pkgForGeneratedClasses = pkg,
//     includeIneligibleTypesForTestingOnly = false,
//     excludeCrossModuleFields = false,
//     javaTargetVersion = null,
//     workerNumber = 0,
//     workerCount = 1,
//     timer = Timer(),
//     baseTypeMapper = AirBnbConfig.baseTypeMapper,
// )
//
// internal fun ViaductExtendedSchema.toClassLoader(): ClassLoader =
//     GRTClassFilesBuilderClassic(codeGenArgs)
//         .addAll(this)
//         .buildClassLoader()

internal fun InvariantChecker.assertContainsLabels(vararg labels: String): InvariantChecker {
    val exp = labels.toSet()
    val allLabels = map { it.label }.toSet()
    assertTrue(allLabels.containsAll(exp)) {
        val missing = exp - allLabels
        val missingStr = missing.joinToString(", ")
        val allLabelsStr = if (allLabels.isEmpty()) "<empty>" else allLabels.joinToString(", ")
        """
            Missing labels: $missingStr
            All labels: $allLabelsStr

        """.trimIndent()
    }
    return this
}
