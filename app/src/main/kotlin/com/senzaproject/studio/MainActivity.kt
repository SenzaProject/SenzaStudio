package com.senzaproject.studio

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.senzaproject.studio.core.design.SenzaTheme
import com.senzaproject.studio.feature.settings.AppInfo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        val info = AppInfo(
            versionName = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown",
            androidApi = Build.VERSION.SDK_INT,
        )
        setContent {
            SenzaTheme { SenzaStudioRoot(info) }
        }
    }

    // Battery: the spectrum FFT and the 25 Hz meter updates only run while the UI is on screen.
    override fun onStart() {
        super.onStart()
        (application as SenzaStudioApplication).container.engine.setUiVisible(true)
    }

    override fun onStop() {
        (application as SenzaStudioApplication).container.engine.setUiVisible(false)
        super.onStop()
    }
}
