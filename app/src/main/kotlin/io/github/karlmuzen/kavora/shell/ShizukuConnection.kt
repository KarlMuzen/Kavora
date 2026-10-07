package io.github.karlmuzen.kavora.shell

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

class ShizukuConnection(
    context: Context,
) : AutoCloseable {

    companion object {
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 1001
    }

    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    private val _state = MutableStateFlow<ShizukuState>(detectInstalledState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private var started = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        refresh()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _state.value = if (isShizukuInstalled()) {
            ShizukuState.Died
        } else {
            ShizukuState.NotInstalled
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
        if (requestCode == REQUEST_CODE) {
            refresh()
        }
    }

    fun start() {
        if (started) return
        started = true

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(permissionListener)

        refresh()
    }

    fun refresh() {
        if (!isShizukuInstalled()) {
            _state.value = ShizukuState.NotInstalled
            return
        }

        val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!binderAlive) {
            _state.value = ShizukuState.Stopped
            return
        }

        val granted = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        val uid = if (granted) {
            runCatching { Shizuku.getUid() }.getOrDefault(-1)
        } else {
            -1
        }

        _state.value = ShizukuState.derive(
            installed = true,
            binderAlive = binderAlive,
            permissionGranted = granted,
            uid = uid,
        )
    }

    fun requestPermission() {
        if (_state.value !is ShizukuState.NoPermission) return
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { refresh() }
    }

    fun openShizuku() {
        val launchIntent = packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        val intent = launchIntent ?: Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$SHIZUKU_PACKAGE"),
        )
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun detectInstalledState(): ShizukuState =
        if (isShizukuInstalled()) ShizukuState.Stopped else ShizukuState.NotInstalled

    private fun isShizukuInstalled(): Boolean =
        runCatching {
            packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

    override fun close() {
        if (!started) return
        started = false

        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
