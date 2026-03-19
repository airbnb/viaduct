package viaduct.gradle.javafeature

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ViaductJavaFeatureAppPluginTest {
    // --- Java ---

    @Test
    fun `java class with extends returns superclass name`() {
        val content = """
            package com.example;
            public class FooTest extends ObjectContractTest {
            }
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isEqualTo("ObjectContractTest")
    }

    // --- Kotlin: simple inheritance (the bug case) ---

    @Test
    fun `kotlin class with direct superclass and no constructor params`() {
        val content = """
            package com.example
            class FooTest : ObjectContractTest()
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isEqualTo("ObjectContractTest")
    }

    @Test
    fun `kotlin abstract class with direct superclass and no constructor params`() {
        val content = """
            package com.example
            abstract class FooTest : ObjectContractTest()
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isEqualTo("ObjectContractTest")
    }

    @Test
    fun `kotlin open class with direct superclass and no constructor params`() {
        val content = """
            package com.example
            open class FooTest : ObjectContractTest()
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isEqualTo("ObjectContractTest")
    }

    @Test
    fun `kotlin class implementing interface without parens`() {
        val content = """
            package com.example
            class FooTest : SomeInterface
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isEqualTo("SomeInterface")
    }

    // --- Kotlin: inheritance with constructor params ---

    @Test
    fun `kotlin class with constructor params and superclass`() {
        val content = """
            package com.example
            class FooTest(val helper: TestHelper) : ObjectContractTest()
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isEqualTo("ObjectContractTest")
    }

    // --- no superclass ---

    @Test
    fun `kotlin class with no superclass returns null`() {
        val content = """
            package com.example
            class FooTest
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isNull()
    }

    @Test
    fun `java class with no extends returns null`() {
        val content = """
            package com.example;
            public class FooTest {
            }
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isNull()
    }

    // --- false-positive prevention ---

    @Test
    fun `file-level annotation is not mistaken for a class declaration`() {
        // @file:Suppress("...") should not trigger a match
        val content = """
            @file:Suppress("UNCHECKED_CAST")
            package com.example
            class FooTest
        """.trimIndent()
        assertThat(ViaductJavaFeatureAppPlugin.extractSuperclassName(content)).isNull()
    }
}
