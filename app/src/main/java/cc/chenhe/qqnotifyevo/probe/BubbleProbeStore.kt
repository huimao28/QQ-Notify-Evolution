package cc.chenhe.qqnotifyevo.probe

import android.app.PendingIntent
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Debug probe state. It is intentionally process-memory-only: no logs, files, preferences or uploads.
 * Never place notification text, people/contact data, or extra values in this state.
 */
object BubbleProbeStore {
    private val allowedPackages = mapOf(
        "com.tencent.mobileqq" to "QQ",
        "com.tencent.tim" to "TIM",
    )

    data class Snapshot(
        val sourcePackage: String? = null,
        val sourceLabel: String? = null,
        val contentIntent: PendingIntent? = null,
        val callbackIntent: Intent? = null,
        val sendResult: String? = null,
    )

    private val mutable = MutableStateFlow(Snapshot())
    val state = mutable.asStateFlow()

    fun capture(sourcePackage: String, contentIntent: PendingIntent?) {
        val label = allowedPackages[sourcePackage] ?: return
        if (contentIntent == null) return
        mutable.value = Snapshot(
            sourcePackage = sourcePackage,
            sourceLabel = label,
            contentIntent = contentIntent,
        )
    }

    fun callback(intent: Intent?, resultCode: Int) {
        val old = mutable.value
        // Intent copy is process-local only. Never stringify it because toString can expose data URIs.
        mutable.value = old.copy(
            callbackIntent = intent?.let(::Intent),
            sendResult = "OnFinished 已返回",
        )
    }

    fun sendFailed(kind: String) {
        mutable.value = mutable.value.copy(sendResult = kind, callbackIntent = null)
    }
}
