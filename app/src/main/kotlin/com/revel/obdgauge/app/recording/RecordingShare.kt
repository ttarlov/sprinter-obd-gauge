package com.revel.obdgauge.app.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** `${applicationId}.fileprovider` — matches the `<provider>` authority in the manifest. */
fun fileProviderAuthority(context: Context): String = "${context.packageName}.fileprovider"

/**
 * OBD-70's Recordings-screen Share action: `ACTION_SEND` of [file] via a [FileProvider] uri
 * (API-23 safe, no storage permission needed) so any share target on the device — email, Drive,
 * Bluetooth, a file manager, whatever exists — can read it without this app granting blanket file
 * access. The caller wraps this in `Intent.createChooser(...)` before calling `startActivity`
 * (kept out of this function so it stays a plain, directly assertable value for
 * `RecordingShareTest`, per this issue's Robolectric AC).
 *
 * OBD-75: the MIME type is **`text/plain`, not `text/csv`**. On a bare device (the Garmin
 * Overlander, Android 6) nothing registers for `text/csv`, so the chooser came up EMPTY on Taras's
 * primary device. `text/plain` is what Bluetooth OPP and most text handlers register for, so it
 * surfaces Bluetooth (a real wireless path off the Garmin) + file managers, while the `.csv`
 * filename on the uri still tells the receiver what it is. Widen further to a fully-wildcard MIME
 * type only if a device still shows no targets.
 */
fun buildShareIntent(
    context: Context,
    file: File,
): Intent {
    val uri = FileProvider.getUriForFile(context, fileProviderAuthority(context), file)
    return Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
