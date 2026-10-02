package com.firebolt141.ubertrag

import com.firebolt141.ubertrag.util.PhotoDate
import com.firebolt141.ubertrag.util.PhotoLogic
import org.junit.Assert.*
import org.junit.Test

class PhotoLogicTest {

    // ── jsonCandidateNames ────────────────────────────────────────────────────

    @Test
    fun `standard jpg produces stem-dot-ext and stem candidates`() {
        val names = PhotoLogic.jsonCandidateNames("IMG_20240315_143022.jpg")
        assertTrue("IMG_20240315_143022.jpg.json" in names)
        assertTrue("IMG_20240315_143022.json"     in names)
    }

    @Test
    fun `long filename over 46 chars adds truncated candidate`() {
        val fileName = "a".repeat(50) + ".jpg"   // 54 chars total
        val names = PhotoLogic.jsonCandidateNames(fileName)
        assertTrue(names.any { it == "${fileName.take(46)}.json" })
    }

    @Test
    fun `filename at exactly 46 chars does not add truncated candidate`() {
        // stem(40) + ".jpg"(4) = 44 — under limit
        val fileName = "a".repeat(40) + ".jpg"
        val names = PhotoLogic.jsonCandidateNames(fileName)
        assertFalse(names.any { it.length < fileName.length + 5 && it.startsWith("a".repeat(40).take(46)) && it != "${fileName}.json" && it != "${"a".repeat(40)}.json" })
    }

    @Test
    fun `numbered duplicate pattern produces extra candidates`() {
        val names = PhotoLogic.jsonCandidateNames("photo(1).jpg")
        assertTrue("photo.jpg(1).json" in names)
        assertTrue("photo(1).json"     in names)
    }

    @Test
    fun `edited suffix produces original name candidates`() {
        val names = PhotoLogic.jsonCandidateNames("IMG_001-edited.jpg")
        assertTrue("IMG_001.jpg.json" in names)
        assertTrue("IMG_001.json"     in names)
    }

    @Test
    fun `supplemental metadata candidates always included`() {
        val names = PhotoLogic.jsonCandidateNames("photo.jpg")
        assertTrue("photo.jpg.supplemental-metadata.json" in names)
        assertTrue("photo.supplemental-metadata.json"     in names)
    }

    @Test
    fun `video file works the same way`() {
        val names = PhotoLogic.jsonCandidateNames("VID_20240315_143022.mp4")
        assertTrue("VID_20240315_143022.mp4.json" in names)
        assertTrue("VID_20240315_143022.json"     in names)
    }

    // ── parseMeta ─────────────────────────────────────────────────────────────

    @Test
    fun `parses photoTakenTime timestamp`() {
        val json = """{"photoTakenTime":{"timestamp":"1710500000","formatted":"Mar 15, 2024"}}"""
        val meta = PhotoLogic.parseMeta(json)
        assertEquals(1710500000L, meta.timestampSec)
    }

    @Test
    fun `falls back to creationTime when photoTakenTime absent`() {
        val json = """{"creationTime":{"timestamp":"1710500001"}}"""
        val meta = PhotoLogic.parseMeta(json)
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
        assertEquals(1000L, PhotoLogic.parseMeta(json).timestampSec)
    }

    @Test
    fun `parses geoDataExif latitude and longitude`() {
        val json = """
            {
              "photoTakenTime": {"timestamp":"1000"},
              "geoDataExif": {"latitude":48.8566,"longitude":2.3522,"altitude":35.0}
            }
        """.trimIndent()
        val meta = PhotoLogic.parseMeta(json)
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
        val meta = PhotoLogic.parseMeta(json)
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
        val meta = PhotoLogic.parseMeta(json)
        assertEquals(5.0, meta.latitude!!, 0.001)
    }

    @Test
    fun `parses description field`() {
        val json = """{"description":"Sunset at the beach"}"""
        assertEquals("Sunset at the beach", PhotoLogic.parseMeta(json).description)
    }

    @Test
    fun `invalid JSON returns empty meta`() {
        val meta = PhotoLogic.parseMeta("not json at all {{")
        assertNull(meta.timestampSec)
        assertNull(meta.latitude)
        assertEquals("", meta.description)
    }

    @Test
    fun `empty JSON object returns empty meta`() {
        val meta = PhotoLogic.parseMeta("{}")
        assertNull(meta.timestampSec)
    }

    // ── tsFromFilename ────────────────────────────────────────────────────────

    @Test
    fun `parses IMG_YYYYMMDD_HHMMSS format`() {
        val ts = PhotoLogic.tsFromFilename("IMG_20240315_143022.jpg")
        assertNotNull(ts)
        // 2024-03-15 noon UTC
        val expected = java.time.LocalDateTime.of(2024, 3, 15, 12, 0, 0)
            .toInstant(java.time.ZoneOffset.UTC).epochSecond
        assertEquals(expected, ts)
    }

    @Test
    fun `parses PXL_YYYYMMDD_HHMMSSMMM format`() {
        val ts = PhotoLogic.tsFromFilename("PXL_20240315_143022000.jpg")
        assertNotNull(ts)
    }

    @Test
    fun `parses Screenshot_YYYYMMDD-HHMMSS format`() {
        assertNotNull(PhotoLogic.tsFromFilename("Screenshot_20240315-143022.png"))
    }

    @Test
    fun `rejects year outside 2000-2040`() {
        assertNull(PhotoLogic.tsFromFilename("IMG_19991231_000000.jpg"))
        assertNull(PhotoLogic.tsFromFilename("IMG_20991231_000000.jpg"))
    }

    @Test
    fun `accepts boundary year 2040`() {
        assertNotNull(PhotoLogic.tsFromFilename("IMG_20401231_000000.jpg"))
    }

    @Test
    fun `rejects invalid month`() {
        assertNull(PhotoLogic.tsFromFilename("IMG_20241300_000000.jpg"))
    }

    @Test
    fun `rejects impossible day for month (Feb 30)`() {
        assertNull(PhotoLogic.tsFromFilename("IMG_20240230_000000.jpg"))
    }

    @Test
    fun `returns null for generic filename with no date`() {
        assertNull(PhotoLogic.tsFromFilename("random_photo.jpg"))
    }

    // ── dateFromFilename (time of day, zones) ────────────────────────────────

    @Test
    fun `filename time is local wall time with the zone offset`() {
        val zone = java.time.ZoneId.of("Asia/Kolkata")
        val pd = PhotoLogic.dateFromFilename("IMG_20240315_143022.jpg", zone)!!
        assertEquals(java.time.LocalDateTime.of(2024, 3, 15, 14, 30, 22), pd.wall)
        assertEquals("+05:30", pd.offsetString)
        assertEquals("2024:03:15 14:30:22", pd.exif)
    }

    @Test
    fun `date-only names are noon UTC`() {
        val pd = PhotoLogic.dateFromFilename("VID-20240315-WA0001.mp4")!!
        assertEquals(12, pd.wall.hour)
        assertEquals("+00:00", pd.offsetString)
    }

    @Test
    fun `screenshot with dashes and millis`() {
        val pd = PhotoLogic.dateFromFilename("Screenshot_2024-03-15-14-30-22-123_com.app.jpg")!!
        assertEquals(java.time.LocalDateTime.of(2024, 3, 15, 14, 30, 22), pd.wall)
    }

    @Test
    fun `epoch millisecond names`() {
        val pd = PhotoLogic.dateFromFilename("1710513022123.jpg")!!
        assertEquals(java.time.LocalDateTime.of(2024, 3, 15, 14, 30, 22), pd.wall)
    }

    @Test
    fun `invalid first match falls through to a later valid one`() {
        assertNotNull(PhotoLogic.dateFromFilename("12345678_IMG_20200101.jpg"))
        assertNull(PhotoLogic.dateFromFilename("DSC_1234.JPG"))
    }

    // ── EXIF dates & wall clock ──────────────────────────────────────────────

    @Test
    fun `parse exif dates`() {
        assertEquals(java.time.LocalDateTime.of(2020, 5, 6, 7, 8, 9), PhotoLogic.parseExifDateTime("2020:05:06 07:08:09"))
        assertNull(PhotoLogic.parseExifDateTime("0000:00:00 00:00:00"))
        assertNull(PhotoLogic.parseExifDateTime("1904:01:01 00:00:00"))
        assertNull(PhotoLogic.parseExifDateTime(""))
        assertNull(PhotoLogic.parseExifDateTime(null))
    }

    @Test
    fun `phone backup folders use the local day, not UTC`() {
        // 2024-03-16 03:00 UTC is still the evening of March 15 in New York
        val epoch = java.time.Instant.parse("2024-03-16T03:00:00Z").toEpochMilli()
        val wall = PhotoLogic.wallMs(epoch, java.time.ZoneId.of("America/New_York"))
        assertEquals(listOf("2024", "March", "March_15"), PhotoLogic.dateFolders(wall))
        assertEquals(listOf("2024", "March", "March_16"), PhotoLogic.dateFolders(epoch))
    }

    // ── Folder names ─────────────────────────────────────────────────────────

    @Test
    fun `day and month folder parsing`() {
        assertEquals(7, PhotoLogic.parseDayFolder("January_07"))
        assertEquals(7, PhotoLogic.parseDayFolder("January 7"))
        assertEquals(15, PhotoLogic.parseDayFolder("15"))
        assertNull(PhotoLogic.parseDayFolder("January_32"))
        assertNull(PhotoLogic.parseDayFolder("Burst"))
        assertEquals(3, PhotoLogic.parseMonthFolder("march"))
        assertEquals(3, PhotoLogic.parseMonthFolder("03"))
        assertNull(PhotoLogic.parseMonthFolder("13"))
        assertEquals(2024, PhotoLogic.parseYearFolder("2024"))
        assertNull(PhotoLogic.parseYearFolder("1999"))
    }

    @Test
    fun `legacy rename targets`() {
        assertEquals("January", PhotoLogic.legacyMonthRename("01"))
        assertNull(PhotoLogic.legacyMonthRename("January"))
        assertEquals("January_15", PhotoLogic.legacyDayRename(1, "15"))
        assertEquals("January_07", PhotoLogic.legacyDayRename(1, "January 7"))
        assertEquals("January_07", PhotoLogic.legacyDayRename(1, "January_7"))
        assertNull(PhotoLogic.legacyDayRename(1, "January_07"))
        assertNull(PhotoLogic.legacyDayRename(1, "February_07"))   // wrong month: leave it
        assertNull(PhotoLogic.legacyDayRename(1, "Burst"))
    }

    @Test
    fun `date folders`() {
        val pd = PhotoDate(java.time.LocalDateTime.of(2024, 1, 7, 9, 0), null, "filename")
        assertEquals(listOf("2024", "January", "January_07"), PhotoLogic.dateFolders(pd.wallMs))
        assertEquals("2024-01-07_09-00-00.jpg", PhotoLogic.dateFileName(pd, "IMG_1.JPG"))
    }

    @Test
    fun `unique names`() {
        val taken = setOf("a.jpg", "a_1.jpg")
        assertEquals("a_2.jpg", PhotoLogic.uniqueName("a.jpg") { it in taken })
        assertEquals("b.jpg", PhotoLogic.uniqueName("b.jpg") { it in taken })
        assertEquals("noext_1", PhotoLogic.uniqueName("noext") { it == "noext" })
    }

    @Test
    fun `media and junk detection`() {
        assertTrue(PhotoLogic.isMedia("IMG_1.CR3"))
        assertTrue(PhotoLogic.isMedia("clip.3g2"))
        assertFalse(PhotoLogic.isMedia("._IMG_1.jpg"))
        assertFalse(PhotoLogic.isMedia(".~ubertrag~IMG_1.jpg"))
        assertFalse(PhotoLogic.isMedia("notes.txt"))
        assertTrue(PhotoLogic.isWritable("a.JPG"))
        assertFalse(PhotoLogic.isWritable("a.heic"))
        assertTrue(PhotoLogic.isArchive("takeout-001.zip"))
        assertTrue(PhotoLogic.isArchive("takeout.tar.gz"))
        assertFalse(PhotoLogic.isArchive("._takeout.zip"))
    }

    // ── Sidecar matching ─────────────────────────────────────────────────────

    @Test
    fun `finds abbreviated supplemental sidecar`() {
        val sib = setOf("img_1234.jpg", "img_1234.jpg.supplemental-meta.json")
        assertEquals("img_1234.jpg.supplemental-meta.json", PhotoLogic.findSidecar("IMG_1234.jpg", sib))
    }

    @Test
    fun `numbered photos get their own numbered sidecar`() {
        val sib = setOf("img_1.jpg.supplemental-metadata.json", "img_1.jpg.supplemental-metadata(1).json")
        assertEquals("img_1.jpg.supplemental-metadata(1).json", PhotoLogic.findSidecar("IMG_1(1).jpg", sib))
        assertEquals("img_1.jpg.supplemental-metadata.json", PhotoLogic.findSidecar("IMG_1.jpg", sib))
    }

    @Test
    fun `live photo video uses the image sidecar`() {
        assertEquals("img_5.heic.json", PhotoLogic.findSidecar("IMG_5.MP4", setOf("img_5.heic.json")))
    }

    @Test
    fun `truncated long names`() {
        val name = "a_really_long_file_name_that_google_truncates_xx.jpg"
        val j = name.take(46).lowercase() + ".json"
        assertEquals(j, PhotoLogic.findSidecar(name, setOf(j)))
    }

    @Test
    fun `album metadata json is never a sidecar`() {
        assertNull(PhotoLogic.findSidecar("metadata.jpg", setOf("metadata.json")))
        assertFalse(PhotoLogic.isSidecarName("metadata.json"))
        assertTrue(PhotoLogic.isSidecarName("IMG_1.jpg.json"))
    }

    @Test
    fun `edited in other languages`() {
        assertTrue("IMG_001.jpg.json" in PhotoLogic.jsonCandidateNames("IMG_001-bearbeitet.jpg"))
    }

    // ── parseMeta extras ─────────────────────────────────────────────────────

    @Test
    fun `parseMeta source people favourite`() {
        val m = PhotoLogic.parseMeta("""{"creationTime":{"timestamp":"5"},"people":[{"name":" Ann "},{"x":1}],"favorited":true}""")
        assertEquals(5L, m.timestampSec)
        assertEquals("creationTime", m.timestampSource)
        assertEquals(listOf("Ann"), m.people)
        assertTrue(m.favorited)
    }

    @Test
    fun `parseMeta rejects absurd timestamps and coordinates`() {
        assertNull(PhotoLogic.parseMeta("""{"photoTakenTime":{"timestamp":"99999999999"}}""").timestampSec)
        assertNull(PhotoLogic.parseMeta("""{"geoData":{"latitude":200,"longitude":5}}""").latitude)
    }
}
