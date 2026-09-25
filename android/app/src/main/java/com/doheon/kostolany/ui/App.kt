package com.doheon.kostolany.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable

@Composable
fun App(vm: MainViewModel) {
    Surface(color = Beige) {
        AnimatedContent(
            vm.screen,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            contentKey = { it::class },  // 진행 메시지가 바뀔 때마다 깜빡이지 않게
            label = "screen",
        ) { screen ->
            when (screen) {
                is Screen.Home -> HomeScreen(screen.snapshot, vm.risk, { vm.risk = it }, vm::showStored, vm::updateAndShow)
                is Screen.Updating -> UpdatingScreen(screen.progress)
                is Screen.Result -> ResultScreen(screen.snapshot, screen.update, vm.risk, { vm.risk = it }, vm::goHome)
            }
        }
    }
}
