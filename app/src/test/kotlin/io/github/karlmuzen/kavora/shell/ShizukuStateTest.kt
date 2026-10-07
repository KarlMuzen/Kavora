package io.github.karlmuzen.kavora.shell

import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuStateTest {

    @Test
    fun notInstalledTakesPriority() {
        assertEquals(
            ShizukuState.NotInstalled,
            ShizukuState.derive(
                installed = false,
                binderAlive = false,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun stoppedIsReportedWhenBinderIsDead() {
        assertEquals(
            ShizukuState.Stopped,
            ShizukuState.derive(
                installed = true,
                binderAlive = false,
                permissionGranted = true,
            ),
        )
    }

    @Test
    fun missingPermissionIsReported() {
        assertEquals(
            ShizukuState.NoPermission,
            ShizukuState.derive(
                installed = true,
                binderAlive = true,
                permissionGranted = false,
            ),
        )
    }

    @Test
    fun readyPreservesUid() {
        assertEquals(
            ShizukuState.Ready(2000),
            ShizukuState.derive(
                installed = true,
                binderAlive = true,
                permissionGranted = true,
                uid = 2000,
            ),
        )
    }
}
