package com.example.execution.twoproject.resolvers

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.api.testing.ResolverTestBase
import viaduct.apiannotations.ExperimentalApi

@OptIn(ExperimentalApi::class)
class GreetingResolverTest : ResolverTestBase() {
    @Test
    fun resolverLoadsApplicationSchemaWithoutApplicationClasses() =
        runTest {
            assertThrows(ClassNotFoundException::class.java) {
                javaClass.classLoader.loadClass("com.example.execution.twoproject.GeneratedGrtConsumer")
            }
            assertEquals("hello from two-project", runFieldResolver(GreetingResolver()))
        }
}
