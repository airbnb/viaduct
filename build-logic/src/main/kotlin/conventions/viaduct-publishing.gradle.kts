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

val publishMinimal = providers.gradleProperty("publishMinimal").isPresent

// Script plugins are the correct sharing mechanism here: build-logic/common/ utilities are on
// the compilation classpath of precompiled plugins only (src/main/kotlin/). They do NOT
// propagate to sibling subproject build scripts (shared/build.gradle.kts), because Gradle
// scopes implementation() dependencies to precompiled plugin compilation, not to subproject
// buildscript classpaths. Script plugins share the applying project's classpath, which covers
// all Gradle API types needed.
//
// Walk up the Gradle instance hierarchy to the top-level build (gradle.parent == null
// at the root). Since core/ is an included build of OSS — not the other way around —
// the top-level Gradle is always the OSS build, so its rootProject.projectDir is always
// the right anchor for resolving build-logic/gradle/viaduct-maven-central.gradle.kts.
apply(from = generateSequence(gradle) { it.parent }.last()
    .rootProject.projectDir
    .resolve("build-logic/gradle/viaduct-maven-central.gradle.kts"))

mavenPublishing {
    val isRelease = providers.environmentVariable("RELEASE").orElse("false").get().toBoolean()
    publishToMavenCentral(
        host = if (isRelease) SonatypeHost.CENTRAL_PORTAL else SonatypeHost.S01,
        automaticRelease = true
    )
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
        }
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
