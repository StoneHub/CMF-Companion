package dev.cmfopen.companion.ui

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cmfopen.companion.CompanionApp
import dev.cmfopen.companion.WatchManager.Phase
import dev.cmfopen.companion.media.SpeakerExperiment
import dev.cmfopen.protocol.Payloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WatchScreen(modifier: Modifier) {
    val app = CompanionApp.instance
    val prefs = app.prefs
    val watch = app.watch
    val state by watch.state.collectAsStateWithLifecycle()
    val version by prefs.version.collectAsStateWithLifecycle()
    val setup = rememberSetupItems()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Prefs aren't Compose state; reading `version` recomposes when a watch is chosen or forgotten.
        val address = if (version >= 0) prefs.deviceAddress else null
        if (address == null) {
            Onboarding(setup) { device, name -> watch.select(device, name) }
        } else {
            WatchCard(state, prefs.deviceName ?: "CMF Watch", address)
            MissingSetupCard(setup)
            MusicSection(state.lastButton)
            WeatherSection()
            ContactsSection()
            FacesSection(state.phase == Phase.CONNECTED)
            SpeakerSection()
        }
    }
}

// -- music ---------------------------------------------------------------------

@Composable
private fun MusicSection(lastButton: String?) {
    val prefs = CompanionApp.instance.prefs
    Section("Music") {
        ToggleRow("Show what's playing on the watch", prefs.mirrorMedia, "YouTube, podcasts, Spotify… The watch's music controls play, skip and change volume.") {
            prefs.mirrorMedia = it
            if (it) CompanionApp.instance.watch.media.push(force = true)
        }
        Hint("Last button from the watch: ${lastButton ?: "none yet"}")
        Hint("If a watch button does the wrong thing, press it and check the code here or in the Log tab.")
    }
}

// -- contacts ------------------------------------------------------------------

@Composable
private fun ContactsSection() {
    val app = CompanionApp.instance
    val prefs = app.prefs
    val sync = app.watch.contacts
    val scope = rememberCoroutineScope()
    val version by prefs.version.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        prefs.contactsEnabled = ok
        note = if (ok) null else "Contacts permission denied"
        if (ok) sync.syncSoon()
    }
    Section("Contacts") {
        if (version < 0) return@Section // recompose on settings changes
        ToggleRow("Send favourite contacts", prefs.contactsEnabled && sync.permitted, "Your starred contacts with a number, up to 20.") { on ->
            if (on && !sync.permitted) launcher.launch(Manifest.permission.READ_CONTACTS)
            else {
                prefs.contactsEnabled = on
                if (on) sync.syncSoon()
            }
        }
        if (prefs.contactsEnabled && sync.permitted) {
            OutlinedButton(onClick = {
                scope.launch {
                    note = runCatching { "Sent ${sync.syncOnce()} contacts" }.getOrElse { "Failed: ${it.message}" }
                }
            }) { Text("Send now") }
        }
        note?.let { Hint(it) }
    }
}

// -- weather -------------------------------------------------------------------

@Composable
private fun WeatherSection() {
    val app = CompanionApp.instance
    val prefs = app.prefs
    val scope = rememberCoroutineScope()
    var city by remember { mutableStateOf(prefs.weatherCity) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf(app.watch.weather.last?.let { "${it.place}: ${it.tempC}°C, ${it.description}" }) }
    val ctx = LocalContext.current
    var locationOk by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        locationOk = ok
        if (ok) scope.launch { runCatching { app.watch.weather.syncOnce() } }
    }
    Section("Weather") {
        ToggleRow("Send weather to the watch", prefs.weatherEnabled, "Now, hourly and 7-day forecast from Open-Meteo, refreshed every 30 minutes while connected.") { prefs.weatherEnabled = it }
        ToggleRow("Fahrenheit and miles", prefs.imperialUnits, "Changes how the watch shows temperature and distance.") {
            prefs.imperialUnits = it
            app.watch.pushUnits()
        }
        OutlinedTextField(
            value = city,
            onValueChange = { city = it },
            label = { Text("City (blank = use location)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (city.isBlank() && !locationOk) {
            TextButton(onClick = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) { Text("Use my location") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !busy, onClick = {
                prefs.weatherCity = city
                busy = true
                scope.launch {
                    note = runCatching {
                        app.watch.weather.syncOnce()
                        app.watch.weather.last?.let { "${it.place}: ${it.tempC}°C, ${it.description}" } ?: "No location yet"
                    }.getOrElse { "Failed: ${it.message}" }
                    busy = false
                }
            }) { Text("Save and send now") }
            if (busy) CircularProgressIndicator(strokeWidth = 2.dp)
        }
        note?.let { Hint(it) }
    }
}

// -- faces ---------------------------------------------------------------------

@Composable
private fun FacesSection(connected: Boolean) {
    val ctx = LocalContext.current
    val watch = CompanionApp.instance.watch
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<Float?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var dials by remember { mutableStateOf<List<Payloads.Dial>>(emptyList()) }
    var pendingStructured by remember { mutableStateOf<ByteArray?>(null) }

    suspend fun read(uri: Uri): ByteArray? = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val blob = read(uri) ?: return@launch
            progress = 0f
            note = "Uploading ${blob.size / 1024} KB…"
            val ok = watch.withSession("photo face") { s -> s.uploadPhotoDial(blob) { done, total -> progress = done.toFloat() / total } }
            progress = null
            note = when (ok) { true -> "Face installed."; false -> "The watch rejected that face."; null -> "Upload failed (see Log)." }
        }
    }
    val structuredPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            pendingStructured = read(uri)
            dials = watch.withSession("list faces") { it.listDials() } ?: emptyList()
            if (dials.isEmpty()) note = "Couldn't read the installed faces."
        }
    }

    Section("Watch faces") {
        Hint("Install faces you made in the CMF Open face editor (.bin files).")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = connected && progress == null, onClick = { photoPicker.launch(arrayOf("*/*")) }) { Text("Photo face") }
            OutlinedButton(enabled = connected && progress == null, onClick = { structuredPicker.launch(arrayOf("*/*")) }) { Text("Structured face") }
        }
        progress?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth()) }
        note?.let { Hint(it) }
    }

    val blob = pendingStructured
    if (blob != null && dials.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingStructured = null },
            confirmButton = { TextButton(onClick = { pendingStructured = null }) { Text("Cancel") } },
            title = { Text("Replace which face?") },
            text = {
                Column {
                    Hint("A structured face replaces one that's installed.")
                    dials.forEach { d ->
                        Text(
                            "Face ${d.id} (type ${d.type})" + if (d.active) " · current" else "",
                            Modifier.fillMaxWidth().clickable {
                                pendingStructured = null
                                scope.launch {
                                    progress = 0f
                                    val ok = watch.withSession("structured face") { s -> s.uploadStructuredDial(blob, d.id) { done, total -> progress = done.toFloat() / total } }
                                    progress = null
                                    note = when (ok) { true -> "Face installed."; false -> "The watch rejected that face."; null -> "Upload failed (see Log)." }
                                }
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
        )
    }
}

// -- speaker -------------------------------------------------------------------

@Composable
private fun SpeakerSection() {
    val ctx = LocalContext.current
    val exp = remember { SpeakerExperiment(ctx.applicationContext) }
    var on by remember { mutableStateOf(exp.on) }
    var callOn by remember { mutableStateOf(dev.cmfopen.companion.media.SpeakerCallService.current != null) }
    var note by remember { mutableStateOf<String?>(null) }
    Section("Watch as speaker (experiment)") {
        Hint("Plays phone audio through the watch's call link, so it sounds like a phone call. The watch must be connected for calls in Android's Bluetooth settings.")
        ToggleRow("Mode 1: open the call audio link", on, "Plain SCO link. Didn't work on Nov 24; kept for comparison.") {
            note = if (it) exp.start() else { exp.stop(); "Stopped." }
            on = exp.on
        }
        ToggleRow("Mode 2: pretend phone call", callOn, "Starts a silent call so the watch switches into call audio.") {
            if (it) {
                note = dev.cmfopen.companion.media.SpeakerCallService.start(ctx)
                callOn = true
            } else {
                dev.cmfopen.companion.media.SpeakerCallService.stop()
                callOn = false
                note = "Call ended."
            }
        }
        note?.let { Hint(it) }
    }
}
