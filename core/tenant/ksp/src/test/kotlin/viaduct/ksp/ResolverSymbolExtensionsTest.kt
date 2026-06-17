package viaduct.ksp

import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolverSymbolExtensionsTest {
    @Nested
    inner class ExtractTypeNameFromBaseClass {
        private fun createMockDeclarationWithSuperTypes(superTypes: Sequence<KSTypeReference>) =
            mockk<KSClassDeclaration> {
                every { this@mockk.superTypes } returns superTypes
                every { qualifiedName?.asString() } returns "com.example.TestResolver"
            }

        @Test
        fun `throws when declaration has no super types`() {
            val mockDeclaration = createMockDeclarationWithSuperTypes(emptySequence())

            val exception = assertThrows<IllegalStateException> {
                mockDeclaration.extractTypeNameFromBaseClass()
            }
            assertTrue(exception.message?.contains("has no base class") == true)
        }

        @Test
        fun `throws when base class is not a KSClassDeclaration`() {
            val mockNonClassDeclaration = mockk<KSDeclaration>(relaxed = true)
            val mockTypeRef = mockk<KSTypeReference> {
                every { resolve() } returns mockk {
                    every { declaration } returns mockNonClassDeclaration
                }
            }
            val mockDeclaration = createMockDeclarationWithSuperTypes(sequenceOf(mockTypeRef))

            val exception = assertThrows<IllegalStateException> {
                mockDeclaration.extractTypeNameFromBaseClass()
            }
            assertTrue(exception.message?.contains("has no base class") == true)
        }

        @Test
        fun `throws when ResolverFor annotation not present`() {
            val mockBaseClass = mockk<KSClassDeclaration> {
                every { annotations } returns emptySequence()
                every { qualifiedName?.asString() } returns "com.example.BaseResolver"
            }
            val mockTypeRef = mockk<KSTypeReference> {
                every { resolve() } returns mockk<KSType> {
                    every { declaration } returns mockBaseClass
                }
            }
            val mockDeclaration = createMockDeclarationWithSuperTypes(sequenceOf(mockTypeRef))

            val exception = assertThrows<IllegalStateException> {
                mockDeclaration.extractTypeNameFromBaseClass()
            }
            assertTrue(exception.message?.contains("without a @ResolverFor annotation") == true)
        }

        @Test
        fun `throws when typeName argument not found`() {
            val mockAnnotation = mockk<KSAnnotation> {
                every { shortName.asString() } returns "ResolverFor"
                every { arguments } returns emptyList()
            }
            val mockBaseClass = mockk<KSClassDeclaration> {
                every { annotations } returns sequenceOf(mockAnnotation)
                every { qualifiedName?.asString() } returns "com.example.BaseResolver"
            }
            val mockTypeRef = mockk<KSTypeReference> {
                every { resolve() } returns mockk<KSType> {
                    every { declaration } returns mockBaseClass
                }
            }
            val mockDeclaration = createMockDeclarationWithSuperTypes(sequenceOf(mockTypeRef))

            val exception = assertThrows<IllegalStateException> {
                mockDeclaration.extractTypeNameFromBaseClass()
            }
            assertTrue(exception.message?.contains("missing the typeName argument") == true)
        }

        @Test
        fun `extracts typeName from ResolverFor annotation successfully`() {
            val mockTypeNameArg = mockk<KSValueArgument> {
                every { name?.asString() } returns "typeName"
                every { value } returns "User"
            }
            val mockAnnotation = mockk<KSAnnotation> {
                every { shortName.asString() } returns "ResolverFor"
                every { arguments } returns listOf(mockTypeNameArg)
            }
            val mockBaseClass = mockk<KSClassDeclaration> {
                every { annotations } returns sequenceOf(mockAnnotation)
            }
            val mockTypeRef = mockk<KSTypeReference> {
                every { resolve() } returns mockk<KSType> {
                    every { declaration } returns mockBaseClass
                }
            }
            val mockDeclaration = createMockDeclarationWithSuperTypes(sequenceOf(mockTypeRef))

            val result = mockDeclaration.extractTypeNameFromBaseClass()

            assertEquals("User", result)
        }
    }

    @Nested
    inner class ConvertToFullFragment {
        @Test
        fun `returns longhand fragment unchanged`() {
            val longhand = """
                fragment UserFragment on User {
                    id
                    name
                }
            """.trimIndent()

            val result = convertToFullFragment(longhand, "User")

            assertEquals(longhand, result)
        }

        @Test
        fun `converts shorthand to longhand`() {
            val result = convertToFullFragment("id name", "User")

            val expected = """
                fragment Main on User {
                    id name
                }
            """.trimIndent()
            assertEquals(expected, result)
        }

        @Test
        fun `throws if shorthand contains directive`() {
            assertThrows<IllegalArgumentException> {
                convertToFullFragment("id @skip(if: true)", "User")
            }
        }

        @Test
        fun `handles fragment with leading whitespace`() {
            val longhand = "  fragment UserFragment on User { id }"

            val result = convertToFullFragment(longhand, "User")

            assertEquals("fragment UserFragment on User { id }", result)
        }
    }
}
