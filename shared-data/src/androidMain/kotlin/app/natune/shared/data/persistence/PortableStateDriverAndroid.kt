package app.natune.shared.data.persistence

import androidx.sqlite.SQLiteDriver
import androidx.sqlite.driver.AndroidSQLiteDriver

actual fun portableStateDriver(): SQLiteDriver = AndroidSQLiteDriver()
