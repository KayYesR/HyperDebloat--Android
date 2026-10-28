package io.github.kayyesr.hyperdebloat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.kayyesr.hyperdebloat.ui.App
import io.github.kayyesr.hyperdebloat.ui.MainViewModel

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { App(vm) }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from Settings: wireless debugging may have just been switched on or paired.
        vm.tryConnect()
    }
}
