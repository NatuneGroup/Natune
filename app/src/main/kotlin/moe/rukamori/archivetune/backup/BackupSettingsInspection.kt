package moe.rukamori.archivetune.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.db.InternalDatabase
import moe.rukamori.archivetune.extensions.zipInputStream
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.viewmodels.BackupCategory
import moe.rukamori.archivetune.viewmodels.BackupRestoreViewModel
import moe.rukamori.archivetune.viewmodels.BackupValidationResult
import java.io.ByteArrayOutputStream
import java.io.InputStream
import moe.rukamori.archivetune.shared.data.persistence.LegacyDataStorePreferences

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
    return buildSet {
        if (names.any { BackupPreferenceSelection.includes(it, settings = true, account = false) }) add(BackupArchiveCategory.SETTINGS)
        if (names.any { BackupPreferenceSelection.includes(it, settings = false, account = true) }) add(BackupArchiveCategory.ACCOUNT)
    }
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
                zipStream.use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        entryNames.add(entry.name)
                        if (entry.name == BackupArchiveRepository.SETTINGS_XML_FILENAME || entry.name == BackupRestoreViewModel.SETTINGS_FILENAME) {
                            preferenceCategories += inspectBackupSettings(entry.name, zip)
                        }
                        entry = zip.nextEntry
                    }
                }
                if (entryNames.isEmpty()) {
                    return@withContext BackupValidationResult(
                        isValid = false,
                        availableCategories = emptySet(),
                        errorMessage = context.getString(R.string.restore_invalid_file),
                    )
                }
                val categories = mutableSetOf<BackupCategory>()
                val hasDb = entryNames.any { it.startsWith(InternalDatabase.DB_NAME) }
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
