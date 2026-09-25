package com.doheon.kostolany

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.doheon.kostolany.ui.App
import com.doheon.kostolany.ui.KostolanyTheme
import com.doheon.kostolany.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KostolanyTheme { App(vm) }
        }
    }
}
