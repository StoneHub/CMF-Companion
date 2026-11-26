package dev.cmfopen.companion.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import dev.cmfopen.companion.CompanionApp
import dev.cmfopen.companion.WatchService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { CompanionTheme { Root() } }
    }

    override fun onResume() {
        super.onResume()
        val app = CompanionApp.instance
        if (app.prefs.autoConnect) WatchService.start(this)
        app.watch.media.start()
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    WATCH("Watch", Icons.Filled.Watch),
    ALERTS("Alerts", Icons.Filled.Notifications),
    APPS("Apps", Icons.Filled.Apps),
    HEALTH("Health", Icons.Filled.Favorite),
    LOG("Log", Icons.Filled.Terminal),
}

@Composable
private fun Root() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.WATCH -> WatchScreen(m)
            Tab.ALERTS -> AlertsScreen(m)
            Tab.APPS -> AppsScreen(m)
            Tab.HEALTH -> HealthScreen(m)
            Tab.LOG -> LogScreen(m)
        }
    }
}

// Nothing red, as in the README banner. One accent; everything else stays monochrome.
private val Accent = Color(0xFFD71921)

@Composable
fun CompanionTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Accent, onPrimary = Color.White, secondary = Accent,
            background = Color.Black, surface = Color(0xFF0B0B0B), surfaceContainer = Color(0xFF111111),
            outlineVariant = Color(0xFF2A2A2A),
        )
    } else {
        lightColorScheme(
            primary = Accent, onPrimary = Color.White, secondary = Accent,
            background = Color(0xFFF5F5F5), surface = Color.White, surfaceContainer = Color(0xFFF5F5F5),
            outlineVariant = Color(0xFFDADADA),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
