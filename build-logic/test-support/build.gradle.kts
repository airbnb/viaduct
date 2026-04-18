plugins {
    `kotlin-dsl`
    `java-test-fixtures`
}

dependencies {
    implementation(project(":build-common"))
    implementation(project(":build-shared"))
    implementation(libs.asm)
    implementation(libs.ksp.gradle.plugin)

    // Do NOT leak the Kotlin Gradle Plugin at runtime
    compileOnly(libs.kotlin.gradle.plugin)

    testFixturesImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testImplementation(libs.assertj.core)
    testImplementation(libs.junit)
    testRuntimeOnly(libs.junit.engine)
}

tasks.test {
    useJUnitPlatform()
}
