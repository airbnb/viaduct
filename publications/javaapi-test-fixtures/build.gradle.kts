import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.util.zip.ZipFile

plugins {
    id("conventions.viaduct-fat-jar")
}

viaductPublishing {
    name.set("Java Test Fixtures")
    description.set("Convenience module for testing Java Viaduct tenants")
}

dependencies {
    api(testFixtures(libs.viaduct.javaapi.api))
    fatJarLayers(project(":api"))
    fatJarLayers(project(":runtime"))
    fatJarLayers(project(":javaapi-api"))
}

tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(project.configurations.compileClasspath.get())
    exclude("kotlin/**")
    exclude("kotlinx/**")
    exclude("org/jetbrains/**")
    exclude("_COROUTINE/**")
}

val checkJavaTestFixturesPublication = tasks.register("checkJavaTestFixturesPublication") {
    group = "verification"
    val fixturesJar = tasks.named<ShadowJar>("shadowJar").flatMap { it.archiveFile }
    inputs.file(fixturesJar)
    doLast {
        val entries = ZipFile(fixturesJar.get().asFile).use { archive ->
            archive.entries().asSequence().map { it.name }.filter { !it.endsWith("/") && !it.startsWith("META-INF/") }.toList()
        }
        check("viaduct/java/api/testing/ResolverTestBase.class" in entries) { "Java test fixtures JAR does not ship ResolverTestBase" }
        val outside = entries.filterNot { it.startsWith("viaduct/java/api/testing/") }
        check(outside.isEmpty()) { "Java test fixtures JAR ships ${outside.size} entries outside viaduct/java/api/testing/, e.g. ${outside.take(5)}" }
    }
}

tasks.named("check") { dependsOn(checkJavaTestFixturesPublication) }
