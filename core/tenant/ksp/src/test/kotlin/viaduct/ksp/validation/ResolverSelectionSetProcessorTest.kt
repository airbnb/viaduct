package viaduct.ksp.validation

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSName
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeReference
import com.google.devtools.ksp.symbol.KSValueArgument
import graphql.validation.Validator
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

private const val TEST_FRAGMENTS_OUTPUT_FILE = "resolver-extracted-fragments"

class ResolverSelectionSetProcessorTest {
    @Nested
    inner class GetResolverSelectionSetSpecs {
        private val processor = run {
            val mockEnvironment = mockk<SymbolProcessorEnvironment>(relaxed = true)
            val mockValidator = mockk<Validator>(relaxed = true)
            ResolverSelectionSetProcessor(mockEnvironment, mockValidator)
        }

        private fun createMockKSName(value: String) =
            mockk<KSName> {
                every { asString() } returns value
            }

        private fun createMockValueArgument(
            argName: String,
            value: Any
        ) = mockk<KSValueArgument> {
            every { name } returns createMockKSName(argName)
            every { this@mockk.value } returns value
        }

        private fun createMockResolverAnnotation(arguments: List<KSValueArgument>) =
            mockk<KSAnnotation> {
                every { shortName.asString() } returns "Resolver"
                every { this@mockk.arguments } returns arguments
            }

        private fun createMockClassDeclaration(
            packageName: String,
            className: String,
            annotations: Sequence<KSAnnotation>,
            typeName: String = "User",
            nestedDeclarations: Sequence<KSClassDeclaration> = emptySequence()
        ): KSClassDeclaration {
            val mockTypeNameArg = mockk<KSValueArgument> {
                every { name?.asString() } returns "typeName"
                every { value } returns typeName
            }

            val mockResolverForAnnotation = mockk<KSAnnotation> {
                every { shortName.asString() } returns "ResolverFor"
                every { arguments } returns listOf(mockTypeNameArg)
            }

            val mockBaseClass = mockk<KSClassDeclaration> {
                every { this@mockk.annotations } returns sequenceOf(mockResolverForAnnotation)
            }

            val mockTypeRef = mockk<KSTypeReference> {
                every { resolve() } returns mockk<KSType> {
                    every { declaration } returns mockBaseClass
                }
            }

            return mockk {
                every { this@mockk.packageName } returns createMockKSName(packageName)
                every { simpleName } returns createMockKSName(className)
                every { qualifiedName } returns createMockKSName("$packageName.$className")
                every { this@mockk.annotations } returns annotations
                every { superTypes } returns sequenceOf(mockTypeRef)
                every { declarations } returns nestedDeclarations
            }
        }

        @Test
        fun `returns empty list when no Resolver annotation present`() {
            val mockDeclaration = mockk<KSClassDeclaration> {
                every { annotations } returns emptySequence()
                every { declarations } returns emptySequence()
            }

            val result = with(processor) {
                mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
            }

            assertTrue(result == null)
        }

        @Test
        fun `throws IllegalArgumentException when multiple Resolver annotations present`() {
            val mockAnnotation1 = createMockResolverAnnotation(emptyList())
            val mockAnnotation2 = createMockResolverAnnotation(emptyList())

            val mockDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "TestResolver",
                annotations = sequenceOf(mockAnnotation1, mockAnnotation2)
            )

            assertThrows<IllegalArgumentException> {
                with(processor) {
                    mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
                }
            }
        }

        @Test
        fun `extracts variables from resolver annotation`() {
            val longhandFragment = "fragment Main on User { id }"
            val mockFragmentArg = createMockValueArgument("objectValueFragment", longhandFragment)

            val unset = VARIABLE_UNSET_STRING_VALUE
            val mockVarAnnotation = mockk<KSAnnotation> {
                every { arguments } returns listOf(
                    mockk<KSValueArgument> {
                        every { name } returns createMockKSName("name")
                        every { value } returns "myVar"
                    },
                    mockk<KSValueArgument> {
                        every { name } returns createMockKSName("fromObjectField")
                        every { value } returns "foo.bar"
                    },
                    mockk<KSValueArgument> {
                        every { name } returns createMockKSName("fromQueryField")
                        every { value } returns unset
                    },
                    mockk<KSValueArgument> {
                        every { name } returns createMockKSName("fromArgument")
                        every { value } returns unset
                    },
                )
            }
            val mockVariablesArg = createMockValueArgument("variables", listOf(mockVarAnnotation))
            val mockAnnotation = createMockResolverAnnotation(listOf(mockFragmentArg, mockVariablesArg))
            val mockDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "TestResolver",
                annotations = sequenceOf(mockAnnotation),
                typeName = "User"
            )

            val result = with(processor) {
                mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
            }

            assertEquals(1, result!!.fragments.size)
            assertEquals(longhandFragment, result.fragments[0].fragment)
            assertEquals(1, result.variables.size)
            assertEquals("myVar", result.variables[0].name)
            assertEquals("foo.bar", result.variables[0].fromObjectField)
            assertEquals(null, result.variables[0].fromQueryField)
            assertEquals(null, result.variables[0].fromArgument)
        }

        @Test
        fun `skips empty fragments`() {
            val mockArgument = createMockValueArgument("objectValueFragment", "")
            val mockAnnotation = createMockResolverAnnotation(listOf(mockArgument))
            val mockDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "TestResolver",
                annotations = sequenceOf(mockAnnotation)
            )

            val result = with(processor) {
                mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
            }

            assertTrue(result!!.fragments.isEmpty())
        }

        @Test
        fun `handles both objectValueFragment and queryValueFragment together`() {
            val objectFragment = "fragment ObjectMain on User { id }"
            val queryFragment = "fragment QueryMain on Query { user(id: \"1\") { id } }"
            val mockObjectArg = createMockValueArgument("objectValueFragment", objectFragment)
            val mockQueryArg = createMockValueArgument("queryValueFragment", queryFragment)
            val mockAnnotation = createMockResolverAnnotation(listOf(mockObjectArg, mockQueryArg))
            val mockDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "TestResolver",
                annotations = sequenceOf(mockAnnotation),
                typeName = "User"
            )

            val result = with(processor) {
                mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
            }

            assertEquals(2, result!!.fragments.size)
            assertTrue(result.fragments.any { it.metadata.fragmentType == ResolverFragmentType.OBJECT && it.fragment == objectFragment })
            assertTrue(result.fragments.any { it.metadata.fragmentType == ResolverFragmentType.QUERY && it.fragment == queryFragment })
        }

        private fun createMockVariablesNestedClass(typesString: String): KSClassDeclaration {
            val mockTypesArg = mockk<KSValueArgument> {
                every { name } returns createMockKSName("types")
                every { value } returns typesString
            }
            val mockVariablesAnnotation = mockk<KSAnnotation> {
                every { shortName.asString() } returns "Variables"
                every { arguments } returns listOf(mockTypesArg)
            }
            return mockk {
                every { annotations } returns sequenceOf(mockVariablesAnnotation)
            }
        }

        @Test
        fun `resolver with zero Variables classes passes`() {
            val longhandFragment = "fragment Main on User { id }"
            val mockFragmentArg = createMockValueArgument("objectValueFragment", longhandFragment)
            val mockAnnotation = createMockResolverAnnotation(listOf(mockFragmentArg))
            val mockDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "TestResolver",
                annotations = sequenceOf(mockAnnotation),
                nestedDeclarations = emptySequence()
            )

            val result = with(processor) {
                mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
            }

            assertTrue(result!!.variablesProviderVarNames.isEmpty())
        }

        @Test
        fun `resolver with one Variables class extracts variable names`() {
            val longhandFragment = "fragment Main on User { id }"
            val mockFragmentArg = createMockValueArgument("objectValueFragment", longhandFragment)
            val mockAnnotation = createMockResolverAnnotation(listOf(mockFragmentArg))
            val nestedVariablesClass = createMockVariablesNestedClass("viewerId: ID, listingId: String!")
            val mockDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "TestResolver",
                annotations = sequenceOf(mockAnnotation),
                nestedDeclarations = sequenceOf(nestedVariablesClass)
            )

            val result = with(processor) {
                mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
            }

            assertEquals(setOf("viewerId", "listingId"), result!!.variablesProviderVarNames)
        }

        @Test
        fun `resolver with two Variables classes throws`() {
            val longhandFragment = "fragment Main on User { id }"
            val mockFragmentArg = createMockValueArgument("objectValueFragment", longhandFragment)
            val mockAnnotation = createMockResolverAnnotation(listOf(mockFragmentArg))
            val nestedVariablesClass1 = createMockVariablesNestedClass("viewerId: ID")
            val nestedVariablesClass2 = createMockVariablesNestedClass("listingId: String!")
            val mockDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "TestResolver",
                annotations = sequenceOf(mockAnnotation),
                nestedDeclarations = sequenceOf(nestedVariablesClass1, nestedVariablesClass2)
            )

            assertThrows<IllegalArgumentException> {
                with(processor) {
                    mockDeclaration.getResolverAnnotationSpec("TestFile.kt")
                }
            }
        }
    }

    @Nested
    inner class Process {
        @TempDir
        lateinit var tempDir: File

        private fun createMockKSName(value: String) =
            mockk<KSName> {
                every { asString() } returns value
            }

        private fun createMockValueArgument(
            argName: String,
            value: Any
        ) = mockk<KSValueArgument> {
            every { name } returns createMockKSName(argName)
            every { this@mockk.value } returns value
        }

        private fun createMockResolverAnnotation(arguments: List<KSValueArgument>) =
            mockk<KSAnnotation> {
                every { shortName.asString() } returns "Resolver"
                every { this@mockk.arguments } returns arguments
            }

        private fun createMockClassDeclaration(
            packageName: String,
            className: String,
            annotations: Sequence<KSAnnotation>,
            typeName: String
        ): KSClassDeclaration {
            val mockTypeNameArg = mockk<KSValueArgument> {
                every { name?.asString() } returns "typeName"
                every { value } returns typeName
            }
            val mockResolverForAnnotation = mockk<KSAnnotation> {
                every { shortName.asString() } returns "ResolverFor"
                every { arguments } returns listOf(mockTypeNameArg)
            }
            val mockBaseClass = mockk<KSClassDeclaration> {
                every { this@mockk.annotations } returns sequenceOf(mockResolverForAnnotation)
            }
            val mockTypeRef = mockk<KSTypeReference> {
                every { resolve() } returns mockk<KSType> {
                    every { declaration } returns mockBaseClass
                }
            }
            return mockk {
                every { this@mockk.packageName } returns createMockKSName(packageName)
                every { simpleName } returns createMockKSName(className)
                every { qualifiedName } returns createMockKSName("$packageName.$className")
                every { this@mockk.annotations } returns annotations
                every { superTypes } returns sequenceOf(mockTypeRef)
                every { declarations } returns emptySequence()
            }
        }

        private fun createCompilationSchemaFile(schemaSDL: String): File {
            val wrapperContent = CompilationSchemaWrapperKtUtils.createCompilationSchemaSDLWrapperKt(schemaSDL)
            return File(tempDir, CompilationSchemaWrapperKtUtils.COMPILATION_SCHEMA_WRAPPER_KT_FILE).apply {
                writeText(wrapperContent)
            }
        }

        @Test
        fun `process extracts resolvers and generates fragments file when option is provided`() {
            val schemaSDL = """
                type Query { user(id: ID!): User photo(id: ID!): Photo }
                type User { id: ID! name: String }
                type Photo { id: ID! url: String caption: String }
            """.trimIndent()
            val schemaFile = createCompilationSchemaFile(schemaSDL)

            val outputStream = ByteArrayOutputStream()
            val mockEnvironment = mockk<SymbolProcessorEnvironment>()
            val mockCodeGenerator = mockk<CodeGenerator>()
            val mockLogger = mockk<KSPLogger>(relaxed = true)

            every { mockEnvironment.codeGenerator } returns mockCodeGenerator
            every { mockEnvironment.logger } returns mockLogger
            every { mockEnvironment.options } returns mapOf(FRAGMENTS_OUTPUT_OPTION to TEST_FRAGMENTS_OUTPUT_FILE)
            every { mockCodeGenerator.createNewFile(any(), any(), any(), any()) } returns outputStream

            val userFragment = "fragment UserMain on User { id name }"
            val mockUserFragmentArg = createMockValueArgument("objectValueFragment", userFragment)
            val mockUserAnnotation = createMockResolverAnnotation(listOf(mockUserFragmentArg))
            val mockUserClassDeclaration = createMockClassDeclaration(
                packageName = "com.example.resolver1",
                className = "UserResolver",
                annotations = sequenceOf(mockUserAnnotation),
                typeName = "User"
            )

            val photoFragment = "fragment PhotoMain on Photo { id url caption }"
            val mockPhotoFragmentArg = createMockValueArgument("objectValueFragment", photoFragment)
            val mockPhotoAnnotation = createMockResolverAnnotation(listOf(mockPhotoFragmentArg))
            val mockPhotoClassDeclaration = createMockClassDeclaration(
                packageName = "com.example.resolver2",
                className = "PhotoResolver",
                annotations = sequenceOf(mockPhotoAnnotation),
                typeName = "Photo"
            )

            val mockSchemaKSFile = mockk<KSFile> {
                every { fileName } returns CompilationSchemaWrapperKtUtils.COMPILATION_SCHEMA_WRAPPER_KT_FILE
                every { filePath } returns schemaFile.absolutePath
                every { declarations } returns emptySequence()
            }
            val mockUserResolverKSFile = mockk<KSFile> {
                every { fileName } returns "UserResolver.kt"
                every { declarations } returns sequenceOf(mockUserClassDeclaration)
            }
            val mockPhotoResolverKSFile = mockk<KSFile> {
                every { fileName } returns "PhotoResolver.kt"
                every { declarations } returns sequenceOf(mockPhotoClassDeclaration)
            }
            val mockResolver = mockk<Resolver> {
                every { getAllFiles() } returns sequenceOf(mockSchemaKSFile, mockUserResolverKSFile, mockPhotoResolverKSFile)
            }

            val processor = ResolverSelectionSetProcessor(mockEnvironment, Validator())
            val result = processor.process(mockResolver)

            assertEquals(emptyList<Any>(), result)
            verify { mockCodeGenerator.createNewFile(any(), "", TEST_FRAGMENTS_OUTPUT_FILE, "graphql") }
            verify { mockLogger.info("Generated resolver fragments GraphQL schema with 2 resolvers") }

            val generatedContent = outputStream.toString()

            // Verify UserResolver metadata and fragment
            assertTrue(generatedContent.contains("\"packageName\":\"com.example.resolver1\""))
            assertTrue(generatedContent.contains("\"className\":\"UserResolver\""))
            assertTrue(generatedContent.contains("\"sourceFileName\":\"UserResolver.kt\""))
            assertTrue(generatedContent.contains("\"typeName\":\"User\""))
            assertTrue(generatedContent.contains("fragment UserMain on User"))

            // Verify PhotoResolver metadata and fragment
            assertTrue(generatedContent.contains("\"packageName\":\"com.example.resolver2\""))
            assertTrue(generatedContent.contains("\"className\":\"PhotoResolver\""))
            assertTrue(generatedContent.contains("\"sourceFileName\":\"PhotoResolver.kt\""))
            assertTrue(generatedContent.contains("\"typeName\":\"Photo\""))
            assertTrue(generatedContent.contains("fragment PhotoMain on Photo"))
        }

        @Test
        fun `process skips file generation when fragmentsOutputFile option is absent`() {
            val schemaSDL = """
                type Query { user(id: ID!): User }
                type User { id: ID! name: String }
            """.trimIndent()
            val schemaFile = createCompilationSchemaFile(schemaSDL)

            val mockEnvironment = mockk<SymbolProcessorEnvironment>()
            val mockCodeGenerator = mockk<CodeGenerator>()
            val mockLogger = mockk<KSPLogger>(relaxed = true)

            every { mockEnvironment.codeGenerator } returns mockCodeGenerator
            every { mockEnvironment.logger } returns mockLogger
            every { mockEnvironment.options } returns emptyMap()

            val userFragment = "fragment UserMain on User { id name }"
            val mockFragmentArg = createMockValueArgument("objectValueFragment", userFragment)
            val mockAnnotation = createMockResolverAnnotation(listOf(mockFragmentArg))
            val mockClassDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "UserResolver",
                annotations = sequenceOf(mockAnnotation),
                typeName = "User"
            )

            val mockSchemaKSFile = mockk<KSFile> {
                every { fileName } returns CompilationSchemaWrapperKtUtils.COMPILATION_SCHEMA_WRAPPER_KT_FILE
                every { filePath } returns schemaFile.absolutePath
                every { declarations } returns emptySequence()
            }
            val mockResolverKSFile = mockk<KSFile> {
                every { fileName } returns "UserResolver.kt"
                every { declarations } returns sequenceOf(mockClassDeclaration)
            }
            val mockResolver = mockk<Resolver> {
                every { getAllFiles() } returns sequenceOf(mockSchemaKSFile, mockResolverKSFile)
            }

            val processor = ResolverSelectionSetProcessor(mockEnvironment, Validator())
            processor.process(mockResolver)

            verify(exactly = 0) { mockCodeGenerator.createNewFile(any(), any(), any(), any()) }
        }

        @Test
        fun `process throws when compilation schema file is missing`() {
            val mockEnvironment = mockk<SymbolProcessorEnvironment>()
            val mockCodeGenerator = mockk<CodeGenerator>()
            val mockLogger = mockk<KSPLogger>(relaxed = true)

            every { mockEnvironment.codeGenerator } returns mockCodeGenerator
            every { mockEnvironment.logger } returns mockLogger
            every { mockEnvironment.options } returns emptyMap()

            val mockResolver = mockk<Resolver> {
                every { getAllFiles() } returns emptySequence()
            }

            val processor = ResolverSelectionSetProcessor(mockEnvironment, Validator())

            val exception = assertThrows<RuntimeException> {
                processor.process(mockResolver)
            }
            assertTrue(exception.message?.contains("Unable to read compilation schema SDL") == true)
        }

        @Test
        fun `process skips file generation when no resolver specs found`() {
            val schemaSDL = "type Query { hello: String }"
            val schemaFile = createCompilationSchemaFile(schemaSDL)

            val mockEnvironment = mockk<SymbolProcessorEnvironment>()
            val mockCodeGenerator = mockk<CodeGenerator>()
            val mockLogger = mockk<KSPLogger>(relaxed = true)

            every { mockEnvironment.codeGenerator } returns mockCodeGenerator
            every { mockEnvironment.logger } returns mockLogger
            every { mockEnvironment.options } returns mapOf(FRAGMENTS_OUTPUT_OPTION to TEST_FRAGMENTS_OUTPUT_FILE)

            val mockSchemaKSFile = mockk<KSFile> {
                every { fileName } returns CompilationSchemaWrapperKtUtils.COMPILATION_SCHEMA_WRAPPER_KT_FILE
                every { filePath } returns schemaFile.absolutePath
                every { declarations } returns emptySequence()
            }
            val mockResolver = mockk<Resolver> {
                every { getAllFiles() } returns sequenceOf(mockSchemaKSFile)
            }

            val processor = ResolverSelectionSetProcessor(mockEnvironment, Validator())
            processor.process(mockResolver)

            verify(exactly = 0) { mockCodeGenerator.createNewFile(any(), any(), any(), any()) }
        }

        @Test
        fun `process throws when fragment validation fails`() {
            val schemaSDL = """
                type Query { user(id: ID!): User }
                type User { id: ID! }
            """.trimIndent()
            val schemaFile = createCompilationSchemaFile(schemaSDL)

            val mockEnvironment = mockk<SymbolProcessorEnvironment>()
            val mockCodeGenerator = mockk<CodeGenerator>()
            val mockLogger = mockk<KSPLogger>(relaxed = true)

            every { mockEnvironment.codeGenerator } returns mockCodeGenerator
            every { mockEnvironment.logger } returns mockLogger
            every { mockEnvironment.options } returns emptyMap()

            // Fragment references non-existent field
            val invalidFragment = "fragment Main on User { id nonExistentField }"
            val mockFragmentArg = createMockValueArgument("objectValueFragment", invalidFragment)
            val mockAnnotation = createMockResolverAnnotation(listOf(mockFragmentArg))
            val mockClassDeclaration = createMockClassDeclaration(
                packageName = "com.example",
                className = "UserResolver",
                annotations = sequenceOf(mockAnnotation),
                typeName = "User"
            )

            val mockSchemaKSFile = mockk<KSFile> {
                every { fileName } returns CompilationSchemaWrapperKtUtils.COMPILATION_SCHEMA_WRAPPER_KT_FILE
                every { filePath } returns schemaFile.absolutePath
                every { declarations } returns emptySequence()
            }
            val mockResolverKSFile = mockk<KSFile> {
                every { fileName } returns "UserResolver.kt"
                every { declarations } returns sequenceOf(mockClassDeclaration)
            }
            val mockResolver = mockk<Resolver> {
                every { getAllFiles() } returns sequenceOf(mockSchemaKSFile, mockResolverKSFile)
            }

            val processor = ResolverSelectionSetProcessor(mockEnvironment, Validator())

            val exception = assertThrows<IllegalStateException> {
                processor.process(mockResolver)
            }
            assertTrue(exception.message?.contains("validation failed") == true)
        }
    }
}
