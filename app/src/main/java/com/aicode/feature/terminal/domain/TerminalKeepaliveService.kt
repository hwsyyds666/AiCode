package com.aicode.feature.terminal.domain

import android.app.ActivityManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aicode.MainActivity
import com.aicode.core.util.FileLogger
import com.aicode.R
import com.aicode.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TerminalKeepaliveService : Service() {
    private val binder = LocalBinder()
    private var sessionCount = 0

    /** 用户在设置页开启的常驻保活：为 true 时即便没有后台会话也保持前台通知。 */
    private var persistent = false

    /**
     * 服务级 WakeLock：有活跃会话时持有 PARTIAL_WAKE_LOCK，确保切后台后 CPU 不休眠、
     * SSE/网络 I/O 不会被系统挂起。所有会话结束或常驻关闭时释放。
     */
    private var wakeLock: PowerManager.WakeLock? = null

    @Inject
    lateinit var keepaliveSettings: KeepaliveSettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    inner class LocalBinder : Binder() {
        fun getService(): TerminalKeepaliveService = this@TerminalKeepaliveService
    }

    override fun onCreate() {
        super.onCreate()
        FileLogger.i(TAG, "Service created")
        // 进程被系统回收后 START_STICKY 重建：persistent 是内存态会丢失。
        // 从持久化开关恢复常驻前台，否则服务空转后 stopSelf，保活形同虚设。
        serviceScope.launch {
            if (keepaliveSettings.isEnabled()) {
                persistent = true
                ensureForeground()
                FileLogger.i(TAG, "Restored persistent keepalive after restart")
            }
        }
    }

    override fun onDestroy() {
        releaseServiceWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 用户从最近任务划掉 app = 明确不要后台运行：停掉前台服务并取消 WorkManager 周期兜底，
        // 否则进程被杀后服务会被 START_STICKY 或 KeepaliveWorker 重新拉起并弹通知。
        // 开关本身保持不变，下次打开 app 时由 MainActivity / AIEditorApp 自动恢复。
        KeepaliveWorker.cancel(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Task removed, keepalive stopped")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_SESSION -> {
                sessionCount++
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Session started, count=$sessionCount")
            }
            ACTION_STOP_SESSION -> {
                sessionCount = (sessionCount - 1).coerceAtLeast(0)
                if (sessionCount == 0 && !persistent) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "All sessions ended, stopping service")
                } else {
                    ensureForeground()
                    if (sessionCount == 0) releaseServiceWakeLock()
                    FileLogger.i(TAG, "Session ended, count=$sessionCount, persistent=$persistent")
                }
            }
            ACTION_ENABLE_PERSISTENT -> {
                persistent = true
                ensureForeground()
                acquireServiceWakeLock()
                FileLogger.i(TAG, "Persistent keepalive enabled")
            }
            ACTION_DISABLE_PERSISTENT -> {
                persistent = false
                if (sessionCount == 0) {
                    releaseServiceWakeLock()
                    stopSelf(startId)
                    FileLogger.i(TAG, "Persistent keepalive disabled, no sessions, stopping service")
                } else {
                    ensureForeground()
                    FileLogger.i(TAG, "Persistent keepalive disabled, sessions still running count=$sessionCount")
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /**
     * 进入前台并刷新通知；文案随「常驻保活 / 后台会话」组合变化。
     *
     * startForeground 在 Android 12+ 从后台启动前台服务时可能抛
     * ForegroundServiceStartNotAllowedException，此处捕获仅记录——常驻通知本次未能展示，
     * 不应让进程崩溃（设置页的开关仍是开启态，下次前台时由 [com.aicode.MainActivity] 恢复）。
     */
    private fun ensureForeground() {
        val text = when {
            sessionCount > 0 && persistent ->
                getString(R.string.notification_keepalive_persistent_sessions, sessionCount)
            sessionCount > 0 ->
                getString(R.string.notification_keepalive_running_sessions, sessionCount)
            else -> getString(R.string.notification_keepalive_enabled)
        }
        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()

        runCatching { startForeground(NOTIFICATION_ID, notification) }
            .onFailure { FileLogger.e(TAG, "startForeground failed", it) }
    }

    /**
     * 获取服务级 PARTIAL_WAKE_LOCK：切后台后保持 CPU 唤醒，防止系统挂起网络 I/O。
     * 仅在有活跃会话时持有，会话全部结束时释放。
     */
    private fun acquireServiceWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AiCode:KeepaliveService").also {
            runCatching { it.acquire(WAKELOCK_TIMEOUT_MS) }
                .onFailure { FileLogger.e(TAG, "acquire service wakeLock failed", it) }
        }
    }

    /** 释放服务级 WakeLock。 */
    private fun releaseServiceWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }
                .onFailure { FileLogger.e(TAG, "release service wakeLock failed", it) }
        }
        wakeLock = null
    }

    companion object {
        private const val TAG = "TerminalKeepaliveService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "terminal_service"
        /** 服务级 WakeLock 超时保险：AI 流式生成可能持续很久，给足 60 分钟。 */
        private const val WAKELOCK_TIMEOUT_MS = 60 * 60 * 1000L
        const val ACTION_START_SESSION = "com.aicode.action.START_SESSION"
        const val ACTION_STOP_SESSION = "com.aicode.action.STOP_SESSION"
        const val ACTION_ENABLE_PERSISTENT = "com.aicode.action.ENABLE_PERSISTENT"
        const val ACTION_DISABLE_PERSISTENT = "com.aicode.action.DISABLE_PERSISTENT"

        /**
         * 注册一个活跃会话并拉起前台服务（用于 AI 流式生成 / 后台终端）。
         * 使用 startForegroundService 而非 startService，确保 Android 8+ 从后台恢复时也能正确启动。
         */
        fun startSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_START_SESSION
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 结束一个活跃会话。会话计数归零且无常驻保活时自动停止服务。 */
        fun stopSession(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_STOP_SESSION
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "stopSession startService failed", it) }
        }

        /** 开启常驻保活（幂等）。使用 startForegroundService 确保后台也能拉起。 */
        fun enablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_ENABLE_PERSISTENT
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** 关闭常驻保活（幂等）。仅在确曾开启过时调用，避免为关闭而凭空拉起 Service。 */
        fun disablePersistent(context: Context) {
            val intent = Intent(context, TerminalKeepaliveService::class.java).apply {
                action = ACTION_DISABLE_PERSISTENT
            }
            runCatching { context.startService(intent) }
                .onFailure { FileLogger.e(TAG, "disablePersistent startService failed", it) }
        }

        /** 判断保活服务当前是否在运行。WorkManager 兜底拉起前先探测，避免反复 startService。 */
        @Suppress("DEPRECATION")
        fun isRunning(context: Context): Boolean {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val serviceName = TerminalKeepaliveService::class.java.name
            return am.getRunningServices(Int.MAX_VALUE)
                .any { it.service.packageName == context.packageName && it.service.className == serviceName }
        }
    }
}
