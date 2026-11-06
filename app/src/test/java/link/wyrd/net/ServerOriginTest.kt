package link.wyrd.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerOriginTest {
    private val server = "https://d1abc.cloudfront.net/prefix"

    @Test
    fun anyPathOnTheEnrolledServerPasses() {
        assertTrue(onServer("https://d1abc.cloudfront.net/prefix/apks/7?t=x", server))
        assertTrue(onServer("https://D1ABC.cloudfront.net:443/prefix/tiles", server))
    }

    @Test
    fun anotherHostPortOrSchemeIsRefused() {
        assertFalse(onServer("https://evil.example/prefix/apks/7", server))
        assertFalse(onServer("https://d1abc.cloudfront.net.evil.example/x", server))
        assertFalse(onServer("https://d1abc.cloudfront.net:8443/prefix", server))
        assertFalse(onServer("http://d1abc.cloudfront.net/prefix", server))
        assertFalse(onServer("https://d1abc.cloudfront.net@evil.example/x", server))
        assertFalse(onServer("not a url", server))
        assertFalse(onServer("https://d1abc.cloudfront.net/x", null))
    }
}
