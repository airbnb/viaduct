package viaduct.graphql.schema.binary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for bit manipulation utilities and value class encoding in constants.kt.
 *
 * These tests verify that bit manipulation functions (isBitSet, extract) and
 * value class encoding/decoding work correctly.
 */
class ConstantsTest {
    @Test
    fun `isBitSet function correctly identifies set bits`() {
        val word1 = 0b1000
        assertTrue(word1.isBitSet(0b1000))
        assertFalse(word1.isBitSet(0b0100))

        val word2 = 0b1010
        assertTrue(word2.isBitSet(0b1000))
        assertTrue(word2.isBitSet(0b0010))
        assertFalse(word2.isBitSet(0b0100))
    }

    @Test
    fun `isBitSet with multiple bits in mask checks single bit`() {
        val word = 0b1111
        assertTrue(word.isBitSet(0b1000))
        assertTrue(word.isBitSet(0b0100))
        assertTrue(word.isBitSet(0b0010))
        assertTrue(word.isBitSet(0b0001))
    }

    @Test
    fun `isBitSet with zero word`() {
        val word = 0b0000
        assertFalse(word.isBitSet(0b1000))
        assertFalse(word.isBitSet(0b0100))
        assertFalse(word.isBitSet(0b0010))
        assertFalse(word.isBitSet(0b0001))
    }

    @Test
    fun `extract function retrieves field values from packed words`() {
        // Field value is 0b111 (7) in bits 6-8
        val word1 = 0b0001_1100_0000
        assertEquals(7, word1.extract(0b0001_1100_0000))

        // Field value is 0b101 (5) in bits 2-4
        val word2 = 0b0001_0100
        assertEquals(5, word2.extract(0b0001_1100))
    }

    @Test
    fun `extract function with single bit field`() {
        val word = 0b1000
        assertEquals(1, word.extract(0b1000))

        val word2 = 0b0100
        assertEquals(1, word2.extract(0b0100))
    }

    @Test
    fun `extract function with zero field value`() {
        val word = 0b0000_0000
        assertEquals(0, word.extract(0b1111_0000))
    }

    @Test
    fun `extract function with full field value`() {
        val word = 0b1111_0000
        assertEquals(15, word.extract(0b1111_0000))
    }

    @Test
    fun `DirectiveInfo encoding and decoding repeatable flag`() {
        // Test that repeatable flag is correctly encoded and decoded
        val word = DirectiveInfo.REPEATABLE_BIT
        val info = DirectiveInfo(word)
        assertTrue(info.isRepeatable())
    }

    @Test
    fun `DirectiveInfo encoding and decoding hasArgs flag`() {
        // Test that hasArgs flag is correctly encoded and decoded
        val word = DirectiveInfo.HAS_ARGS_BIT
        val info = DirectiveInfo(word)
        assertTrue(info.hasArgs())
    }

    @Test
    fun `DirectiveInfo with no flags set`() {
        val word = 0
        val info = DirectiveInfo(word)
        assertFalse(info.isRepeatable())
        assertFalse(info.hasArgs())
    }

    @Test
    fun `RefPlus hasNext flag encoding`() {
        // Test that hasNext flag correctly determines continuation bit
        val withNext = InputLikeFieldRefPlus(nameIndex = 42, hasAppliedDirectives = false, hasDefaultValue = false, hasNext = true)
        assertTrue(withNext.hasNext())

        val withoutNext = InputLikeFieldRefPlus(nameIndex = 42, hasAppliedDirectives = false, hasDefaultValue = false, hasNext = false)
        assertFalse(withoutNext.hasNext())
    }

    @Test
    fun `RefPlus hasDefaultValue flag encoding`() {
        // Test that hasDefaultValue flag is correctly encoded
        val withDefault = InputLikeFieldRefPlus(nameIndex = 42, hasAppliedDirectives = false, hasDefaultValue = true, hasNext = false)
        assertTrue(withDefault.hasDefaultValue())

        val withoutDefault = InputLikeFieldRefPlus(nameIndex = 42, hasAppliedDirectives = false, hasDefaultValue = false, hasNext = false)
        assertFalse(withoutDefault.hasDefaultValue())
    }

    @Test
    fun `RefPlus getIndex extracts name index`() {
        val refPlus = InputLikeFieldRefPlus(nameIndex = 123, hasAppliedDirectives = false, hasDefaultValue = false, hasNext = false)
        assertEquals(123, refPlus.getIndex())
    }

    @Test
    fun `RefPlus forDefinition encoding`() {
        val refPlus = DefinitionRefPlus(sourceLocationIndex = 456, hasImplementedTypes = true)
        assertEquals(456, refPlus.getIndex())
        assertTrue(refPlus.hasImplementedTypes())
    }

    @Test
    fun `RefPlus forDefinition without implemented types`() {
        val refPlus = DefinitionRefPlus(sourceLocationIndex = 789, hasImplementedTypes = false)
        assertEquals(789, refPlus.getIndex())
        assertFalse(refPlus.hasImplementedTypes())
    }
}
