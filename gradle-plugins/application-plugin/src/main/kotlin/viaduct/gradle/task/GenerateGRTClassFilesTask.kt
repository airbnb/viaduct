package viaduct.gradle.task

import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import viaduct.gradle.ViaductPluginCommon
import viaduct.graphql.schema.binary.writeBSchema
import viaduct.graphql.schema.graphqljava.GJSchema

@CacheableTask
abstract class GenerateGRTClassFilesTask
    @Inject
    constructor(
        private var execOperations: ExecOperations
    ) : DefaultTask() {
        init {
            // No group: don't want this to appear in task list
            description = "Generate compiled GRT class files from the central schema."
        }

        @get:Input
        abstract val mainClass: Property<String>

        @get:Input
        abstract val buildFlags: MapProperty<String, String>

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val classpath: ConfigurableFileCollection

        @get:InputFiles
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val schemaFiles: ConfigurableFileCollection

        @get:Input
        abstract val grtPackageName: Property<String>

        @get:OutputDirectory
        abstract val grtClassesDirectory: DirectoryProperty

        @TaskAction
        fun taskAction() {
            val flagFile = temporaryDir.resolve("viaduct_build_flags")
            flagFile.writeText(ViaductPluginCommon.buildFlagFileContent(buildFlags.get()))

            val binarySchemaFile = temporaryDir.resolve("schema.bgql")
            writeBSchema(
                GJSchema.fromFiles(schemaFiles.files.toList()),
                FileOutputStream(binarySchemaFile)
            )

            execOperations.javaexec {
                classpath = this@GenerateGRTClassFilesTask.classpath
                mainClass.set(this@GenerateGRTClassFilesTask.mainClass.get())
                argumentProviders.add {
                    listOf(
                        "--schema_files",
                        schemaFiles.files.map(File::getAbsolutePath).sorted().joinToString(","),
                        "--binary_schema_file",
                        binarySchemaFile.absolutePath,
                        "--flag_file",
                        flagFile.absolutePath,
                        "--pkg_for_generated_classes",
                        grtPackageName.get(),
                        "--generated_directory",
                        grtClassesDirectory.get().asFile.absolutePath
                    )
                }
            }
        }
    }
