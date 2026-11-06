package link.wyrd.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileWipeTest {

    @Test
    fun nothing_wipes_except_through_profile_wipe() {
        val sources = File("src/main/java")
        assertTrue("sources not found from ${File(".").absolutePath}", sources.isDirectory)
        val wipeCall = Regex("""\.(wipeDevice|wipeProfile|wipeData)\(""")
        val callers = sources.walk()
            .filter { it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    val code = line.trimStart()
                    !code.startsWith("*") && !code.startsWith("//") && wipeCall.containsMatchIn(code)
                }
            }
            .map { it.name }
            .toSet()
        assertEquals(setOf("DpmActions.kt", "ProfileWipe.kt"), callers)
    }
}
