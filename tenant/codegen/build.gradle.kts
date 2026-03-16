plugins {
    id("conventions.kotlin")
    id("conventions.kotlin-static-analysis")
    id("test-classdiff")
    id("me.champeau.jmh").version("0.7.3")
    `maven-publish`
}

viaductPublishing {
    name.set("Codegen")
    description.set("The Viaduct code generator and command-line interface.")
}

viaductClassDiff {
    schemaDiff("schema") {
        actualPackage.set("actuals.api.generated")
        expectedPackage.set("viaduct.api.grts")
        schemaResource("graphql/schema.graphqls")
    }
}

dependencies {
    implementation(libs.clikt.jvm)
    implementation(libs.graphql.java)
    implementation(libs.kotlinx.metadata.jvm)
    implementation(libs.viaduct.shared.invariants)
    implementation(libs.viaduct.shared.codegen)
    implementation(libs.viaduct.shared.utils)
    implementation(libs.viaduct.shared.viaductschema)
    implementation(libs.viaduct.shared.apiannotations)

    runtimeOnly(libs.viaduct.tenant.api)

    testImplementation(libs.viaduct.engine.api)
    testImplementation(libs.viaduct.service.api)
    testImplementation(libs.viaduct.tenant.api)
    testImplementation(libs.viaduct.tenant.runtime)
    testImplementation(libs.io.mockk.dsl)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.javassist)
    testImplementation(testFixtures(libs.viaduct.shared.viaductschema))
    testImplementation(testFixtures(libs.viaduct.tenant.api))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.reflections)
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.guava)
    testImplementation(libs.jackson.annotations)
    testImplementation(libs.slf4j.api)
    testImplementation(libs.kotest.property.jvm)

    testImplementation(libs.viaduct.shared.graphql)

    /** Codegen classpath for test-classdiff worker isolation **/
    viaductCodegenClasspath(libs.viaduct.tenant.codegen)

    jmh(libs.jmh.annotation.processor)
    jmhAnnotationProcessor(libs.jmh.annotation.processor)
    jmhApi(libs.jmh.core)

    jmhImplementation(testFixtures(libs.viaduct.shared.viaductschema))
    jmhImplementation(libs.graphql.java)
    jmhImplementation(libs.guava)
}

tasks.test {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}
