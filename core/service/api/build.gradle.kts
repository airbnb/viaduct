import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import viaduct.gradle.internal.repoRoot

plugins {
    `java-library`
    `java-test-fixtures`
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("conventions.dokka")
    id("conventions.bcv-api")
    id("conventions.viaduct-publishing")
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileKotlin") {
    compilerOptions.moduleName.set("service-api")
}

tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestFixturesKotlin") {
    compilerOptions.moduleName.set("service-api_testFixtures")
}

dependencies {
    /** External dependencies **/
    implementation(libs.guice)
    implementation(libs.graphql.java)

    /** Viaduct dependencies **/
    api(libs.viaduct.shared.apiannotations)
    implementation(libs.viaduct.shared.graphql)

    /** Test fixtures dependencies **/
    testFixturesImplementation(libs.viaduct.engine.api)

    /** Test dependencies - External **/
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Suppress Gradle module metadata: the .module file would list apiannotations and graphql as
// dependencies, but those have no separate Maven artifact. Gradle falls back to the POM instead.
tasks.withType<GenerateModuleMetadata> {
    enabled = false
}

// Strip viaduct.shared.* from the published POM: those classes are bundled inside the Gradle
// plugin JARs that consume this module, so consumers do not need to resolve them separately.
pluginManager.withPlugin("maven-publish") {
    project.extensions.getByType(PublishingExtension::class.java)
        .publications.withType(MavenPublication::class.java).configureEach {
            pom.withXml {
                // asNode().get("dependencies") returns the <dependencies> container node(s);
                // children() yields the individual <dependency> entries inside it.
                (asNode().get("dependencies") as groovy.util.NodeList)
                    .filterIsInstance<groovy.util.Node>()
                    .forEach { depsContainer ->
                        val toRemove = (depsContainer.children() as groovy.util.NodeList)
                            .filterIsInstance<groovy.util.Node>()
                            .filter { dep ->
                                val groupId = (
                                    (dep.get("groupId") as groovy.util.NodeList)
                                        .firstOrNull() as? groovy.util.Node
                                )?.text() ?: ""
                                groupId.startsWith("com.airbnb.viaduct.shared")
                            }
                        toRemove.forEach { depsContainer.remove(it) }
                    }
            }
        }
}

dokka {
    dokkaPublications.html {
        outputDirectory.set(repoRoot().dir("docs/site/apis/service-api"))
        includes.from(layout.projectDirectory.file("module.md"))
    }
    pluginsConfiguration.html {
        customStyleSheets.from(repoRoot().file("docs/kdoc-service-styles.css"))
    }
}
