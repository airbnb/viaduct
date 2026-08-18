plugins {
    `kotlin-dsl`
    `java-test-fixtures`
    jacoco
    id("build-logic.conventions")
    alias(libs.plugins.detekt)
}

detekt {
    source.setFrom("src/main/kotlin", "src/test/kotlin")
    config.setFrom(
        layout.projectDirectory.dir("../../..").file("detekt.yml"),
        layout.projectDirectory.dir("../../..").file("detekt-viaduct.yml"),
    )
}

dependencies {
    detektPlugins(project(":build-common"))
    implementation(project(":build-common"))
    implementation(libs.asm)
    implementation(libs.ksp.gradle.plugin)

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)

    testFixturesImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(libs.kotest.assertions.core.jvm)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}
