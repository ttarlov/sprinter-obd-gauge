package com.revel.obdgauge.app.recording

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * OBD-70 Robolectric AC: the share `Intent`'s shape (action/type/flags/EXTRA_STREAM), and that
 * the `FileProvider` uri actually resolves against the real merged manifest + `file_paths.xml` —
 * a file OUTSIDE the exposed `logs/` external-files path would throw `IllegalArgumentException`
 * from `FileProvider.getUriForFile` at runtime, so a passing test here is a real assertion about
 * the manifest/`file_paths.xml` wiring, not just about this function's own code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordingShareTest {
    @Test
    fun `authority is the applicationId plus fileprovider`() {
        val context = ApplicationProvider.getApplicationContext<Application>()

        assertEquals("${context.packageName}.fileprovider", fileProviderAuthority(context))
    }

    // Deliberately ONE test method for both the intent shape and the raw uri resolution:
    // androidx.core.content.FileProvider caches its parsed `file_paths.xml` roots in a static
    // map keyed by authority, which — because each Robolectric test method gets its own fresh
    // sandboxed filesystem root but shares that static cache across methods in the same run —
    // makes a SECOND test touching this authority resolve against the FIRST test's now-stale
    // temp path and throw. Splitting this into two `@Test`s reproduced exactly that; production
    // has no such hazard (one process, one filesystem root for the app's whole lifetime).
    @Test
    fun `buildShareIntent is ACTION_SEND, text-csv, grants read, and the uri resolves`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val logsDirectory = logsDir(context)
        logsDirectory.mkdirs()
        val file = File(logsDirectory, "obdlog_2026-08-18_2207.csv")
        file.writeText("# sprinter-obd-gauge log v1\n")

        val intent = buildShareIntent(context, file)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("text/csv", intent.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val uri = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull(uri)
        assertEquals("content", uri!!.scheme)
        assertEquals("obdlog_2026-08-18_2207.csv", uri.lastPathSegment)
    }
}
