package link.wyrd.command

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ProfileExpiryReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ProfileExpiry.ACTION_EXPIRE) return
        runCatching { ProfileExpiry.service(context) }
            .onFailure { Log.w(TAG, "profile end date check failed", it) }
    }

    companion object {
        private const val TAG = "ProfileExpiryReceiver"
    }
}
