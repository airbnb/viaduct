package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

internal class RecordingCodeGenerator : CodeGenerator {
    val outputs = linkedMapOf<String, String>()

    override val generatedFile: Collection<File> = emptyList()

    override fun createNewFile(
        dependencies: Dependencies,
        packageName: String,
        fileName: String,
        extensionName: String,
    ): OutputStream {
        throw UnsupportedOperationException("createNewFile is not used in these tests")
    }

    override fun createNewFileByPath(
        dependencies: Dependencies,
        path: String,
        extensionName: String,
    ): OutputStream {
        val key = "$path.$extensionName"
        return object : ByteArrayOutputStream() {
            override fun close() {
                super.close()
                outputs[key] = toString(Charsets.UTF_8.name())
            }
        }
    }

    override fun associateWithClasses(
        classes: List<KSClassDeclaration>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) {
        // Not needed for these tests.
    }

    override fun associate(
        sources: List<KSFile>,
        packageName: String,
        fileName: String,
        extensionName: String,
    ) {
        // Not needed for these tests.
    }

    override fun associateByPath(
        sources: List<KSFile>,
        path: String,
        extensionName: String,
    ) {
        // Not needed for these tests.
    }
}
