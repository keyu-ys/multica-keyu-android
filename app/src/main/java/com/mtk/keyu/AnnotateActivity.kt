package com.mtk.keyu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** 截图标注独立 Activity 入口（备用，正常通过 AnnotateOverlay 直接展示）。 */
class AnnotateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val shotPath = intent.getStringExtra("shot")
        setContent {
            BrandTheme {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF0D0E12)) {
                    AnnotateUi(shotPath) { finish() }
                }
            }
        }
    }
}
