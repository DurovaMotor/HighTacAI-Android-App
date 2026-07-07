package com.example.deepchatdemo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.deepchatdemo.ui.DeepChatScreen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            DeepChatDemoApp(onSplashFinished = ::showSystemBars)
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
private fun DeepChatDemoApp(onSplashFinished: () -> Unit) {
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(SPLASH_DURATION_MILLIS)
        showSplash = false
        onSplashFinished()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (showSplash) {
                SplashScreen(modifier = Modifier.fillMaxSize())
            } else {
                DeepChatScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun SplashScreen(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.splash_hightac),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Crop
    )
}

private const val SPLASH_DURATION_MILLIS = 1400L
