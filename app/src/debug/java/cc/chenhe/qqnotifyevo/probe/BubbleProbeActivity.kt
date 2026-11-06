package cc.chenhe.qqnotifyevo.probe

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.content.LocusId
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.chenhe.qqnotifyevo.R
import cc.chenhe.qqnotifyevo.ui.theme.AppTheme

/** Debug-only, user-driven diagnostic UI. It never persists or logs captured material. */
class BubbleProbeActivity : ComponentActivity() {
    private val channelId = "bubble_direct_chat_probe"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        setContent { AppTheme { ProbeScreen() } }
    }

    @Composable
    private fun ProbeScreen() {
        val state by BubbleProbeStore.state.collectAsStateWithLifecycle()
        val callback = state.callbackIntent
        val target = callback?.let { resolveTarget(it, state.sourcePackage) }
        val callbackDetails = callback?.let(::describeWithoutValues).orEmpty()
        val launcher = state.sourcePackage?.let { packageManager.getLaunchIntentForPackage(it) }
        val notificationPermission = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("QQ 会话直达诊断测试版", style = MaterialTheme.typography.headlineSmall)
            Text("仅在内存保留最新 QQ/TIM 原通知 contentIntent；不读取正文或联系人，不取消/替换原通知，不自动学习。进程重启后全部失效。")

            OutlinedButton(
                onClick = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开通知使用权设置") }

            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    this@BubbleProbeActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Button(
                    onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("授予测试通知权限") }
            }

            Text(if (state.contentIntent == null) "捕获状态：尚未捕获" else "捕获状态：已捕获")
            Text("来源：${state.sourceLabel ?: "—"}")

            Button(
                enabled = state.contentIntent != null,
                onClick = { sendOriginal(state.contentIntent!!) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("手动发送原 contentIntent（将打开 QQ/TIM）") }

            state.sendResult?.let { Text(it) }
            if (callback == null) {
                Text("尚无 OnFinished callback Intent。发送后若系统返回，才会进行下一步检查。")
            } else {
                Text("Callback Intent（仅元数据）", style = MaterialTheme.typography.titleMedium)
                callbackDetails.forEach { Text(it) }
                Text("目标 Activity：${if (target?.info == null) "无法解析" else "已解析"}")
                Text("exported：${target?.info?.exported ?: "未知"}")
                Text("permission：${if (target?.info?.permission.isNullOrEmpty()) "无" else "需要权限"}")
                Text("来源包匹配：${target?.sameSource ?: false}")

                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    Text("系统气泡测试仅在 Android 11 及以上启用。")
                } else if (target?.publiclyCallable == true) {
                    Button(
                        onClick = { createDirectBubble(callback, target.info!!, state.sourceLabel ?: "QQ/TIM") },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("创建直接气泡测试") }
                } else {
                    Text("目标不是探针包可公开调用的 Activity（须 exported、无权限且属于来源 QQ/TIM），不会尝试直接气泡。")
                    if (launcher != null) {
                        OutlinedButton(
                            onClick = { launchWithTransferredExtras(launcher, callback) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("实验：将 callback extras 原样转交 QQ/TIM Launcher") }
                        Text("警告：仅手动实验；Launcher 可能忽略这些 extras，因此可能无效。不会枚举、打印或持久化值。")
                    }
                }
            }
        }
    }

    private fun sendOriginal(pendingIntent: PendingIntent) {
        try {
            pendingIntent.send(
                this,
                0,
                null,
                { _, intent, resultCode, _, _ -> BubbleProbeStore.callback(intent, resultCode) },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: PendingIntent.CanceledException) {
            BubbleProbeStore.sendFailed("发送失败：原 contentIntent 已失效")
        } catch (_: SecurityException) {
            BubbleProbeStore.sendFailed("发送失败：系统拒绝")
        }
    }

    private data class TargetCheck(
        val info: ActivityInfo?,
        val sameSource: Boolean,
        val publiclyCallable: Boolean,
    )

    private fun resolveTarget(intent: Intent, sourcePackage: String?): TargetCheck {
        val info = try {
            intent.component?.let { packageManager.getActivityInfo(it, 0) }
                ?: packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: SecurityException) {
            null
        }
        val sameSource = info != null && info.packageName == sourcePackage
        return TargetCheck(
            info = info,
            sameSource = sameSource,
            publiclyCallable = sameSource && info!!.exported && info.permission.isNullOrEmpty(),
        )
    }

    @Suppress("DEPRECATION")
    private fun describeWithoutValues(intent: Intent): List<String> {
        val lines = mutableListOf(
            "package：${intent.`package` ?: "—"}",
            "action：${intent.action ?: "—"}",
            "component：${intent.component?.flattenToShortString() ?: "—"}",
        )
        val extras = intent.extras
        val keys = try { extras?.keySet()?.sorted().orEmpty() } catch (_: RuntimeException) { emptyList() }
        if (keys.isEmpty()) {
            lines += "extras：无"
        } else {
            lines += "extra keys / types："
            keys.forEach { key ->
                val type = try { extras?.get(key)?.javaClass?.name ?: "null" } catch (_: RuntimeException) { "无法读取类型" }
                lines += "• $key：$type"
            }
        }
        return lines
    }

    private fun createDirectBubble(callback: Intent, info: ActivityInfo, sourceLabel: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        // Re-check immediately before creating an externally targeted PendingIntent.
        val check = resolveTarget(callback, info.packageName)
        if (!check.publiclyCallable) return
        val direct = Intent(callback).setComponent(ComponentName(info.packageName, info.name))
        val requestCode = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val openChat = PendingIntent.getActivity(
            this, requestCode, direct,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val conversationIcon = Icon.createWithResource(this, R.mipmap.ic_launcher)
        val person = Person.Builder()
            .setName(sourceLabel)
            .setKey("bubble-probe-${info.packageName}")
            .setIcon(conversationIcon)
            .setImportant(true)
            .build()
        val shortcutId = "bubble-probe-${info.packageName}"
        val shortcut = ShortcutInfo.Builder(this, shortcutId)
            .setShortLabel("$sourceLabel 直接聊天测试")
            .setLongLived(true)
            .setPerson(person)
            .setIcon(conversationIcon)
            .setCategories(setOf("android.shortcut.conversation"))
            .setLocusId(LocusId(shortcutId))
            .setIntent(direct)
            .build()
        getSystemService(ShortcutManager::class.java).pushDynamicShortcut(shortcut)
        // Android 11+ conversation bubbles are keyed by the long-lived shortcut.
        // This keeps the QQ activity in the system bubble task instead of using a
        // cross-package PendingIntent bubble, which Android 17 rejects.
        val bubble = Notification.BubbleMetadata.Builder(shortcutId).build()
        val style = Notification.MessagingStyle(person)
            .addMessage("点气泡验证是否进入原会话", System.currentTimeMillis(), person)
        val notification = Notification.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notify_qq)
            .setContentTitle("$sourceLabel 直接聊天气泡测试")
            .setContentText("仅由你手动创建；点气泡验证是否进入原会话")
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setStyle(style)
            .addPerson(person)
            .setShortcutId(shortcutId)
            .setLocusId(LocusId(shortcutId))
            .setContentIntent(openChat)
            .setBubbleMetadata(bubble)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(41001, notification)
    }

    private fun launchWithTransferredExtras(launcher: Intent, callback: Intent) {
        // Do not inspect the values. Bundle is copied only into this process-local launch Intent.
        val experimental = Intent(launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        callback.extras?.let { experimental.replaceExtras(Bundle(it)) }
        try {
            startActivity(experimental)
        } catch (_: RuntimeException) {
            BubbleProbeStore.sendFailed("Launcher 实验启动失败")
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            channelId, "会话直达气泡探针", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "仅用于用户手动发起的直接气泡测试"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowBubbles(true)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
