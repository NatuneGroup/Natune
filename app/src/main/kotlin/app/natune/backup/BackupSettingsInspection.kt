package app.natune.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.natune.R
import app.natune.db.InternalDatabase
import app.natune.extensions.zipInputStream
import app.natune.utils.reportException
import app.natune.viewmodels.BackupCategory
import app.natune.viewmodels.BackupRestoreViewModel
import app.natune.viewmodels.BackupValidationResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import app.natune.shared.data.persistence.LegacyDataStorePreferences

/** Inspect actual preference names, since settings.xml alone no longer implies an account section. */
internal fun inspectBackupSettings(name: String, input: InputStream): Set<BackupArchiveCategory> {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= AndroidRestoreLimits.MAX_SETTINGS_BYTES) { "Settings payload exceeds limit" }
        output.write(buffer, 0, count)
    }
    val bytes = output.toByteArray()
    val names = if (name == BackupArchiveRepository.SETTINGS_XML_FILENAME) {
        readLegacySettings(bytes.inputStream()).keyNames
    } else {
        LegacyDataStorePreferences.read(bytes).keys.map { it.name }.toSet()
    }
    return categoriesOf(names)
}

private fun categoriesOf(names: Collection<String>): Set<BackupArchiveCategory> =
    buildSet {
        if (names.any { BackupPreferenceSelection.includes(it, settings = true, account = false) }) add(BackupArchiveCategory.SETTINGS)
        if (names.any { BackupPreferenceSelection.includes(it, settings = false, account = true) }) add(BackupArchiveCategory.ACCOUNT)
    }

internal suspend fun validateBackupArchive(
    context: Context,
    uri: Uri,
): BackupValidationResult =
    withContext(Dispatchers.IO) {
        try {
            val stream =
                context.applicationContext.contentResolver.openInputStream(uri)
            if (stream == null) {
                return@withContext BackupValidationResult(
                    isValid = false,
                    availableCategories = emptySet(),
                    errorMessage = context.getString(R.string.restore_file_not_found),
                )
            }
            stream.use { inputStream ->
                val zipStream = inputStream.zipInputStream()
                val entryNames = mutableSetOf<String>()
                val preferenceCategories = mutableSetOf<BackupArchiveCategory>()
                var manifest: ByteArray? = null
                var settingsJson: ByteArray? = null
                zipStream.use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entryNames.add(entry.name)
                        when (entry.name) {
                            BackupArchiveRepository.SETTINGS_XML_FILENAME, BackupRestoreViewModel.SETTINGS_FILENAME ->
                                preferenceCategories += inspectBackupSettings(entry.name, zip)
                            BackupArchiveLayout.MANIFEST -> manifest = BackupArchiveLayout.readManifest(zip)
                            // The manifest may follow settings.json, so the archive's origin is known only at the end.
                            BackupArchiveLayout.SETTINGS_JSON ->
                                settingsJson = BackupArchiveLayout.readBounded(zip, AndroidRestoreLimits.MAX_SETTINGS_BYTES)
                        }
                        entry = zip.nextEntry
                    }
                }
                val foreign = BackupArchiveLayout.isForeign(manifest, settingsJson != null)
                settingsJson?.let { bytes ->
                    preferenceCategories += categoriesOf(BackupArchiveLayout.readSettings(bytes, foreign).settings.keys.map { it.name })
                }
                if (entryNames.isEmpty()) {
                    return@withContext BackupValidationResult(
                        isValid = false,
                        availableCategories = emptySet(),
                        errorMessage = context.getString(R.string.restore_invalid_file),
                    )
                }
                val categories = mutableSetOf<BackupCategory>()
                // Another app's song.db is its own schema, so its library is never offered.
                val hasDb = !foreign && entryNames.any { it.startsWith(InternalDatabase.DB_NAME) }
                preferenceCategories.forEach { categories.add(BackupCategory.valueOf(it.name)) }
                if (hasDb) {
                    categories.add(BackupCategory.LIBRARY)
                }
                if (categories.isEmpty()) {
                    return@withContext BackupValidationResult(
                        isValid = false,
                        availableCategories = emptySet(),
                        errorMessage = context.getString(R.string.restore_missing_content),
                    )
                }
                BackupValidationResult(
                    isValid = true,
                    availableCategories = categories,
                    errorMessage = null,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            reportException(e)
            BackupValidationResult(
                isValid = false,
                availableCategories = emptySet(),
                errorMessage = context.getString(R.string.restore_corrupted),
            )
        }
    }
