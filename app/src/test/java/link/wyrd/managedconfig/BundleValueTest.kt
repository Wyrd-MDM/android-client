package link.wyrd.managedconfig

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BundleValueTest {

    private val json = Json

    private fun parse(src: String): JsonObject =
        json.parseToJsonElement(src) as JsonObject

    @Test
    fun booleanRoundTrip() {
        val bv = JsonBundleCodec.fromJson(parse("""{"a":true,"b":false}"""))
        assertEquals(BundleValue.Bool(true), bv.v["a"])
        assertEquals(BundleValue.Bool(false), bv.v["b"])
    }

    @Test
    fun intAndLongBoundary() {
        val bv = JsonBundleCodec.fromJson(
            parse("""{"small":42,"negSmall":-7,"max":2147483647,"overflow":2147483648}""")
        )
        assertEquals(BundleValue.IntV(42), bv.v["small"])
        assertEquals(BundleValue.IntV(-7), bv.v["negSmall"])
        assertEquals(BundleValue.IntV(Int.MAX_VALUE), bv.v["max"])
        assertEquals(BundleValue.LongV(2147483648L), bv.v["overflow"])
    }

    @Test
    fun longPrefixParses() {
        val bv = JsonBundleCodec.fromJson(
            parse("""{"TimestampMs":"long:9223372036854775807","NegativeLong":"long:-1234567890123456789"}""")
        )
        assertEquals(BundleValue.LongV(Long.MAX_VALUE), bv.v["TimestampMs"])
        assertEquals(BundleValue.LongV(-1234567890123456789L), bv.v["NegativeLong"])
    }

    @Test
    fun doubleHasFraction() {
        val bv = JsonBundleCodec.fromJson(parse("""{"d":1.5,"e":1e2}"""))
        assertEquals(BundleValue.DoubleV(1.5), bv.v["d"])
        assertEquals(BundleValue.DoubleV(100.0), bv.v["e"])
    }

    @Test
    fun stringArrayDetected() {
        val bv = JsonBundleCodec.fromJson(
            parse("""{"URLBlocklist":["*://x/*","*://y/*"]}""")
        )
        val arr = bv.v["URLBlocklist"] as BundleValue.StrArray
        assertEquals(listOf("*://x/*", "*://y/*"), arr.v)
    }

    @Test
    fun intArrayDetected() {
        val bv = JsonBundleCodec.fromJson(parse("""{"Ports":[80,443,8080]}"""))
        val arr = bv.v["Ports"] as BundleValue.IntArray
        assertEquals(listOf(80, 443, 8080), arr.v)
    }

    @Test
    fun objectArrayDetected() {
        val bv = JsonBundleCodec.fromJson(
            parse("""{"ManagedBookmarks":[{"name":"A","url":"x"},{"name":"B","url":"y"}]}""")
        )
        val arr = bv.v["ManagedBookmarks"] as BundleValue.ObjArray
        assertEquals(2, arr.v.size)
        assertEquals(BundleValue.Str("A"), arr.v[0]["name"])
        assertEquals(BundleValue.Str("y"), arr.v[1]["url"])
    }

    @Test
    fun nestedObject() {
        val bv = JsonBundleCodec.fromJson(
            parse("""{"outer":{"inner":{"leaf":1}}}""")
        )
        val outer = bv.v["outer"] as BundleValue.Obj
        val inner = outer.v["inner"] as BundleValue.Obj
        assertEquals(BundleValue.IntV(1), inner.v["leaf"])
    }

    @Test
    fun emptyArrayBecomesEmptyStringArray() {
        val bv = JsonBundleCodec.fromJson(parse("""{"x":[]}"""))
        assertEquals(BundleValue.StrArray(emptyList()), bv.v["x"])
    }

    @Test
    fun rejectsExplicitNull() {
        try {
            JsonBundleCodec.fromJson(parse("""{"ProxyServer":null}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("null_values_not_supported", e.code)
        }
    }

    @Test
    fun rejectsNestedNull() {
        try {
            JsonBundleCodec.fromJson(parse("""{"outer":{"inner":null}}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("null_values_not_supported", e.code)
        }
    }

    @Test
    fun rejectsHeterogeneousArray() {
        try {
            JsonBundleCodec.fromJson(parse("""{"bad":[1,"two",true]}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("heterogeneous_array", e.code)
        }
    }

    @Test
    fun rejectsInvalidLongPrefix() {
        try {
            JsonBundleCodec.fromJson(parse("""{"x":"long:abc"}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("invalid_long_prefix", e.code)
        }
    }

    @Test
    fun rejectsLongPrefixOverflow() {
        try {
            JsonBundleCodec.fromJson(parse("""{"x":"long:99999999999999999999"}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("invalid_long_prefix", e.code)
        }
    }

    @Test
    fun rejectsNestedHeterogeneous() {
        try {
            JsonBundleCodec.fromJson(parse("""{"o":{"arr":[1,"two"]}}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("heterogeneous_array", e.code)
        }
    }

    @Test
    fun rejectsDoubleArray() {
        try {
            JsonBundleCodec.fromJson(parse("""{"arr":[1.0,2.0]}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("double_array_unsupported", e.code)
        }
    }

    @Test
    fun rejectsBoolArray() {
        try {
            JsonBundleCodec.fromJson(parse("""{"arr":[true,false]}"""))
            fail("expected CodecError")
        } catch (e: JsonBundleCodec.CodecError) {
            assertEquals("bool_array_unsupported", e.code)
        }
    }

    @Test
    fun jsonToBundleValueToJsonRoundTripsLongPrefix() {
        val src = parse("""{"t":"long:9223372036854775807","n":"long:-1"}""")
        val bv = JsonBundleCodec.fromJson(src)
        val back = JsonBundleCodec.toJson(bv)
        assertEquals(src.toString(), back.toString())
    }

    @Test
    fun jsonToBundleValueToJsonRoundTripsRichPayload() {
        val src = parse(
            """{"HomepageLocation":"https://intranet","IncognitoModeAvailability":1,"ManagedBookmarks":[{"name":"HR","url":"https://hr"},{"name":"Help","url":"https://help"}],"URLBlocklist":["*://fb/*","*://tw/*"]}"""
        )
        val bv = JsonBundleCodec.fromJson(src)
        val back = JsonBundleCodec.toJson(bv)
        assertTrue("has HomepageLocation", back.containsKey("HomepageLocation"))
        assertEquals(src, back)
    }
}
