import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty

plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("test-classdiff")
    id("me.champeau.jmh").version("0.7.3")
}

viaductClassDiff {
    schemaDiff("schema") {
        actualPackage.set("actuals.api.generated")
        expectedPackage.set("viaduct.api.grts")
        // Generated at build time from shared/arbitrary, seeded from this project's sources, so
        // classdiff exercises a large, varied set of schema constructs while staying reproducible.
        generatedSchemaResource("graphql/schema.graphqls")
    }
}

dependencies {
    compileOnly(libs.ksp.symbol.processing.api)

    implementation(libs.clikt.jvm)
    implementation(libs.graphql.java)
    implementation(libs.kotlinx.metadata.jvm)
    implementation(libs.viaduct.shared.invariants)
    implementation(libs.viaduct.shared.codegen)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.viaductschema)
    implementation(libs.viaduct.shared.apiannotations)

    implementation(libs.viaduct.tenant.api)
    implementation(libs.viaduct.engine.api)
    implementation(libs.viaduct.shared.bootstrap)

    implementation(libs.jackson.databind)
    implementation(libs.jackson.module)

    testImplementation(libs.viaduct.engine.api)
    testImplementation(libs.viaduct.service.api)
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.javassist)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(testFixtures(libs.viaduct.engine.api))
    testImplementation(testFixtures(libs.viaduct.shared.viaductschema))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.guava)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.ksp.symbol.processing.api)

    testImplementation(libs.viaduct.shared.graphql)

    /** Codegen classpath for test-classdiff worker isolation **/
    viaductCodegenClasspath(libs.viaduct.tenant.codegen)
    viaductCodegenClasspath(libs.viaduct.shared.arbitrary.cli)

    jmh(libs.jmh.annotation.processor)
    jmhAnnotationProcessor(libs.jmh.annotation.processor)
    jmhApi(libs.jmh.core)

    jmhImplementation(testFixtures(libs.viaduct.shared.viaductschema))
    jmhImplementation(libs.graphql.java)
    jmhImplementation(libs.guava)
}

tasks.test {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    // Forward the golden-snapshot regenerate flag to the test JVM so that running with
    // -Dviaduct.codegen.golden.regenerate=true rewrites the checked-in golden files (see
    // KotlinCodegenGoldenTest). Without this, Gradle does not propagate the system property.
    System.getProperty("viaduct.codegen.golden.regenerate")?.let {
        systemProperty("viaduct.codegen.golden.regenerate", it)
    }
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
