package viaduct.codegen.km

import kotlinx.metadata.KmClassifier
import kotlinx.metadata.KmType
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import viaduct.codegen.utils.name

class CommonUtilsTests {
    @Test
    fun `Given KmType with non-class classifier then KmType_name should throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            KmType()
                .apply {
                    classifier = KmClassifier.TypeAlias("foo")
                }.name
        }
        assertThrows(IllegalArgumentException::class.java) {
            KmType()
                .apply {
                    classifier = KmClassifier.TypeParameter(0)
                }.name
        }
    }
}
