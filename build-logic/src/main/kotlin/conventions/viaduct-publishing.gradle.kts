package conventions

import buildroot.registerForOrchestrationAggregate
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.*
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.plugin.devel.GradlePluginDevelopmentExtension
import org.gradle.plugins.signing.SigningExtension
import org.gradle.work.DisableCachingByDefault
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register

plugins {
    id("com.vanniktech.maven.publish")
    id("conventions.dokka")
    id("conventions.security-scanning")
    signing
}

base.archivesName.set(project.path.removePrefix(":").replace(":", "-"))

abstract class ViaductPublishingExtension @Inject constructor(objects: ObjectFactory) {
    val name: Property<String> = objects.property(String::class.java).convention("")
    val description: Property<String> = objects.property(String::class.java).convention("")
}

val viaductPublishing = extensions.create<ViaductPublishingExtension>("viaductPublishing")

val publishMinimal = providers.gradleProperty("publishMinimal").isPresent

// Self-report each publish task to the orchestration registry instead of having a root
// project's `subprojects { }` block read this project's task container.
registerForOrchestrationAggregate("publishToMavenLocal", "publishToMavenLocal")
registerForOrchestrationAggregate("publishToMavenCentral", "publishAllPublicationsToMavenCentralRepository")
registerForOrchestrationAggregate("publishToSnapshots", "publishAllPublicationsToSnapshotsRepository")

@DisableCachingByDefault(because = "Writes a single small file")
abstract class WritePublishedCoordinatesTask : DefaultTask() {
    @get:Input abstract val coordinates: ListProperty<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty

    @TaskAction
    fun run() {
        outputFile.get().asFile
            .writeText(coordinates.get().joinToString(separator = "\n", postfix = "\n"))
    }
}

val writePublishedCoordinates = tasks.register<WritePublishedCoordinatesTask>("writePublishedCoordinates") {
    description = "Records this project's published coordinates for the release workflow to probe."
    outputFile.set(layout.buildDirectory.file("reports/publication/coordinates.txt"))
}
registerForOrchestrationAggregate("writePublishedCoordinates", "writePublishedCoordinates")

// `gradlePlugin.plugins` is empty until the consumer's block is evaluated.
afterEvaluate {
    val publishedVersion = project.version.toString()
    val central = "central ${project.group}:${project.name}:$publishedVersion"
    val markers = if (pluginManager.hasPlugin("com.gradle.plugin-publish")) {
        extensions.getByType(GradlePluginDevelopmentExtension::class.java)
            .plugins.map { "portal ${it.id}:${it.id}.gradle.plugin:$publishedVersion" }
    } else {
        emptyList()
    }
    writePublishedCoordinates.configure { coordinates.set(listOf(central) + markers) }
}

// Apply standard Viaduct POM metadata to all Maven publications.
pluginManager.withPlugin("maven-publish") {
    project.extensions.getByType(PublishingExtension::class.java)
        .publications.withType(MavenPublication::class.java).configureEach {
            pom {
                url.set("https://viaduct.airbnb.tech/")
                licenses {
                    license {
                        name.set("Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                developers {
                    developer {
                        id.set("airbnb")
                        name.set("Airbnb, Inc.")
                        email.set("viaduct-maintainers@airbnb.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/airbnb/viaduct.git")
                    developerConnection.set("scm:git:ssh://github.com/airbnb/viaduct.git")
                    url.set("https://github.com/airbnb/viaduct")
                }
            }
        }
}

// Configure in-memory PGP signing for all Viaduct publications.
val signingKeyId = findProperty("signingKeyId") as String?
val signingKey = findProperty("signingKey") as String?
val signingPassword = findProperty("signingPassword") as String?
pluginManager.withPlugin("signing") {
    val signingExt = project.extensions.getByType(SigningExtension::class.java)
    signingExt.useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    signingExt.setRequired { gradle.taskGraph.allTasks.any { it is PublishToMavenRepository } }
    pluginManager.withPlugin("maven-publish") {
        val publications = project.extensions.getByType(PublishingExtension::class.java).publications
        signingExt.sign(publications)
    }
}

mavenPublishing {
    val isRelease = providers.environmentVariable("RELEASE").orElse("false").get().toBoolean()
    publishToMavenCentral(automaticRelease = true)
    if (isRelease) {
        signAllPublications()
    }
    when {
        plugins.hasPlugin("java-platform") -> configure(JavaPlatform())
        plugins.hasPlugin("com.gradle.plugin-publish") -> configure(GradlePublishPlugin())
        else -> configure(KotlinJvm(
            javadocJar = if (publishMinimal) JavadocJar.Empty() else JavadocJar.Dokka("dokkaGeneratePublicationJavadoc"),
            sourcesJar = !publishMinimal
        ))
    }
}

// For snapshot publications, add the Central Portal snapshots repository.
// The vanniktech plugin v0.34.0 uses Central Portal for releases but doesn't route -SNAPSHOT
// versions to the snapshot endpoint. This adds a standard Maven repository so
// `publishAllPublicationsToSnapshotsRepository` can publish snapshots to Central Portal.
// The workflow calls `publishToSnapshots` (orchestrated) instead of `publishToMavenCentral`
// when in snapshot mode.
run {
    val isRelease = providers.environmentVariable("RELEASE").orElse("false").get().toBoolean()
    if (!isRelease) {
        publishing {
            repositories {
                maven {
                    name = "snapshots"
                    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
                    credentials {
                        username = providers.gradleProperty("mavenCentralUsername").orNull
                        password = providers.gradleProperty("mavenCentralPassword").orNull
                    }
                }
            }
        }
    }
}

// 🔑 Defer coordinates() until after the consumer has configured viaductPublishing { ... }.
afterEvaluate {
    // Resolve lazily here (now it's safe to .get()).
    val resolvedName = viaductPublishing.name.get().ifBlank { project.name }.let { "Viaduct :: $it" }
    val resolvedDescription = viaductPublishing.description.get().ifBlank { "" }

    extensions.configure<MavenPublishBaseExtension> {
        coordinates(project.group.toString(), project.name, project.version.toString())

        pom {
            name.set(resolvedName)
            if (resolvedDescription.isNotBlank()) description.set(resolvedDescription) else description.set("Viaduct library ${project.name}")
        }
    }
}

plugins.withId("org.jetbrains.kotlin.jvm") {
    publishing {
        publications.withType(MavenPublication::class.java).configureEach {
            versionMapping {
                allVariants { fromResolutionResult() }
            }
        }
    }
}
