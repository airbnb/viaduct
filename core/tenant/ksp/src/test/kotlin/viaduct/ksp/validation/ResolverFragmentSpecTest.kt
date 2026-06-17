package viaduct.ksp.validation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ResolverFragmentSpecTest {
    private fun createSpec(
        packageName: String = "com.airbnb.viaduct",
        className: String = "UserResolver",
        sourceFileName: String = "UserResolver.kt",
        typeName: String = "User",
        fragmentType: ResolverFragmentType = ResolverFragmentType.OBJECT,
        fragment: String = "fragment _ on User { id name }"
    ): ResolverFragmentSpec =
        ResolverFragmentSpec(
            fragment = fragment,
            metadata = Metadata(
                packageName = packageName,
                className = className,
                sourceFileName = sourceFileName,
                typeName = typeName,
                fragmentType = fragmentType,
            ),
        )

    @Nested
    inner class SerializationRoundTrip {
        @Test
        fun `round trip serialization preserves all metadata`() {
            val spec = createSpec(
                fragmentType = ResolverFragmentType.OBJECT,
                fragment = "fragment _ on User { id name }"
            )

            val serialized = spec.toString()
            val reparsed = ResolverFragmentSpec.fromString(serialized)

            assertEquals(spec.metadata.fullClassName, reparsed.metadata.fullClassName)
            assertEquals(spec.metadata.sourceFileName, reparsed.metadata.sourceFileName)
            assertEquals(spec.metadata.typeName, reparsed.metadata.typeName)
            assertEquals(spec.metadata.fragmentType, reparsed.metadata.fragmentType)
            assertEquals(spec.fragment, reparsed.fragment)
        }

        @Test
        fun `serialization format uses METADATA tags`() {
            val spec = createSpec()
            val serialized = spec.toString()

            assertTrue(serialized.startsWith("# <METADATA>"))
            assertTrue(serialized.contains("</METADATA>"))
            assertTrue(serialized.contains("\"className\":\"UserResolver\""))
            assertTrue(serialized.contains("\"typeName\":\"User\""))
            assertTrue(serialized.contains("\"fragmentType\":\"OBJECT\""))
        }
    }

    @Nested
    inner class FromString {
        @Test
        fun `parses valid metadata block`() {
            val input = """
                # <METADATA>{"packageName":"com.airbnb","className":"UserResolver","sourceFileName":"UserResolver.kt","typeName":"User","fragmentType":"OBJECT"}</METADATA>
                fragment _ on User { id }
            """.trimIndent()

            val spec = ResolverFragmentSpec.fromString(input)

            assertEquals("com.airbnb.UserResolver", spec.metadata.fullClassName)
            assertEquals("UserResolver.kt", spec.metadata.sourceFileName)
            assertEquals("User", spec.metadata.typeName)
            assertEquals(ResolverFragmentType.OBJECT, spec.metadata.fragmentType)
            assertEquals("fragment _ on User { id }", spec.fragment)
        }

        @Test
        fun `throws when metadata block is missing`() {
            val input = "fragment _ on User { id }"

            val exception = assertThrows<IllegalArgumentException> {
                ResolverFragmentSpec.fromString(input)
            }

            assertTrue(exception.message!!.contains("No metadata block found"))
        }

        @Test
        fun `throws when fragment content is blank`() {
            val input = """
                # <METADATA>{"packageName":"com.airbnb","className":"UserResolver","sourceFileName":"UserResolver.kt","typeName":"User","fragmentType":"OBJECT"}</METADATA>
            """.trimIndent()

            val exception = assertThrows<IllegalArgumentException> {
                ResolverFragmentSpec.fromString(input)
            }

            assertTrue(exception.message!!.contains("Fragment content cannot be blank"))
        }

        @Test
        fun `parses multiline fragment content`() {
            val input = """
                # <METADATA>{"packageName":"com.airbnb","className":"UserResolver","sourceFileName":"UserResolver.kt","typeName":"User","fragmentType":"OBJECT"}</METADATA>
                fragment _ on User {
                    id
                    name
                    email
                }
            """.trimIndent()

            val spec = ResolverFragmentSpec.fromString(input)

            assertTrue(spec.fragment.contains("id"))
            assertTrue(spec.fragment.contains("name"))
            assertTrue(spec.fragment.contains("email"))
        }
    }

    @Nested
    inner class DerivedProperties {
        @Test
        fun `fullClassName combines packageName and className`() {
            val spec = createSpec(
                packageName = "com.airbnb.viaduct.resolvers",
                className = "UserResolver"
            )

            assertEquals("com.airbnb.viaduct.resolvers.UserResolver", spec.metadata.fullClassName)
        }

        @Test
        fun `fullClassName handles empty packageName`() {
            val spec = createSpec(
                packageName = "",
                className = "UserResolver"
            )

            assertEquals("UserResolver", spec.metadata.fullClassName)
        }
    }
}
