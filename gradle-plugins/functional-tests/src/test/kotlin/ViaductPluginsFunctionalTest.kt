import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ViaductPluginsFunctionalTest : AbstractViaductPluginsFunctionalTest() {

    // TODO: do we want to donwload test projects from GitHub?

    @ParameterizedTest(name = "gradle version: {0}")
    @MethodSource("getGradleTestVersions")
    fun `simple project`(gradleVersion: String) {
        withDefaultSettingsFile()

        withBuildFile("""
            plugins {
                kotlin("jvm") version "1.9.24"
                id("com.airbnb.viaduct.application-gradle-plugin") version("$viaductVersion")
                id("com.airbnb.viaduct.module-gradle-plugin") version("$viaductVersion")
                application
            }

            viaductApplication {
                modulePackagePrefix.set("com.example.viadapp")
            }

            viaductModule {
                modulePackageSuffix.set("resolvers")
            }

            dependencies {
                implementation("ch.qos.logback:logback-classic:1.3.7")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.5.2")

                implementation("com.fasterxml.jackson.core:jackson-databind:2.9.10")
            }

            application {
                mainClass.set("com.example.viadapp.ViaductApplicationKt")
            }
        """)

        withKotlinSourceFile("com/example/resolvers/HelloWorldResolvers.kt", """
            package com.example.viadapp.resolvers

            import com.example.viadapp.resolvers.resolverbases.QueryResolvers
            import viaduct.api.Resolver
            
            @Resolver
            class GreetingResolver : QueryResolvers.Greeting() {
                override suspend fun resolve(ctx: Context): String {
                    return "Hello, World!"
                }
            }
            
            @Resolver
            class AuthorResolver : QueryResolvers.Author() {
                override suspend fun resolve(ctx: Context): String {
                    return "Brian Kernighan"
                }
            }
        """)

        withKotlinSourceFile("com/example/viadapp/ViaductApplication.kt", """
            @file:Suppress("ForbiddenImport")

            package com.example.viadapp
            
            import ch.qos.logback.classic.Level
            import ch.qos.logback.classic.Logger
            import com.fasterxml.jackson.databind.ObjectMapper
            import kotlinx.coroutines.coroutineScope
            import kotlinx.coroutines.runBlocking
            import org.slf4j.LoggerFactory
            import viaduct.engine.runtime.execution.withThreadLocalCoroutineContext
            import viaduct.service.BasicViaductFactory
            import viaduct.service.TenantRegistrationInfo
            import viaduct.service.api.ExecutionInput
            
            fun main(argv: Array<String>) {
                val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
                rootLogger.level = Level.ERROR
            
                // Create a Viaduct engine using the BasicViaductFactory
                val viaduct = BasicViaductFactory.create(
                    tenantRegistrationInfo = TenantRegistrationInfo(
                        tenantPackagePrefix = "com.example.viadapp"
                    )
                )
            
                // Create an execution input
                val executionInput = ExecutionInput.create(
                    operationText = (
                        argv.getOrNull(0)
                            ?: ""${'"'}
                                 query {
                                     greeting
                                 }
                            ""${'"'}.trimIndent()
                    ),
                    variables = emptyMap(),
                )
            
                // Run the query
                val result = runBlocking {
                    // Note to reviewers: in the future the next two scope functions
                    // will go away
                    coroutineScope {
                        withThreadLocalCoroutineContext {
                            viaduct.execute(executionInput)
                        }
                    }
                }
            
                // [toSpecification] converts to JSON as described in the GraphQL
                // specification.
                val mapper = ObjectMapper().writerWithDefaultPrettyPrinter()
                println(
                    mapper.writeValueAsString(result.toSpecification())
                )
            }

        """)

        withSchemaFile("""
            extend type Query {
              greeting: String @resolver
              author: String @resolver
            }
        """)

        val result = runner()
            .withGradleVersion(gradleVersion)
            .withArguments("run", "--stacktrace")
            .build()
        println(result.output)
    }

}