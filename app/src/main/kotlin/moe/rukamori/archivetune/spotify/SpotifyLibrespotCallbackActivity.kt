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
        intent?.data?.let(SpotifyLibrespotAuthCoordinator::emit)
    }
}
