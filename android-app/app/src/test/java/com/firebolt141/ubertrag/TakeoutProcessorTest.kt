package com.firebolt141.ubertrag

import com.firebolt141.ubertrag.util.TakeoutMeta
import com.firebolt141.ubertrag.util.TakeoutProcessor
import org.junit.Assert.*
import org.junit.Test

class TakeoutProcessorTest {

    // ── jsonCandidateNames ────────────────────────────────────────────────────

    @Test
    fun `standard jpg produces stem-dot-ext and stem candidates`() {
        val names = TakeoutProcessor.jsonCandidateNames("IMG_20240315_143022.jpg")
        assertTrue("IMG_20240315_143022.jpg.json" in names)
        assertTrue("IMG_20240315_143022.json"     in names)
    }

    @Test
    fun `long filename over 46 chars adds truncated candidate`() {
        val fileName = "a".repeat(50) + ".jpg"   // 54 chars total
        val names = TakeoutProcessor.jsonCandidateNames(fileName)
        assertTrue(names.any { it == "${fileName.take(46)}.json" })
    }

    @Test
    fun `filename at exactly 46 chars does not add truncated candidate`() {
        // stem(40) + ".jpg"(4) = 44 — under limit
        val fileName = "a".repeat(40) + ".jpg"
        val names = TakeoutProcessor.jsonCandidateNames(fileName)
        assertFalse(names.any { it.length < fileName.length + 5 && it.startsWith("a".repeat(40).take(46)) && it != "${fileName}.json" && it != "${"a".repeat(40)}.json" })
    }

    @Test
    fun `numbered duplicate pattern produces extra candidates`() {
        val names = TakeoutProcessor.jsonCandidateNames("photo(1).jpg")
        assertTrue("photo.jpg(1).json" in names)
        assertTrue("photo(1).json"     in names)
    }

    @Test
    fun `edited suffix produces original name candidates`() {
        val names = TakeoutProcessor.jsonCandidateNames("IMG_001-edited.jpg")
        assertTrue("IMG_001.jpg.json" in names)
        assertTrue("IMG_001.json"     in names)
    }

    @Test
    fun `supplemental metadata candidates always included`() {
        val names = TakeoutProcessor.jsonCandidateNames("photo.jpg")
        assertTrue("photo.jpg.supplemental-metadata.json" in names)
        assertTrue("photo.supplemental-metadata.json"     in names)
    }

    @Test
    fun `video file works the same way`() {
        val names = TakeoutProcessor.jsonCandidateNames("VID_20240315_143022.mp4")
        assertTrue("VID_20240315_143022.mp4.json" in names)
        assertTrue("VID_20240315_143022.json"     in names)
    }

    // ── parseMeta ─────────────────────────────────────────────────────────────

    @Test
    fun `parses photoTakenTime timestamp`() {
        val json = """{"photoTakenTime":{"timestamp":"1710500000","formatted":"Mar 15, 2024"}}"""
        val meta = TakeoutProcessor.parseMeta(json)
        assertEquals(1710500000L, meta.timestampSec)
    }

    @Test
    fun `falls back to creationTime when photoTakenTime absent`() {
        val json = """{"creationTime":{"timestamp":"1710500001"}}"""
        val meta = TakeoutProcessor.parseMeta(json)
        assertEquals(1710500001L, meta.timestampSec)
    }

    @Test
    fun `photoTakenTime takes priority over creationTime`() {
        val json = """
            {
              "photoTakenTime": {"timestamp":"1000"},
              "creationTime":   {"timestamp":"2000"}
            }
        """.trimIndent()
        assertEquals(1000L, TakeoutProcessor.parseMeta(json).timestampSec)
    }

    @Test
    fun `parses geoDataExif latitude and longitude`() {
        val json = """
            {
              "photoTakenTime": {"timestamp":"1000"},
              "geoDataExif": {"latitude":48.8566,"longitude":2.3522,"altitude":35.0}
            }
        """.trimIndent()
        val meta = TakeoutProcessor.parseMeta(json)
        assertEquals(48.8566, meta.latitude!!, 0.0001)
        assertEquals(2.3522,  meta.longitude!!, 0.0001)
        assertEquals(35.0,    meta.altitude!!, 0.01)
    }

    @Test
    fun `geoDataExif takes priority over geoData`() {
        val json = """
            {
              "geoDataExif": {"latitude":1.0,"longitude":2.0,"altitude":0.0},
              "geoData":     {"latitude":3.0,"longitude":4.0,"altitude":0.0}
            }
        """.trimIndent()
        val meta = TakeoutProcessor.parseMeta(json)
        assertEquals(1.0, meta.latitude!!, 0.001)
    }

    @Test
    fun `zero lat-lon is ignored, falls back to next geo key`() {
        val json = """
            {
              "geoDataExif": {"latitude":0.0,"longitude":0.0,"altitude":0.0},
              "geoData":     {"latitude":5.0,"longitude":6.0,"altitude":0.0}
            }
        """.trimIndent()
        val meta = TakeoutProcessor.parseMeta(json)
        assertEquals(5.0, meta.latitude!!, 0.001)
    }

    @Test
    fun `parses description field`() {
        val json = """{"description":"Sunset at the beach"}"""
        assertEquals("Sunset at the beach", TakeoutProcessor.parseMeta(json).description)
    }

    @Test
    fun `invalid JSON returns empty meta`() {
        val meta = TakeoutProcessor.parseMeta("not json at all {{")
        assertNull(meta.timestampSec)
        assertNull(meta.latitude)
        assertEquals("", meta.description)
    }

    @Test
    fun `empty JSON object returns empty meta`() {
        val meta = TakeoutProcessor.parseMeta("{}")
        assertNull(meta.timestampSec)
    }

    // ── tsFromFilename ────────────────────────────────────────────────────────

    @Test
    fun `parses IMG_YYYYMMDD_HHMMSS format`() {
        val ts = TakeoutProcessor.tsFromFilename("IMG_20240315_143022.jpg")
        assertNotNull(ts)
        // 2024-03-15 noon UTC
        val expected = java.time.LocalDateTime.of(2024, 3, 15, 12, 0, 0)
            .toInstant(java.time.ZoneOffset.UTC).epochSecond
        assertEquals(expected, ts)
    }

    @Test
    fun `parses PXL_YYYYMMDD_HHMMSSMMM format`() {
        val ts = TakeoutProcessor.tsFromFilename("PXL_20240315_143022000.jpg")
        assertNotNull(ts)
    }

    @Test
    fun `parses Screenshot_YYYYMMDD-HHMMSS format`() {
        assertNotNull(TakeoutProcessor.tsFromFilename("Screenshot_20240315-143022.png"))
    }

    @Test
    fun `rejects year outside 2000-2040`() {
        assertNull(TakeoutProcessor.tsFromFilename("IMG_19991231_000000.jpg"))
        assertNull(TakeoutProcessor.tsFromFilename("IMG_20991231_000000.jpg"))
    }

    @Test
    fun `rejects invalid month`() {
        assertNull(TakeoutProcessor.tsFromFilename("IMG_20241300_000000.jpg"))
    }

    @Test
    fun `returns null for generic filename with no date`() {
        assertNull(TakeoutProcessor.tsFromFilename("random_photo.jpg"))
    }
}
