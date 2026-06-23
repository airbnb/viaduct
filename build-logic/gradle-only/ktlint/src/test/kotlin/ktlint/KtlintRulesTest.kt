package ktlint

import com.pinterest.ktlint.rule.engine.core.api.Rule
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.string.shouldContain
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
import org.jetbrains.kotlin.com.intellij.psi.impl.PsiFileFactoryImpl
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.psi.KtFile
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KtlintRulesTest {
    private lateinit var env: KotlinCoreEnvironment
    private lateinit var envDisposer: org.jetbrains.kotlin.com.intellij.openapi.Disposable
    private val violations = mutableListOf<Pair<Int, String>>()
    private val emit: (Int, String, Boolean) -> Unit = { offset, msg, _ -> violations.add(offset to msg) }

    @BeforeAll
    fun setupEnv() {
        envDisposer = Disposer.newDisposable()
        val config = CompilerConfiguration().also {
            it.put(
                CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY,
                PrintingMessageCollector(System.err, MessageRenderer.PLAIN_FULL_PATHS, false),
            )
        }
        env = KotlinCoreEnvironment.createForProduction(
            envDisposer,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES,
        )
    }

    @AfterAll
    fun tearDownEnv() {
        Disposer.dispose(envDisposer)
    }

    @BeforeEach
    fun setup() {
        violations.clear()
    }

    private fun lint(
        fileName: String,
        code: String,
        rule: Rule
    ) {
        val factory = PsiFileFactory.getInstance(env.project) as PsiFileFactoryImpl
        val file = factory.createFileFromText(fileName, KotlinFileType.INSTANCE, code) as KtFile
        val astNode = file.node

        rule.beforeVisitChildNodes(astNode, false, emit)

        fun walk(node: ASTNode) {
            rule.beforeVisitChildNodes(node, false, emit)
            node.getChildren(null).forEach { walk(it) }
        }
        walk(astNode)
    }

    // --- NoPrintlnInGradleRule ---

    @Test
    fun `NoPrintln - flags println in gradle kts`() {
        lint("test.gradle.kts", """println("hello")""", NoPrintlnInGradleRule())
        violations.shouldNotBeEmpty()
        violations.first().second.shouldContain("logger")
    }

    @Test
    fun `NoPrintln - ignores println in regular kt file`() {
        lint("Test.kt", """fun foo() { println("hello") }""", NoPrintlnInGradleRule())
        violations.shouldBeEmpty()
    }

    // --- NoStringDependenciesInGradleRule ---

    @Test
    fun `NoStringDependencies - flags raw string coordinate`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation("com.example:foo:1.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
        violations.first().second.shouldContain("raw string literals")
    }

    @Test
    fun `NoStringDependencies - flags named group-name-version params`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                api(group = "com.example", name = "foo", version = "1.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
        violations.first().second.shouldContain("group/name/version")
    }

    @Test
    fun `NoStringDependencies - allows libs catalog alias`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(libs.foo.bar)
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - allows project reference`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(project(":some-module"))
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - ignores raw string outside dependencies block`() {
        lint(
            "build.gradle.kts",
            """val x = "com.example:foo:1.0"""",
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - flags raw string inside platform wrapper`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(platform("com.example:bom:1.0"))
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
        violations.first().second.shouldContain("raw string literals")
    }

    @Test
    fun `NoStringDependencies - flags raw string inside enforcedPlatform wrapper`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(enforcedPlatform("com.example:bom:1.0"))
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
        violations.first().second.shouldContain("raw string literals")
    }

    @Test
    fun `NoStringDependencies - allows platform with libs catalog alias`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(platform(libs.some.bom))
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - ignores violations in regular kt files`() {
        lint(
            "Build.kt",
            """
            dependencies {
                implementation("com.example:foo:1.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    // --- NoPrintlnInGradleRule additional cases ---

    @Test
    fun `NoPrintln - flags println nested inside configure block`() {
        lint(
            "build.gradle.kts",
            """
            tasks.named("test") {
                println("running tests")
            }
            """.trimIndent(),
            NoPrintlnInGradleRule(),
        )
        violations.shouldNotBeEmpty()
        violations.first().second.shouldContain("logger")
    }

    @Test
    fun `NoPrintln - flags multiple println calls`() {
        lint(
            "build.gradle.kts",
            """
            println("first")
            println("second")
            """.trimIndent(),
            NoPrintlnInGradleRule(),
        )
        violations.shouldHaveSize(2)
    }

    @Test
    fun `NoPrintln - ignores non-println calls in gradle kts`() {
        lint(
            "build.gradle.kts",
            """logger.lifecycle("hello")""",
            NoPrintlnInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    // --- NoStringDependenciesInGradleRule additional cases ---

    @Test
    fun `NoStringDependencies - flags dynamic suffix configuration (debugImplementation)`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                debugImplementation("com.example:debug-tools:1.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
    }

    @Test
    fun `NoStringDependencies - flags dynamic suffix configuration (releaseApi)`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                releaseApi("com.example:lib:2.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
    }

    @Test
    fun `NoStringDependencies - allows testFixtures project reference`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(testFixtures(project(":some-module")))
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - does not flag string with interpolation`() {
        lint(
            "build.gradle.kts",
            """
            val version = "1.0"
            dependencies {
                implementation("com.example:foo:${'$'}version")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - allows enforcedPlatform with libs catalog alias`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(enforcedPlatform(libs.some.bom))
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - does not flag unknown configuration name`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                somethingElse("com.example:foo:1.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `NoStringDependencies - flags compileOnly raw string`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                compileOnly("com.example:annotations:1.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
    }

    @Test
    fun `NoStringDependencies - flags runtimeOnly raw string`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                runtimeOnly("com.example:runtime:1.0")
            }
            """.trimIndent(),
            NoStringDependenciesInGradleRule(),
        )
        violations.shouldNotBeEmpty()
    }

    // --- CoroutinesDependencyUsageRule ---

    @Test
    fun `CoroutinesDependencyUsage - flags coroutines test in implementation dependencies`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
            """.trimIndent(),
            CoroutinesDependencyUsageRule(),
        )
        violations.shouldHaveSize(1)
        violations.first().second.shouldContain("test-scoped configurations")
    }

    @Test
    fun `CoroutinesDependencyUsage - allows coroutines test in test implementation dependencies`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(libs.kotlinx.coroutines.test)
            }
            """.trimIndent(),
            CoroutinesDependencyUsageRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `CoroutinesDependencyUsage - allows coroutines test in test fixtures dependencies`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testFixturesImplementation(libs.kotlinx.coroutines.test)
            }
            """.trimIndent(),
            CoroutinesDependencyUsageRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `CoroutinesDependencyUsage - ignores regular kt files`() {
        lint(
            "Build.kt",
            """
            dependencies {
                implementation(libs.kotlinx.coroutines.test)
            }
            """.trimIndent(),
            CoroutinesDependencyUsageRule(),
        )
        violations.shouldBeEmpty()
    }

    // --- InvalidDependencyRule ---

    @Test
    fun `InvalidDependency - flags strikt-core`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(libs.strikt.core)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.shouldHaveSize(1)
        violations.first().second.shouldContain("libs.strikt.core")
    }

    @Test
    fun `InvalidDependency - flags kotlin-test`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(libs.kotlin.test)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.shouldHaveSize(1)
        violations.first().second.shouldContain("libs.kotlin.test")
    }

    @Test
    fun `InvalidDependency - flags guava-testlib`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(libs.guava.testlib)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.shouldHaveSize(1)
        violations.first().second.shouldContain("libs.guava.testlib")
    }

    @Test
    fun `InvalidDependency - error message references testing-guidance`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(libs.strikt.core)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.first().second.shouldContain("testing-guidance.md")
    }

    @Test
    fun `InvalidDependency - allows kotest`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(libs.kotest.assertions.core.jvm)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `InvalidDependency - allows junit`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                testImplementation(libs.junit)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `InvalidDependency - ignores regular kt files`() {
        lint(
            "Build.kt",
            """
            dependencies {
                testImplementation(libs.strikt.core)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.shouldBeEmpty()
    }

    @Test
    fun `InvalidDependency - flags forbidden lib in implementation scope`() {
        lint(
            "build.gradle.kts",
            """
            dependencies {
                implementation(libs.kotlin.test)
            }
            """.trimIndent(),
            InvalidDependencyRule(),
        )
        violations.shouldHaveSize(1)
    }
}
