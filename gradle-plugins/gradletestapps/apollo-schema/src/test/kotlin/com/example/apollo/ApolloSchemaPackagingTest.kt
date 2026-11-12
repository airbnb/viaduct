package com.example.apollo

import java.nio.file.Path
import java.util.jar.JarFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import viaduct.engine.api.GraphQLBuildError
import viaduct.engine.runtime.tenantloading.MissingResolversException
import viaduct.service.BasicViaductFactory

class ApolloSchemaPackagingTest {
    @Test
    fun `schema generation alone fails startup for the missing protocol executors`() {
        val failure = assertThrows<GraphQLBuildError> { BasicViaductFactory.create() }
        val cause = assertInstanceOf(MissingResolversException::class.java, failure.cause)
        assertEquals(setOf("Query._service", "Query._entities", "_Service.sdl"), cause.missingFieldResolvers.toSet())
        assertTrue(cause.missingNodeResolvers.isEmpty())
    }

    @Test
    fun `validated additions are packaged and indexed without changing central SDL or GRTs`() {
        val buildDir = Path.of(System.getProperty("projectBuildDir"))
        val relative = "schema/plugin/com.airbnb.viaduct.apollo-schema/generated/com.airbnb.viaduct.apollo-schema/schema.graphqls"
        val central = buildDir.resolve("viaduct/centralSchema")
        val final = buildDir.resolve("viaduct/finalSchema")
        assertFalse(central.resolve(relative).toFile().exists())
        val generated = final.resolve(relative).toFile().readText()
        assertTrue(generated.contains("= Product | User"), generated)
        assertFalse(generated.contains("Reference"), generated)
        val packaged = javaClass.getResource("/viaduct/centralSchema/$relative")
        assertNotNull(packaged)
        assertEquals(generated, packaged!!.readText())
        val index = javaClass.getResource("/META-INF/viaduct/indexes/schema-v1.index")!!.readText()
        assertTrue(index.contains("viaduct/centralSchema/$relative"), index)
        central.toFile().walkTopDown().filter { it.isFile }.forEach { source ->
            assertEquals(source.readText(), final.resolve(source.relativeTo(central.toFile()).path).toFile().readText())
        }
        JarFile(System.getProperty("centralGrts")).use { jar ->
            val classes = jar.entries().asSequence().map { it.name }.toList()
            assertTrue(classes.any { it.endsWith("/Product.class") }, classes.toString())
            assertFalse(classes.any { it.endsWith("/_Entity.class") || it.endsWith("/_Service.class") || it.endsWith("/_Any.class") }, classes.toString())
        }
    }
}
