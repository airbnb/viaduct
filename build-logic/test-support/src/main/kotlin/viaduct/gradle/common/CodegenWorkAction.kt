package viaduct.gradle.common

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import org.gradle.workers.WorkerExecutor

/**
 * A generic WorkAction that invokes a main class in a process-isolated context.
 *
 * This is used to run Viaduct codegen CLIs without requiring them on the plugin's
 * compile classpath. The codegen JARs are provided at execution time via the worker's
 * classpath configuration.
 *
 * Process isolation is used rather than classloader isolation because classLoaderIsolation
 * creates a new ClassLoader per invocation and loads the (large) codegen JARs into the
 * Gradle daemon's metaspace each time. With many codegen tasks this causes metaspace
 * exhaustion. processIsolation keeps codegen in a separate
 * JVM, so its metaspace has no effect on the daemon.
 */
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
        const val BINARY_SCHEMA_GENERATOR = "viaduct.tenant.codegen.cli.BinarySchemaGenerator\$Main"
        const val SCHEMA_OBJECTS_BYTECODE = "viaduct.tenant.codegen.cli.SchemaObjectsBytecode\$Main"
        const val VIADUCT_GENERATOR = "viaduct.tenant.codegen.cli.ViaductGenerator\$Main"
        const val KOTLIN_GRTS_GENERATOR = "viaduct.tenant.codegen.cli.KotlinGRTsGenerator\$Main"
        const val JAVA_GRTS_GENERATOR = "viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator\$Main"
    }
}

/**
 * Submits a codegen main class to a process-isolated worker and waits for completion.
 */
fun WorkerExecutor.runCodegen(
    classpath: ConfigurableFileCollection,
    mainClass: String,
    args: List<String>
) {
    val queue = processIsolation { this.classpath.from(classpath) }
    queue.submit(CodegenWorkAction::class.java) {
        this.mainClass.set(mainClass)
        this.args.set(args)
    }
    await()
}

/**
 * Creates or retrieves the shared viaductCodegenClasspath configuration.
 */
fun Project.getOrCreateCodegenClasspath(): Configuration =
    configurations.maybeCreate("viaductCodegenClasspath").apply {
        isCanBeConsumed = false
        isCanBeResolved = true
    }
