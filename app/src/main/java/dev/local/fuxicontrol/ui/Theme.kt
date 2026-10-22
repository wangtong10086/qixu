package dev.local.fuxicontrol.ui

import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

@Composable
internal fun QixuTheme(window: Window, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    // Configuration changes include the system wallpaper color overlay, not just night mode.
    val colors = remember(context, configuration) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }
    SideEffect {
        window.setBackgroundDrawable(ColorDrawable(colors.background.toArgb()))
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
    }
    MaterialTheme(colorScheme = colors, content = content)
}
