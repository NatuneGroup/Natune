package moe.rukamori.archivetune.spotify

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives the `natune://spotify-callback` OAuth redirect and hands it to the pending flow. */
class SpotifyLibrespotCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
        finish()
    }

    private fun handleIntent(intent: Intent?) {
        // Only a VIEW intent carrying our hierarchical callback URI is a real redirect; anything
        // else (malformed extras, spoofed scheme/host) is dropped before it can reach a pending
        // session. Query parsing is guarded — a hostile URI must never crash the activity.
        val uri =
            runCatching {
                intent
                    ?.takeIf { it.action == Intent.ACTION_VIEW }
                    ?.data
                    ?.takeIf { it.isHierarchical }
                    ?.takeIf { it.scheme == "natune" && it.host == "spotify-callback" }
            }.getOrNull() ?: return
        SpotifyLibrespotAuthCoordinator.emit(uri)
    }
}
