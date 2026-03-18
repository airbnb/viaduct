package viaduct.gradle.scaffold

import viaduct.codegen.st.stTemplate

/**
 * StringTemplate 4 templates for scaffolding a Viaduct project.
 * Uses [stTemplate] from shared-codegen which creates templates with "main(mdl)" signature.
 */
object Templates {
    val mainKt = stTemplate(
        """
        package <mdl.packagePrefix>

        import io.ktor.server.application.Application
        import io.ktor.server.netty.EngineMain
        import <mdl.packagePrefix>.ktorplugins.configureContentNegotiation
        import <mdl.packagePrefix>.ktorplugins.configureRouting

        fun main(args: Array\<String>) {
            EngineMain.main(args)
        }

        fun Application.module() {
            configureContentNegotiation()
            configureRouting()
        }
        """
    )

    val contentNegotiationKt = stTemplate(
        """
        package <mdl.packagePrefix>.ktorplugins

        import com.fasterxml.jackson.databind.SerializationFeature
        import io.ktor.serialization.jackson.jackson
        import io.ktor.server.application.Application
        import io.ktor.server.application.install
        import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

        fun Application.configureContentNegotiation() {
            install(ContentNegotiation) {
                jackson {
                    enable(SerializationFeature.INDENT_OUTPUT)
                }
            }
        }
        """
    )

    val routingKt = stTemplate(
        """
        package <mdl.packagePrefix>.ktorplugins

        import io.ktor.http.ContentType
        import io.ktor.http.HttpStatusCode
        import io.ktor.server.application.Application
        import io.ktor.server.application.call
        import io.ktor.server.request.receive
        import io.ktor.server.response.respond
        import io.ktor.server.response.respondText
        import io.ktor.server.routing.get
        import io.ktor.server.routing.post
        import io.ktor.server.routing.routing
        import kotlinx.coroutines.future.await
        import viaduct.service.BasicViaductFactory
        import viaduct.service.TenantRegistrationInfo
        import viaduct.service.api.ExecutionInput

        fun Application.configureRouting() {
            val viaduct = BasicViaductFactory.create(
                tenantRegistrationInfo = TenantRegistrationInfo(
                    tenantPackagePrefix = "<mdl.packagePrefix>"
                )
            )

            routing {
                get("/health") {
                    call.respondText("OK")
                }

                post("/graphql") {
                    @Suppress("UNCHECKED_CAST")
                    val request = call.receive\<Map\<String, Any?\>>() as Map\<String, Any>

                    val query = request["query"] as? String
                    if (query == null) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("errors" to listOf(mapOf("message" to "Query parameter is required")))
                        )
                        return@post
                    }

                    @Suppress("UNCHECKED_CAST")
                    val executionInput = ExecutionInput.create(
                        operationText = query,
                        variables = (request["variables"] as? Map\<String, Any>) ?: emptyMap(),
                    )

                    val result = viaduct.executeAsync(executionInput).await()
                    val statusCode = if (result.errors.isNotEmpty()) HttpStatusCode.BadRequest else HttpStatusCode.OK
                    call.respond(statusCode, result.toSpecification())
                }

                get("/graphiql") {
                    call.respondText(graphiqlHtml(), ContentType.Text.Html)
                }
            }
        }

        private fun graphiqlHtml(): String = ""${'"'}
        \<!DOCTYPE html>
        \<html>
        \<head>
            \<title>GraphiQL\</title>
            \<link href="https://unpkg.com/graphiql/graphiql.min.css" rel="stylesheet" />
        \</head>
        \<body style="margin: 0;">
            \<div id="graphiql" style="height: 100vh;">\</div>
            \<script crossorigin src="https://unpkg.com/react/umd/react.production.min.js">\</script>
            \<script crossorigin src="https://unpkg.com/react-dom/umd/react-dom.production.min.js">\</script>
            \<script crossorigin src="https://unpkg.com/graphiql/graphiql.min.js">\</script>
            \<script>
                const fetcher = GraphiQL.createFetcher({ url: '/graphql' });
                ReactDOM.render(
                    React.createElement(GraphiQL, { fetcher: fetcher }),
                    document.getElementById('graphiql'),
                );
            \</script>
        \</body>
        \</html>
        ""${'"'}.trimIndent()
        """
    )

    val schemaGraphqls = stTemplate(
        """
        # GraphQL Schema for <mdl.projectName>
        # Add your types and queries here

        extend type Query {
            greeting: String! @resolver
        }
        """
    )

    val greetingResolverKt = stTemplate(
        """
        package <mdl.packagePrefix>.resolvers

        import <mdl.packagePrefix>.resolvers.resolverbases.QueryResolvers
        import viaduct.api.Resolver

        @Resolver
        class GreetingResolver : QueryResolvers.Greeting() {
            override suspend fun resolve(ctx: Context): String {
                return "Hello from Viaduct!"
            }
        }
        """
    )

    val applicationConf = stTemplate(
        """
        ktor {
            deployment {
                port = 8080
                host = "0.0.0.0"
            }
            application {
                modules = [ <mdl.packagePrefix>.MainKt.module ]
            }
        }
        """
    )

    val buildGradleKts = stTemplate(
        """
        plugins {
            kotlin("jvm") version "1.9.22"
            id("io.ktor.plugin") version "<mdl.ktorVersion>"
            id("com.airbnb.viaduct.application-gradle-plugin") version "<mdl.viaductVersion>"
            id("com.airbnb.viaduct.module-gradle-plugin") version "<mdl.viaductVersion>"
        }

        group = "<mdl.packagePrefix>"
        version = "0.0.1"

        application {
            mainClass.set("<mdl.packagePrefix>.MainKt")
        }

        viaductApplication {
            modulePackagePrefix.set("<mdl.packagePrefix>")
        }

        viaductModule {
            modulePackageSuffix.set("resolvers")
        }

        repositories {
            mavenCentral()
        }

        dependencies {
            // Ktor
            implementation("io.ktor:ktor-server-core:<mdl.ktorVersion>")
            implementation("io.ktor:ktor-server-netty:<mdl.ktorVersion>")
            implementation("io.ktor:ktor-server-content-negotiation:<mdl.ktorVersion>")
            implementation("io.ktor:ktor-serialization-jackson:<mdl.ktorVersion>")

            // Coroutines
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

            // Jackson
            implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

            // Viaduct
            implementation("com.airbnb.viaduct:api:<mdl.viaductVersion>")
            implementation("com.airbnb.viaduct:runtime:<mdl.viaductVersion>")

            // Logging
            implementation("ch.qos.logback:logback-classic:1.4.14")

            // Testing
            testImplementation("io.ktor:ktor-server-tests:<mdl.ktorVersion>")
            testImplementation("org.jetbrains.kotlin:kotlin-test")
            testImplementation("com.airbnb.viaduct:test-fixtures:<mdl.viaductVersion>")
        }
        """
    )

    val settingsGradleKts = stTemplate(
        """
        pluginManagement {
            repositories {
                mavenCentral()
                gradlePluginPortal()
            }
        }

        dependencyResolutionManagement {
            repositories {
                mavenCentral()
            }
        }

        rootProject.name = "<mdl.projectName>"
        """
    )

    val gradleProperties = stTemplate(
        """
        kotlin.code.style=official
        org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
        """
    )

    // Note: AGENTS.md, .gitignore, and .viaduct/agents/ are created by the
    // install script from viaduct-dev/skills repo, not by templates here.
}
