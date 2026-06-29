package viaduct.gradle

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor
import viaduct.apiannotations.InternalApi

/**
 * A generic [WorkAction] that invokes a main class in a process-isolated worker.
 *
 * Codegen tools are external artifacts resolved via explicit tool classpaths
 * (e.g. `viaductCodegenClasspath`). Process isolation keeps them out of the Gradle
 * daemon's metaspace and avoids classloader/memory issues.
 */
@InternalApi
abstract class CodegenWorkAction : WorkAction<CodegenWorkAction.Params> {
    interface Params : WorkParameters {
        val mainClass: Property<String>
        val args: ListProperty<String>
    }

    override fun execute() {
        val cls = Class.forName(parameters.mainClass.get())
        val method = cls.getMethod("main", Array<String>::class.java)
        method.isAccessible = true
        method.invoke(null, parameters.args.get().toTypedArray())
    }

    object MainClasses {
        const val SCHEMA_OBJECTS_BYTECODE = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
        const val VIADUCT_GENERATOR = "viaduct.tenant.codegen.cli.ViaductGenerator\$Main"
        const val JAVA_GRTS_GENERATOR = "viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator\$Main"
        const val ASSEMBLE_TENANT_MODULE_CONFIG_FILE = "viaduct.tenant.codegen.cli.AssembleTenantModuleConfigFile\$Main"
    }
}

/**
 * Submits a codegen main class to a process-isolated worker and waits for completion.
 */
@InternalApi
fun WorkerExecutor.runCodegen(
    classpath: ConfigurableFileCollection,
    mainClass: String,
    args: List<String>
) {
    val queue = processIsolation { it.classpath.from(classpath) }
    queue.submit(CodegenWorkAction::class.java) {
        it.mainClass.set(mainClass)
        it.args.set(args)
    }
    await()
}
