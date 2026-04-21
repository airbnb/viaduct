package viaduct.tenant.codegen.ksp

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class RegistryExtractorProcessorProviderTest {
    @Test
    fun `create returns a RegistryExtractorProcessor`() {
        val logger = RecordingKspLogger()
        val codeGenerator = RecordingCodeGenerator()
        val environment = fakeEnvironment(logger = logger, codeGenerator = codeGenerator)

        val provider = RegistryExtractorProcessorProvider()
        val processor = provider.create(environment)

        assertNotNull(processor)
        assertTrue(
            processor is RegistryExtractorProcessor,
            "Expected RegistryExtractorProcessor but got ${processor::class.simpleName}",
        )
    }
}

private fun fakeEnvironment(
    logger: RecordingKspLogger,
    codeGenerator: RecordingCodeGenerator,
): SymbolProcessorEnvironment {
    return SymbolProcessorEnvironment(
        options = emptyMap(),
        kotlinVersion = KotlinVersion(1, 9),
        codeGenerator = codeGenerator,
        logger = logger,
    )
}
