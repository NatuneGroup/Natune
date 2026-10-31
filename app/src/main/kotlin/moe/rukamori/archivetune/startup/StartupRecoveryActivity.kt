package moe.rukamori.archivetune.startup

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.IOException
import java.util.concurrent.Executors
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R

/** Launcher gate and recovery UI share no Hilt, Compose, preferences, providers or native runtime. */
class StartupRecoveryActivity : Activity() {
    private val reader = Executors.newSingleThreadExecutor()
    private var generation = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPreviousLaunch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkPreviousLaunch()
    }

    private fun checkPreviousLaunch() {
        val current = ++generation
        setContentView(TextView(this).apply {
            text = getString(R.string.startup_recovery_loading)
            setPadding(24, 24, 24, 24)
        })
        reader.execute {
            val result = runCatching { previousLaunchReport(this) }
            runOnUiThread {
                if (current != generation || isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { report -> if (report == null) openMain(restart = false) else showReport(report) },
                    onFailure = { showReport(StartupReport(getString(R.string.startup_report_unavailable), true, 0L)) },
                )
            }
        }
    }

    private fun showReport(report: StartupReport) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        column.addView(TextView(this).apply {
            text = getString(if (report.safeMode) R.string.startup_safe_mode_title else R.string.startup_report_title)
            textSize = 22f
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.startup_recovery_privacy)
            setPadding(0, padding, 0, padding)
        })
        column.addView(ScrollView(this).apply {
            addView(TextView(this@StartupRecoveryActivity).apply {
                text = report.text
                textSize = 12f
                setTextIsSelectable(true)
            })
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        fun button(label: Int, action: () -> Unit) = Button(this).apply {
            setText(label)
            setOnClickListener { runCatching(action).onFailure { showActionFailure() } }
        }
        val actions = LinearLayout(this)
        actions.addView(button(R.string.copy) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(getString(R.string.startup_report_title), report.text))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(button(R.string.share) {
            // Text sharing avoids a FileProvider call, which would start the broken main process.
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report.text)
            }, getString(R.string.share_logs)))
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(actions)
        column.addView(button(R.string.startup_continue_normally) {
            val store = StartupRecovery.store(this)
            if (report.safeMode) {
                liveMainProcessId(this)?.let { Process.killProcess(it) }
            }
            if (!store.clearPending()) throw IOException("Startup marker could not be cleared")
            runCatching { store.acknowledgeThrough(report.reviewedThroughMs) }
            openMain(restart = report.safeMode)
        })
        column.addView(button(R.string.close) { finishAndRemoveTask() })
        setContentView(column)
    }

    private fun openMain(restart: Boolean) {
        runCatching {
            startActivity(Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (restart) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            })
            finish()
        }.onFailure {
            showActionFailure()
        }
    }

    private fun showActionFailure() {
        Toast.makeText(this, R.string.startup_recovery_action_failed, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        generation++
        reader.shutdownNow()
        super.onDestroy()
    }
}
