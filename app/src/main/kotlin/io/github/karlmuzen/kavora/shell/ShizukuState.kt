package io.github.karlmuzen.kavora.shell

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object Stopped : ShizukuState
    data object NoPermission : ShizukuState
    data object Binding : ShizukuState
    data class Ready(val uid: Int) : ShizukuState
    data object Died : ShizukuState

    companion object {
        fun derive(
            installed: Boolean,
            binderAlive: Boolean,
            permissionGranted: Boolean,
            uid: Int = -1,
        ): ShizukuState = when {
            !installed -> NotInstalled
            !binderAlive -> Stopped
            !permissionGranted -> NoPermission
            else -> Ready(uid)
        }
    }
}
