package io.github.karlmuzen.kavora.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.karlmuzen.kavora.shell.ShizukuConnection

class HomeViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val shizuku = ShizukuConnection(application)

    val shizukuState = shizuku.state

    init {
        shizuku.start()
    }

    fun refreshShizuku() {
        shizuku.refresh()
    }

    fun requestShizukuPermission() {
        shizuku.requestPermission()
    }

    fun openShizuku() {
        shizuku.openShizuku()
    }

    override fun onCleared() {
        shizuku.close()
        super.onCleared()
    }
}
