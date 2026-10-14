import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("com.gradleup.shadow")
    application
}

description = "Code generator for Java GRTs (GraphQL Representational Types) from GraphQL schemas"

application {
    mainClass.set("viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator\$Main")
}

dependencies {
    // CLI
    implementation(libs.clikt.jvm)

    // GraphQL parsing
    implementation(libs.graphql.java)

    // ViaductSchema - abstraction layer for GraphQL schema
    implementation(libs.viaduct.shared.viaductschema)

    // Template engine + shared language-neutral schema analysis (SchemaAnalysis)
    implementation(libs.viaduct.shared.codegen)

    // ScopeAndTenantLocalSchemaFilter — codegen-time @scope filtering reused from the Kotlin
    // tenant codegen (see SchemaScopeFilter). Matches the Bazel dep on tenant/codegen:schema.
    implementation(libs.viaduct.tenant.codegen)

    // Testing
    testImplementation(libs.assertj.core)

    // For GraphQLInput interface in exercise tests
    testImplementation(project(":javaapi:api"))
    // Generated argument builders use the shared synthetic input-type factory.
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(libs.viaduct.tenant.shared)
}

// Forward the golden-snapshot regenerate flag to the test JVM so that running with
// -Dviaduct.codegen.golden.regenerate=true rewrites the checked-in golden files (see
// JavaCodegenGoldenTest). Without this, Gradle does not propagate the system property.
tasks.withType<Test>().configureEach {
    System.getProperty("viaduct.codegen.golden.regenerate")?.let {
        systemProperty("viaduct.codegen.golden.regenerate", it)
    }
}

// Create fat jar with all dependencies for CLI usage
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("")
    archiveBaseName.set("java-grts-codegen")
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "viaduct.x.javaapi.codegen.cli.JavaGRTsGenerator\$Main"
    }
}

tasks.named<Jar>("jar") {
    archiveClassifier.set("thin")
}

tasks.named("assemble") {
    dependsOn(tasks.shadowJar)
}

abstract class GenerateGoldenIndex : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val snapshotsDirectory: DirectoryProperty

    @get:OutputFile
    abstract val indexFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val root = snapshotsDirectory.get().asFile
        val output = indexFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }
                .sorted().joinToString("\n", postfix = "\n")
        )
    }
}

val generateGoldenIndex by tasks.registering(GenerateGoldenIndex::class) {
    snapshotsDirectory.set(layout.projectDirectory.dir("src/test/resources/golden/expected"))
    indexFile.set(layout.buildDirectory.file("generated/golden/expected.index"))
}

tasks.processTestResources {
    from(generateGoldenIndex) {
        into("golden")
    }
}
