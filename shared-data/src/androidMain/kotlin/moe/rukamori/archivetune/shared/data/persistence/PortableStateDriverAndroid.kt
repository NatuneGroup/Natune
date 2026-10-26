package moe.rukamori.archivetune.shared.data.persistence

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver

actual fun portableStateDriver(): SQLiteDriver = AndroidSQLiteDriver()
