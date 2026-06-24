package viaduct.gradle.task

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileType
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.work.FileChange
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.gradle.workers.WorkerExecutor
import viaduct.gradle.CodegenWorkAction
import viaduct.gradle.runCodegen

/**
 * Assembles the tenant module config file by invoking the aggregation CLI.
 *
 * Reads KSP-generated descriptor JSON files from [descriptorDir] and produces
 * a single `META-INF/viaduct/modules/<tenantPackage>.json` in [outputDir].
 *
 * Gradle's up-to-date checking handles skipping this task when inputs haven't
 * changed. When the task does execute, it always reconciles outputs from the
 * full descriptor set (no task-internal delta logic).
 */
@CacheableTask
abstract class AssembleTenantModuleConfigFileTask : DefaultTask(), IncrementalActions {
    /**
     * KSP descriptor root directory containing per-file JSON descriptors.
     *
     * Annotated with `@InputFiles` (not `@InputDirectory`) because the directory may not
     * exist if no `@Resolver` classes are present. `@Optional` prevents Gradle from failing
     * when absent. `@Incremental` is retained so Gradle tracks per-file changes for
     * up-to-date checking (even though the task action currently ignores `InputChanges`).
     */
    @get:Incremental
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val descriptorDir: DirectoryProperty

    @get:Input
    abstract val tenantPackage: Property<String>

    @get:Input
    @get:Optional
    abstract val tenantPackagePrefix: Property<String>

    @get:Input
    abstract val executorFactory: Property<String>

    @get:Classpath
    abstract val codegenClasspath: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val workerExecutor: WorkerExecutor

    // Assembly is intentionally non-incremental: it aggregates all descriptors into a single
    // output file, so any change to the descriptor set requires a full reconcile. Gradle's
    // up-to-date mechanism (driven by the @InputFiles fingerprint on descriptorDir) still
    // skips this task entirely when no descriptors changed, so the cost is only paid when
    // something actually changed. See impldocs/execution-registry-ksp-pipeline.md.
    //
    // TODO: Re-enable task-internal incrementality after execution-style tests cover
    // Gradle directory add/remove events and tenant-package rename cleanup.
    @TaskAction
    fun assemble(
        @Suppress("UNUSED_PARAMETER") inputChanges: InputChanges
    ) {
        clearOwnedModuleConfigs()

        if (!descriptorDir.isPresent) {
            logger.info("No descriptor directory configured — nothing to assemble")
            return
        }
        val descRoot = descriptorDir.get().asFile

        if (goneOrEmpty(descRoot)) {
            logger.info("Descriptor directory is empty — no config to generate")
            return
        }

        assembleConfig(descRoot)
    }

    /**
     * Removes all task-owned registry files under the `META-INF/viaduct/modules`
     * subtree of [outputDir]. This ensures stale configs (e.g., from a renamed
     * tenant package) are cleaned up before regeneration.
     *
     * Scoped to the modules subtree only — does not touch unrelated siblings.
     */
    internal fun clearOwnedModuleConfigs() {
        val modulesDir = outputDir.get().asFile.resolve("META-INF/viaduct/modules")
        if (modulesDir.exists()) {
            modulesDir.listFiles()?.forEach { if (it.extension == "json") it.delete() }
        }
    }

    // ── IncrementalActions implementation ────────────────────────────────────

    override fun assembleConfig(descriptorRoot: File) {
        val args = mutableListOf(
            "--descriptor-dir",
            descriptorRoot.absolutePath,
            "--tenant-package",
            tenantPackage.get(),
            "--executor-factory",
            executorFactory.get(),
            "--output-dir",
            outputDir.get().asFile.absolutePath,
        )
        tenantPackagePrefix.orNull?.let { prefix ->
            args.add("--tenant-package-prefix")
            args.add(prefix)
        }

        workerExecutor.runCodegen(
            codegenClasspath,
            CodegenWorkAction.MainClasses.ASSEMBLE_TENANT_MODULE_CONFIG_FILE,
            args,
        )
    }

    override fun deleteConfig(pkg: String) {
        val configFile = outputDir.get().asFile
            .resolve("META-INF/viaduct/modules/$pkg.json")
        configFile.delete()
        logger.info("Removed config for package {}", pkg)
    }

    companion object {
        const val EXECUTOR_FACTORY = "viaduct.tenant.runtime.bootstrap.ViaductModernExecutorFactory"

        // TODO: Keep this routing helper for future re-enablement of true task-internal
        // incrementality once execution-style tests exist. It is intentionally not used
        // by the task action today.
        internal fun IncrementalActions.processChanges(
            descriptorRoot: File,
            tenantPackage: String,
            descriptorChanges: Iterable<FileChange>,
        ) {
            var hasChanges = false

            for (change in descriptorChanges) {
                if (change.fileType != FileType.FILE) continue
                if (change.file.extension != "json") continue
                hasChanges = true
                break
            }

            if (!hasChanges) return

            if (goneOrEmpty(descriptorRoot)) {
                deleteConfig(tenantPackage)
            } else {
                assembleConfig(descriptorRoot)
            }
        }
    }
}

/**
 * Actions that the incremental logic can perform. The task implements this
 * directly; tests provide a recording fake to verify which actions were taken.
 */
internal interface IncrementalActions {
    fun assembleConfig(descriptorRoot: File)

    fun deleteConfig(pkg: String)

    fun goneOrEmpty(dir: File): Boolean = !dir.exists() || dir.walk().none { it.isFile && it.extension == "json" }
}
