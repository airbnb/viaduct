plugins {
    id("java")
    id("maven-publish")
}

val projectDependencies: Configuration by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = true
}

val nonTransitiveProjectDependencies: Configuration by configurations.creating {
    isTransitive = false // we don't want the transitive dependencies of the projects to be included
    extendsFrom(projectDependencies)
}

val projectNames = setOf(
    ":engine:engine-api",
    ":engine:engine-runtime",
    ":service:service-api",
    ":service:service-runtime",
    ":shared:arbitrary",
    ":shared:graphql",
    ":shared:logging",
    ":shared:utils",
    ":tenant:tenant-api",
    ":tenant:tenant-codegen",
    ":tenant:tenant-runtime",
    ":shared:arbitrary",
    ":shared:dataloader",
    ":shared:deferred",
    ":shared:graphql",
    ":shared:shared-codegen",
    ":shared:invariants",
    ":shared:logging",
    ":shared:viaductschema",
    ":snipped:errors",
)

val projectsToPackage = rootProject.subprojects.filter { it.path in projectNames }

dependencies {
    projectsToPackage.forEach {
        projectDependencies(project(it.path))
    }
}

@CacheableTask
abstract class UnpackProjectDependenciesTask : DefaultTask() {

    // TODO: task can be made incremental, would be more efficient: https://docs.gradle.org/current/userguide/custom_tasks.html#incremental_tasks

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val artifacts: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:Inject
    abstract val archives: ArchiveOperations

    @TaskAction
    fun unpack() {
        val outDir = outputDir.get()

        fs.delete { delete(outDir) }

        artifacts.files.forEach { jar ->
            fs.copy {
                from(archives.zipTree(jar))
                into(outDir)
            }
        }
    }
}

val unpackProjectDependencies by tasks.registering(UnpackProjectDependenciesTask::class) {
    artifacts.from(nonTransitiveProjectDependencies.incoming.artifacts.artifactFiles)
    outputDir = layout.buildDirectory.dir("unpacked-project-dependencies")
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(unpackProjectDependencies.flatMap { it.outputDir })
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "runtime"
            version = project.version.toString()
            artifact(tasks.jar.get())

            pom.withXml {
                val depsNode = asNode().appendNode("dependencies")
                projectsToPackage.forEach { sub ->
                    sub.configurations.findByName("implementation")?.allDependencies
                        ?.filterIsInstance<ExternalModuleDependency>()
                        ?.forEach { dep ->
                            val depNode = depsNode.appendNode("dependency")
                            depNode.appendNode("groupId", dep.group)
                            depNode.appendNode("artifactId", dep.name)
                            depNode.appendNode("version", dep.version)
                            depNode.appendNode("scope", "compile")
                        }
                }
            }
        }
    }

    repositories {
        mavenLocal()
    }
}
