import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    `java-library`
    id("me.champeau.jmh") version "0.7.3"
}

val support by sourceSets.creating

kotlin {
    sourceSets.named("test") {
        kotlin.srcDir("src/test/fixtures")
    }
    target.compilations {
        named("support") { associateWith(getByName("main")) }
        named("test") { associateWith(getByName("support")) }
        named("jmh") { associateWith(getByName("support")) }
    }
}

configurations[support.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[support.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
listOf("test", "jmh").forEach { sourceSetName ->
    configurations["${sourceSetName}Implementation"].extendsFrom(configurations[support.implementationConfigurationName])
    configurations["${sourceSetName}RuntimeOnly"].extendsFrom(configurations[support.runtimeOnlyConfigurationName])
    dependencies.add("${sourceSetName}Implementation", support.output)
}
dependencies.add(support.implementationConfigurationName, sourceSets.main.get().output)

dependencies {
    api(libs.viaduct.engine.api)
    api(libs.viaduct.shared.viaductschema)
    api(libs.viaduct.shared.graphql)
    api(libs.graphql.java)
    implementation(libs.viaduct.engine.runtime)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.jdk8)

    add("supportImplementation", libs.guice)
    add("supportImplementation", libs.jakarta.inject)
    add("supportImplementation", libs.kotest.property.jvm)
    add("supportImplementation", libs.kotest.assertions.core.jvm)
    add("supportImplementation", libs.jackson.module)
    add("supportImplementation", kotlin("test"))

    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.viaduct.engine.wiring)
    testImplementation(libs.viaduct.service.api)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.shared.arbitrary))
    testImplementation(testFixtures(libs.viaduct.shared.graphql))
    testImplementation(testFixtures(libs.viaduct.service.api))
}

jmh {
    includeTests.set(false)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.moduleName.set("engine-runtime2_$name")
}
tasks.configureEach {
    notCompatibleWithConfigurationCache("Runtime2 retains qplan's configuration-cache opt-out.")
}

val developmentSources = files("src/support/kotlin", "src/test/fixtures")
tasks.named<Detekt>("detekt") {
    setSource(source + developmentSources.asFileTree)
}
tasks.named<Detekt>("findWarningsForCleanup") {
    setSource(source + developmentSources.asFileTree)
}
tasks.withType<BaseKtLintCheckTask>().configureEach {
    if (name.endsWith("TestSourceSet")) {
        setSource(files("src/test/kotlin", "src/test/fixtures"))
    }
}
tasks.named<JacocoReport>("jacocoTestReport") {
    sourceDirectories.from(support.allSource.srcDirs)
    classDirectories.from(support.output.classesDirs)
    sourceDirectories.from("src/test/fixtures")
    val fixtureClassPatterns = providers.provider {
        fileTree("src/test/fixtures") { include("**/*.kt") }.flatMap { source ->
            val text = source.readText()
            val packagePath = Regex("""(?m)^package ([\w.]+)""").find(text)!!.groupValues[1].replace('.', '/')
            val types = Regex("""(?m)^(?:(?:internal|public|data|sealed|enum|open|abstract|fun) )*(?:class|interface|object) (\w+)""")
                .findAll(text).map { it.groupValues[1] }.toList() + "${source.nameWithoutExtension}Kt"
            types.flatMap { listOf("$packagePath/$it.class", "$packagePath/$it${'$'}*.class") }
        }
    }
    classDirectories.from(sourceSets.test.get().output.classesDirs.asFileTree.matching { include(fixtureClassPatterns.get()) })
}

apply(from = "development.gradle.kts")
apply(from = "documentation.gradle.kts")
apply(from = "verification.gradle.kts")
