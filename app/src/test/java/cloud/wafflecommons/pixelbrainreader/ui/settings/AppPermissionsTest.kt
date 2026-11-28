package cloud.wafflecommons.pixelbrainreader.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Permissions page (13.4, S11) shows what the manifest declares, and adds none: every permission
 * the manifest asks for has its row, and no row names one it doesn't.
 */
class AppPermissionsTest {

    private val manifest: File = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml")
        .map(::File)
        .first { it.isFile }

    /** The `uses-permission` names of the manifest (a component's own `android:permission` isn't one). */
    private fun declared(): Set<String> =
        Regex("<uses-permission\\b[^>]*android:name=\"([^\"]+)\"")
            .findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every permission the manifest declares has a row, and no row names one it doesn't`() {
        val declared = declared()
        val rows = AppPermissions.ALL.flatMap { it.manifest }
        assertTrue("The manifest declares no permission: is the path right?", declared.isNotEmpty())
        assertEquals("Each manifest permission in one row only", rows.size, rows.toSet().size)
        assertEquals(
            "Rows missing: ${declared - rows.toSet()}; rows for undeclared permissions: ${rows.toSet() - declared}",
            declared,
            rows.toSet()
        )
    }

    @Test
    fun `the health row holds every Health Connect permission, and only those`() {
        val health = AppPermissions.ALL.single { it.kind == PermissionKind.HEALTH }
        assertEquals(declared().filter { it.startsWith(AppPermissions.HEALTH_PREFIX) }.toSet(), health.manifest.toSet())
        assertTrue(health.manifest.all { it.startsWith(AppPermissions.HEALTH_PREFIX) })
    }

    @Test
    fun `each row has a key of its own, a name and what it is for`() {
        val keys = AppPermissions.ALL.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { Regex("[a-z]+(_[a-z]+)*").matches(it) })
        assertTrue(AppPermissions.ALL.all { it.title.isNotBlank() && it.purpose.isNotBlank() && it.manifest.isNotEmpty() })
        // The approved rows, in order (S11).
        assertEquals(listOf("notifications", "live_updates", "dnd", "location", "microphone", "health", "internet"), keys)
    }
}
