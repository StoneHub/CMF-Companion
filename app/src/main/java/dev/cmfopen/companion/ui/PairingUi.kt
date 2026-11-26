package dev.cmfopen.companion.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.cmfopen.companion.CompanionApp
import dev.cmfopen.companion.WatchManager
import dev.cmfopen.companion.WatchManager.Phase
import dev.cmfopen.companion.ble.FoundWatch
import dev.cmfopen.companion.ble.WatchScanner
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * The Watch tab's top half: first-run setup, the connected watch card, and the tucked-away
 * recovery actions (reconnect, pair again, switch, forget). Styled after Nothing's look:
 * monochrome, monospace labels, dot-matrix shapes and one red accent.
 */

// -- shared bits ---------------------------------------------------------------

@Composable
internal fun MonoLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text.uppercase(),
        modifier,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
    )
}

@Composable
private fun Dot(color: Color, size: Int = 8) {
    Box(Modifier.size(size.dp).background(color, CircleShape))
}

@Composable
private fun FlatCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

// -- setup checks --------------------------------------------------------------

internal class SetupItem(val title: String, val help: String, val required: Boolean, val done: Boolean, val grant: () -> Unit)

private fun Context.granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

internal fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** What the app needs from Android, re-checked each time the screen comes back to the front. */
@Composable
internal fun rememberSetupItems(): List<SetupItem> {
    val ctx = LocalContext.current
    val app = CompanionApp.instance
    var granted by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted++
        app.watch.start()
    }
    // Notification access and battery settings come back without a result, so re-check on resume.
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    return remember(lifecycle, granted) {
        val bt = bluetoothPermissions()
        buildList {
            add(SetupItem("Nearby devices", "Bluetooth, to talk to the watch.", true, bt.all { ctx.granted(it) }) { launcher.launch(bt) })
            add(SetupItem("Notification access", "Forward texts and calls, and see what's playing.", true,
                androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)) {
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
            if (Build.VERSION.SDK_INT >= 33) {
                add(SetupItem("Show notifications", "Connection status and find my phone.", true,
                    ctx.granted(Manifest.permission.POST_NOTIFICATIONS)) { launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) })
            }
            add(SetupItem("Run in background", "Keeps Android from cutting the connection.", true,
                ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)) {
                @SuppressLint("BatteryLife")
                val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                ctx.startActivity(i)
            })
            add(SetupItem("Location", "Optional. Local weather; you can type a city instead.", false,
                ctx.granted(Manifest.permission.ACCESS_COARSE_LOCATION)) { launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)) })
        }
    }
}

@Composable
private fun SetupRow(item: SetupItem) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(item.title)
            Hint(item.help)
        }
        if (item.done) Icon(Icons.Filled.Check, contentDescription = "Allowed", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp))
        else TextButton(onClick = item.grant) { Text("Allow") }
    }
}

/** Shown under the watch card only while something required is still missing. */
@Composable
internal fun MissingSetupCard(items: List<SetupItem>) {
    val missing = items.filter { it.required && !it.done }
    if (missing.isEmpty()) return
    FlatCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Dot(MaterialTheme.colorScheme.primary)
            MonoLabel("Finish setup")
        }
        missing.forEach { SetupRow(it) }
    }
}

// -- first run -----------------------------------------------------------------

@Composable
internal fun Onboarding(items: List<SetupItem>, onPick: (BluetoothDevice, String) -> Unit) {
    val btOk = items.first().done
    val requiredOk = items.all { it.done || !it.required }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 4.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Dot(MaterialTheme.colorScheme.primary)
                MonoLabel("CMF Companion")
            }
            Text("Connect your watch", fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Medium)
            Hint("No account and no cloud. Three steps, about a minute.")
        }
        Step("01", "Close Nothing X", done = false, active = true) {
            Hint("The watch talks to one phone app at a time. Force-stop or uninstall Nothing X before you go on.")
        }
        Step("02", "Allow access", done = requiredOk, active = true) {
            items.forEach { SetupRow(it) }
        }
        Step("03", "Choose your watch", done = false, active = btOk) {
            if (btOk) WatchPicker(onPick)
            else Hint("Allow Nearby devices first.")
        }
    }
}

@Composable
private fun Step(number: String, title: String, done: Boolean, active: Boolean, content: @Composable ColumnScope.() -> Unit) {
    FlatCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                number,
                fontFamily = FontFamily.Monospace,
                color = if (active && !done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            if (done) Icon(Icons.Filled.Check, contentDescription = "Done", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

// -- watch picker --------------------------------------------------------------

/** Lists this phone's paired CMF watches, then scans for 20 s. Tap one to use it. */
@SuppressLint("MissingPermission")
@Composable
internal fun WatchPicker(onPick: (BluetoothDevice, String) -> Unit) {
    val ctx = LocalContext.current
    var round by remember { mutableIntStateOf(0) }
    val found = remember { mutableStateListOf<FoundWatch>() }
    var scanning by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(round) {
        scanning = true
        error = null
        // Bonded watches first: a watch busy with another phone app may not advertise.
        runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
                ?.filter { (it.name ?: "").startsWith("CMF Watch") }
                ?.forEach { d -> if (found.none { it.device.address == d.address }) found += FoundWatch(d, d.name, 0) }
        }
        withTimeoutOrNull(20_000) {
            WatchScanner.scan(ctx).catch { error = it.message }.collect { w ->
                if (found.none { it.device.address == w.device.address }) found += w
            }
        }
        scanning = false
    }
    Column {
        found.forEachIndexed { i, w ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable { onPick(w.device, w.name) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(w.name, fontWeight = FontWeight.Medium)
                    Hint(if (w.rssi == 0) "Paired with this phone" else signal(w.rssi))
                }
                Text("Connect", color = MaterialTheme.colorScheme.primary)
            }
        }
        if (scanning) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Hint("Searching nearby…")
            }
        } else {
            if (found.isEmpty()) Hint("No watch found. Keep it close, awake, and away from Nothing X.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { round++ }) { Text("Search again") }
        }
    }
}

private fun signal(rssi: Int) = when {
    rssi >= -60 -> "Right here"
    rssi >= -75 -> "Nearby"
    else -> "Weak signal"
}

// -- the watch card ------------------------------------------------------------

@Composable
internal fun WatchCard(state: WatchManager.State, name: String, address: String) {
    val watch = CompanionApp.instance.watch
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var switching by remember { mutableStateOf(false) }
    val busy = state.phase in setOf(Phase.CONNECTING, Phase.PAIRING, Phase.AWAITING_ACCEPT, Phase.WAITING_RETRY)

    FlatCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoLabel(name, color = MaterialTheme.colorScheme.onSurface)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Dot(
                        when {
                            state.phase == Phase.CONNECTED -> MaterialTheme.colorScheme.onSurface
                            busy || state.phase == Phase.NEEDS_PAIRING -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        },
                    )
                    Text(statusText(state), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Connection options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (state.phase != Phase.CONNECTED) DropdownMenuItem(text = { Text("Reconnect now") }, onClick = { menu = false; watch.start() })
                    DropdownMenuItem(text = { Text("Pair again") }, onClick = { menu = false; confirm = Confirm.PAIR })
                    DropdownMenuItem(text = { Text("Switch watch") }, onClick = { menu = false; switching = true })
                    DropdownMenuItem(text = { Text("Forget watch") }, onClick = { menu = false; confirm = Confirm.FORGET })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Hint(address) }, onClick = { menu = false }, enabled = false)
                }
            }
        }

        DotRing(
            battery = if (state.phase == Phase.CONNECTED) state.battery else null,
            charging = state.charging,
            spinning = busy,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp),
        )

        when (state.phase) {
            Phase.CONNECTED -> Row(verticalAlignment = Alignment.CenterVertically) {
                state.firmware?.let { MonoLabel("FW $it", Modifier.weight(1f)) } ?: Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { watch.findWatch() }) { Text("Find watch") }
            }
            Phase.AWAITING_ACCEPT -> Callout("Look at your watch and tap the check mark to finish pairing.")
            Phase.NEEDS_PAIRING -> {
                Callout("The watch no longer knows this phone. Pair again and accept on your wrist.")
                Button(onClick = { watch.pairAgain() }, Modifier.fillMaxWidth()) { Text("Pair again") }
            }
            Phase.WAITING_RETRY -> {
                Callout("Out of reach. Move the watch closer, or check that Nothing X isn't running.")
                OutlinedButton(onClick = { watch.start() }, Modifier.fillMaxWidth()) { Text("Try now") }
            }
            Phase.IDLE -> Button(onClick = { watch.start() }, Modifier.fillMaxWidth()) { Text("Connect") }
            Phase.CONNECTING, Phase.PAIRING -> {}
        }
        if (state.phase != Phase.CONNECTED) state.error?.let { Hint(it) }
    }

    when (confirm) {
        Confirm.PAIR -> ConfirmDialog(
            title = "Pair again?",
            body = "Makes a new key. The watch will ask you to accept. Only needed if it stopped recognising this phone.",
            action = "Pair again",
            onDismiss = { confirm = null },
        ) { watch.pairAgain() }
        Confirm.FORGET -> ConfirmDialog(
            title = "Forget $name?",
            body = "Disconnects and deletes the stored key. You'll set the watch up again from the start.",
            action = "Forget",
            onDismiss = { confirm = null },
        ) { watch.forget() }
        null -> {}
    }
    if (switching) {
        AlertDialog(
            onDismissRequest = { switching = false },
            confirmButton = { TextButton(onClick = { switching = false }) { Text("Cancel") } },
            title = { Text("Switch watch") },
            text = { WatchPicker { device, n -> switching = false; watch.select(device, n) } },
        )
    }
}

private enum class Confirm { PAIR, FORGET }

@Composable
private fun ConfirmDialog(title: String, body: String, action: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Callout(text: String) {
    Text(text, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
}

private fun statusText(s: WatchManager.State) = when (s.phase) {
    Phase.IDLE -> "Not connected"
    Phase.CONNECTING -> "Connecting"
    Phase.PAIRING -> "Pairing"
    Phase.AWAITING_ACCEPT -> "Waiting for you on the watch"
    Phase.CONNECTED -> if (s.charging) "Connected · charging" else "Connected"
    Phase.WAITING_RETRY -> "Out of reach" + (s.retryInS?.let { " · retry in ${it}s" } ?: "")
    Phase.NEEDS_PAIRING -> "Needs pairing"
}

/**
 * A ring of dots, Nothing glyph style. Lit dots show the battery level; while connecting, a red
 * dot runs around the ring. The animation only runs while [spinning], so an idle screen costs nothing.
 */
@Composable
private fun DotRing(battery: Int?, charging: Boolean, spinning: Boolean, modifier: Modifier = Modifier) {
    val count = 36
    val lit = battery?.let { (it.coerceIn(0, 100) * count / 100f).roundToInt() } ?: 0
    val on = MaterialTheme.colorScheme.onSurface
    val off = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    val head = if (spinning) {
        val t = rememberInfiniteTransition(label = "ring")
        t.animateFloat(0f, count.toFloat(), infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "head").value
    } else null

    Box(modifier.size(184.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(184.dp)) {
            val r = size.minDimension / 2 - 8.dp.toPx()
            val dot = 3.dp.toPx()
            for (i in 0 until count) {
                // Start at 12 o'clock and go clockwise.
                val a = (i / count.toFloat()) * 2 * PI - PI / 2
                val c = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
                val color = when {
                    head != null -> {
                        val behind = ((head - i) + count) % count
                        when {
                            behind < 1f -> accent
                            behind < 5f -> accent.copy(alpha = 0.6f - behind * 0.1f)
                            else -> off
                        }
                    }
                    i < lit -> on
                    else -> off
                }
                drawCircle(color, dot, c)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(battery?.toString() ?: "--", fontFamily = FontFamily.Monospace, fontSize = 48.sp, fontWeight = FontWeight.Light)
                if (battery != null) Text("%", fontFamily = FontFamily.Monospace, fontSize = 18.sp, modifier = Modifier.padding(bottom = 10.dp))
            }
            Spacer(Modifier.height(2.dp))
            MonoLabel(if (charging && battery != null) "Charging" else "Battery")
        }
    }
}
