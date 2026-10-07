package io.github.karlmuzen.kavora.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.karlmuzen.kavora.shell.ShizukuState

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
) {
    val state by viewModel.shizukuState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "Kavora",
            style = MaterialTheme.typography.headlineMedium,
        )

        ShizukuStatusCard(
            state = state,
            onRefresh = viewModel::refreshShizuku,
            onOpenShizuku = viewModel::openShizuku,
            onRequestPermission = viewModel::requestShizukuPermission,
        )
    }
}

@Composable
private fun ShizukuStatusCard(
    state: ShizukuState,
    onRefresh: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    val title: String
    val details: String
    val steps: String?

    when (state) {
        ShizukuState.NotInstalled -> {
            title = "Shizuku not installed"
            details = "Kavora needs Shizuku before privileged device operations are available."
            steps = "Checklist\n1. Install Shizuku.\n2. Start its service.\n3. Return to Kavora and tap Check again."
        }

        ShizukuState.Stopped -> {
            title = "Shizuku stopped"
            details = "The Shizuku binder is not available."
            steps = "Checklist\n1. Open Shizuku.\n2. Start the service.\n3. Return to Kavora and tap Check again."
        }

        ShizukuState.NoPermission -> {
            title = "Shizuku permission needed"
            details = "Shizuku is running, but Kavora has not been granted access."
            steps = "Checklist\n1. Tap Grant access.\n2. Approve Kavora in Shizuku.\n3. Return here."
        }

        ShizukuState.Binding -> {
            title = "Connecting to Shizuku"
            details = "Kavora is waiting for its privileged connection."
            steps = null
        }

        is ShizukuState.Ready -> {
            title = "Shizuku connected"
            details = when (state.uid) {
                0 -> "Ready as root (UID 0)."
                2000 -> "Ready as shell (UID 2000)."
                -1 -> "Ready; remote UID is unavailable."
                else -> "Ready with remote UID " + state.uid + "."
            }
            steps = null
        }

        ShizukuState.Died -> {
            title = "Shizuku connection lost"
            details = "The Shizuku binder died. Start Shizuku again and reconnect."
            steps = "Checklist\n1. Open Shizuku.\n2. Start the service.\n3. Return to Kavora and tap Check again."
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Shizuku",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
            )
            Text(text = details)

            steps?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            when (state) {
                ShizukuState.NoPermission -> {
                    Button(
                        onClick = onRequestPermission,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant access")
                    }
                }

                ShizukuState.Stopped,
                ShizukuState.Died,
                -> {
                    Button(
                        onClick = onOpenShizuku,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Open Shizuku")
                    }
                }

                else -> Unit
            }

            OutlinedButton(
                onClick = onRefresh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state is ShizukuState.Ready) "Refresh" else "Check again")
            }
        }
    }
}
