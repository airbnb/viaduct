plugins {
    id("conventions.kotlin")
    id("me.champeau.jmh").version("0.7.3")
    `java-test-fixtures`
    id("conventions.kotlin-static-analysis")
}

tasks.test {
    environment("PACKAGE_WITH_SCHEMA", "invalidschemapkg")
}

// CLI run task - the CLI lives in testFixtures since it depends on SchemaDiff
tasks.register<JavaExec>("run") {
    description = "Run the BSchema CLI"
    mainClass.set("viaduct.graphql.schema.binary.cli.CLIKt")
    classpath = sourceSets.named("testFixtures").get().runtimeClasspath
}

// Schema CLI run task - for schema2csv and other schema analysis tools
tasks.register<JavaExec>("runSchemaCli") {
    description = "Run the Schema CLI (schema2csv, etc.)"
    mainClass.set("viaduct.graphql.schema.cli.CLIKt")
    classpath = sourceSets.named("testFixtures").get().runtimeClasspath
}

dependencies {
    api(libs.graphql.java)
    api(libs.viaduct.shared.apiannotations)
    api(libs.viaduct.shared.invariants)
    api(libs.viaduct.shared.graphql)
    api(libs.viaduct.shared.utils)

    implementation(libs.guava)
    implementation(libs.kotlin.reflect)
    implementation(libs.jspecify)

    testFixturesApi(libs.graphql.java)
    testFixturesCompileOnly(libs.junit)
    testFixturesApi(libs.viaduct.shared.invariants)

    testFixturesImplementation(libs.clikt.jvm)
    testFixturesImplementation(libs.guava)
    testFixturesImplementation(libs.kotest.assertions.core.jvm)
    testFixturesImplementation(libs.kotlin.reflect)
    testFixturesImplementation(libs.viaduct.shared.graphql)

    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.kotest.assertions.shared)
    testImplementation(libs.kotest.property.jvm)
    testImplementation(libs.viaduct.shared.arbitrary)
    testImplementation(testFixtures(libs.viaduct.shared.arbitrary))

    jmh(libs.jmh.annotation.processor)

    jmhAnnotationProcessor(libs.jmh.annotation.processor)

    jmhApi(libs.jmh.core)
    jmhApi(libs.viaduct.shared.arbitrary)
    jmhApi(testFixtures(libs.viaduct.shared.arbitrary))

    jmhImplementation(libs.graphql.java)
    jmhImplementation(libs.guava)
    jmhImplementation(libs.kotest.property.jvm)
    jmhImplementation(testFixtures(project))
}
