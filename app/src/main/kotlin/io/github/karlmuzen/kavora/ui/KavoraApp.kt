package io.github.karlmuzen.kavora.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun KavoraApp() {
    val viewModel: HomeViewModel = viewModel()
    HomeScreen(viewModel = viewModel)
}
