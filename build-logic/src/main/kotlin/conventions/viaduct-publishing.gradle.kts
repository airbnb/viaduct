package conventions

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.*
import javax.inject.Inject
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.configure

plugins {
    id("com.vanniktech.maven.publish")
    id("conventions.dokka")
    signing
}

abstract class ViaductPublishingExtension @Inject constructor(objects: ObjectFactory) {
    val artifactId: Property<String> = objects.property(String::class.java).convention("")
    val name: Property<String> = objects.property(String::class.java).convention("")
    val description: Property<String> = objects.property(String::class.java).convention("")
}

val viaductPublishing = extensions.create<ViaductPublishingExtension>("viaductPublishing")

mavenPublishing {
    val isRelease = providers.environmentVariable("RELEASE").orElse("false").get().toBoolean()
    publishToMavenCentral(automaticRelease = false)
    if (isRelease) {
        signAllPublications()
    }
    when {
        plugins.hasPlugin("java-platform") -> configure(JavaPlatform())
        plugins.hasPlugin("com.gradle.plugin-publish") -> configure(GradlePublishPlugin())
        else -> configure(KotlinJvm(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationJavadoc"), sourcesJar = true))
    }
}

// 🔑 Defer coordinates() until after the consumer has configured viaductPublishing { ... }.
afterEvaluate {
    // Resolve lazily here (now it's safe to .get()).
    val resolvedArtifactId = viaductPublishing.artifactId.get().ifBlank { project.name }
    val resolvedName = viaductPublishing.name.get().ifBlank { project.name }.let { "Viaduct :: $it" }
    val resolvedDescription = viaductPublishing.description.get().ifBlank { "" }

    extensions.configure<MavenPublishBaseExtension> {
        coordinates(project.group.toString(), resolvedArtifactId, project.version.toString())

        pom {
            name.set(resolvedName)
            if (resolvedDescription.isNotBlank()) description.set(resolvedDescription) else description.set("Viaduct library $resolvedArtifactId")

            url.set("https://airbnb.io/viaduct/")
            licenses { license {
                name.set("Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }}
            developers { developer {
                id.set("airbnb"); name.set("Airbnb, Inc.")
                email.set("viaduct-maintainers@airbnb.com")
            }}
            scm {
                connection.set("scm:git:git://github.com/airbnb/viaduct.git")
                developerConnection.set("scm:git:ssh://github.com/airbnb/viaduct.git")
                url.set("https://github.com/airbnb/viaduct")
            }
        }
    }

    signing {
        val signingKeyId: String? by project
        val signingKey: String? by project
        val signingPassword: String? by project
        useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
        setRequired {
            gradle.taskGraph.allTasks.any { it is PublishToMavenRepository }
        }
        project.logger.lifecycle(publishing.publications.toString())
        publishing.publications.forEach { project.logger.lifecycle("Publication: ${it.name}") }
        publishing.publications.forEach { sign(it) }
    }
}

// Keep your versionMapping, but only for JVM modules
plugins.withId("org.jetbrains.kotlin.jvm") {
    publishing {
        publications.withType(MavenPublication::class.java).configureEach {
            versionMapping {
                usage("java-api") { fromResolutionOf("runtimeClasspath") }
                usage("java-runtime") { fromResolutionResult() }
            }
        }
    }
}
