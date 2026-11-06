package link.wyrd.managedconfig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonBundleCodecFixturesTest {

    private val json = Json

    private fun loadFixture(name: String): String {
        val path = "/fixtures/managed_configs/$name"
        val stream = javaClass.getResourceAsStream(path)
            ?: error("fixture not found: $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun parse(name: String): JsonObject =
        json.parseToJsonElement(loadFixture(name)) as JsonObject

    @Test
    fun chromeMinimalRoundTrips() {
        val src = parse("chrome_minimal.json")
        val bv = JsonBundleCodec.fromJson(src)
        val back = JsonBundleCodec.toJson(bv)
        assertEquals(src, back)
    }

    @Test
    fun chromeRichRoundTrips() {
        val src = parse("chrome_rich.json")
        val bv = JsonBundleCodec.fromJson(src)

        assertTrue(bv.v["HomepageLocation"] is BundleValue.Str)
        assertTrue(bv.v["IncognitoModeAvailability"] is BundleValue.IntV)
        assertTrue(bv.v["ManagedBookmarks"] is BundleValue.ObjArray)
        assertTrue(bv.v["URLBlocklist"] is BundleValue.StrArray)

        val back = JsonBundleCodec.toJson(bv)
        assertEquals(src, back)
    }

    @Test
    fun longsFixtureRoundTrips() {
        val src = parse("longs.json")
        val bv = JsonBundleCodec.fromJson(src)
        assertEquals(BundleValue.LongV(Long.MAX_VALUE), bv.v["TimestampMs"])
        assertEquals(BundleValue.LongV(-1234567890123456789L), bv.v["NegativeLong"])
        val back = JsonBundleCodec.toJson(bv)
        assertEquals(src, back)
    }

    @Test
    fun invalidNullFixtureRejected() {
        try {
            JsonBundleCodec.fromJson(parse("invalid_null.json"))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("null_values_not_supported", e.code)
        }
    }

    @Test
    fun invalidHeterogeneousFixtureRejected() {
        try {
            JsonBundleCodec.fromJson(parse("invalid_heterogeneous.json"))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("heterogeneous_array", e.code)
        }
    }
}
