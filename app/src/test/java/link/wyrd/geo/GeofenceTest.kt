package link.wyrd.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeofenceTest {

    @Test
    fun an_enabled_payload_yields_its_zones() {
        val raw = """
            {"enabled":true,"zones":[
              {"id":1,"name":"Склад","kind":"keep_out","action":"wipe","polygon":[[55.7,37.6],[55.7,37.7],[55.8,37.7]]}
            ]}
        """.trimIndent()
        val gf = Geofence.fromJson(raw)!!
        assertEquals(1, gf.zones.size)
        val z = gf.zones[0]
        assertEquals("Склад", z.name)
        assertEquals("keep_out", z.kind)
        assertEquals("wipe", z.action)
        assertEquals(3, z.polygon.size)
        assertEquals(55.7, z.polygon[0][0], 1e-9)
        assertEquals(37.6, z.polygon[0][1], 1e-9)
    }

    @Test
    fun a_disabled_or_empty_payload_is_not_active() {
        assertNull(Geofence.fromJson("""{"enabled":false,"zones":[{"name":"X","polygon":[[1,1],[1,2],[2,2]]}]}"""))
        assertNull(Geofence.fromJson("""{"enabled":true,"zones":[]}"""))
        assertNull(Geofence.fromJson("not json"))
    }

    @Test
    fun a_vertex_that_is_not_a_pair_is_dropped_not_fatal() {
        val raw = """{"enabled":true,"zones":[{"name":"X","kind":"keep_in","action":"lock","polygon":[[1,1],["bad"],[2,2],[2,1]]}]}"""
        val gf = Geofence.fromJson(raw)!!
        assertTrue(gf.zones[0].polygon.size == 3)
    }
}
