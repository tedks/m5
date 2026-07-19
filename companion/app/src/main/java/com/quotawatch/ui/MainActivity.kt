package com.quotawatch.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.quotawatch.api.ApiKeys
import com.quotawatch.api.LoginStatus
import com.quotawatch.api.QuotaResult
import com.quotawatch.api.serviceDisplayName
import com.quotawatch.ble.BleClient
import com.quotawatch.service.QuotaRefreshService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: QuotaViewModel by viewModels()

    // Guards the one-time wiring of the auto-refresh toggle → service start/stop collector.
    private var serviceControlStarted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // The user has now answered the permission dialog. If BLE permissions are granted, this is
        // where we're first allowed to start the connectedDevice foreground service (see C1).
        startServiceControlIfPermitted()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Give the scraper an Activity context so WebViews can render
        vm.setActivityContext(this)
        requestBlePermissions()

        // If BLE permissions are already held (returning user, or a pre-S device where
        // BLUETOOTH_CONNECT/SCAN aren't runtime permissions), start the service now. On a fresh
        // install where the dialog is still unanswered, this is a no-op and the permissionLauncher
        // callback starts it once the user grants.
        startServiceControlIfPermitted()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                QuotaWatchApp(vm)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Covers: user denied in-app, granted BLUETOOTH_CONNECT/SCAN from system Settings, then
        // returned here without the Activity being recreated — startServiceControlIfPermitted()
        // is idempotent (guarded by serviceControlStarted), so retrying here is safe.
        startServiceControlIfPermitted()
    }

    /**
     * Wire the auto-refresh toggle to start/stop [QuotaRefreshService], but only once the BLE
     * runtime permissions are actually granted.
     *
     * C1: [QuotaRefreshService] runs as a `connectedDevice` foreground service. On Android
     * 14+/targetSdk 35, starting such a service without holding a qualifying runtime permission
     * (BLUETOOTH_CONNECT/SCAN) throws SecurityException — which would crash on first launch, since
     * auto-refresh defaults on and [requestBlePermissions] fires an *async* dialog that isn't
     * answered yet. So we gate the start on [hasBlePermissions] and (re)try from the permission
     * callback. Idempotent — the collector is launched at most once.
     *
     * Collected only while the Activity is STARTED, so startForegroundService always runs from a
     * foreground context (Android 12+ blocks background FGS starts).
     */
    private fun startServiceControlIfPermitted() {
        if (serviceControlStarted) return
        if (!hasBlePermissions()) return
        serviceControlStarted = true
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.autoRefreshEnabled.collect { enabled ->
                    if (enabled) QuotaRefreshService.start(this@MainActivity)
                    else QuotaRefreshService.stop(this@MainActivity)
                }
            }
        }
    }

    /**
     * Whether the runtime permissions required to start the connectedDevice FGS are held. Pre-S,
     * BLUETOOTH_CONNECT/SCAN aren't runtime permissions and the FGS-permission enforcement doesn't
     * apply, so treat them as granted.
     */
    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestBlePermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        // The foreground service posts an ongoing notification; on API 33+ that needs runtime grant.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val needed = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }
}

@Composable
fun QuotaWatchApp(vm: QuotaViewModel) {
    var loginUrl by remember { mutableStateOf<String?>(null) }

    if (loginUrl != null) {
        LoginWebViewScreen(
            url = loginUrl!!,
            onDone = {
                // Refresh right away rather than waiting for the next periodic tick (see
                // QuotaRepository.onLoginDone) — but deliberately does NOT touch the recorded
                // session outcome itself. A stale "Session expired" is corrected only once the
                // refresh's own scrape finds genuine evidence of a valid session.
                vm.onLoginDone()
                loginUrl = null
            }
        )
    } else {
        QuotaWatchScreen(vm, onLogin = { loginUrl = it })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebViewScreen(url: String, onDone: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Log in") },
                actions = {
                    Button(onClick = onDone, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Done")
                    }
                }
            )
        }
    ) { padding ->
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(padding),
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, finishedUrl: String) {
                            // Flush cookies to persistent storage
                            CookieManager.getInstance().flush()
                        }
                    }
                    loadUrl(url)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotaWatchScreen(vm: QuotaViewModel, onLogin: (String) -> Unit) {
    val snapshot by vm.quotas.collectAsStateWithLifecycle()
    val bleState by vm.bleClient.state.collectAsStateWithLifecycle()
    val keys by vm.apiKeys.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val autoRefresh by vm.autoRefreshEnabled.collectAsStateWithLifecycle()
    val claudeLoginStatus by vm.claudeLoginStatus.collectAsStateWithLifecycle()
    val codexLoginStatus by vm.codexLoginStatus.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("QuotaWatch") },
                actions = {
                    TextButton(onClick = { showSettings = !showSettings }) {
                        Text(if (showSettings) "Hide" else "Settings",
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BleCard(bleState, onConnect = vm::connectBle, onDisconnect = vm::disconnectBle)

            if (showSettings) {
                SettingsCard(
                    keys = keys,
                    claudeStatus = claudeLoginStatus,
                    codexStatus = codexLoginStatus,
                    onUpdateKeys = vm::updateApiKeys,
                    onLoginClaude = { onLogin("https://claude.ai/settings/usage") },
                    onLoginCodex = { onLogin("https://chatgpt.com/codex/cloud/settings/usage") }
                )
            }

            // Refresh controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = vm::refresh,
                    modifier = Modifier.weight(1f),
                    enabled = !refreshing
                ) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Refresh")
                }
                FilterChip(
                    selected = autoRefresh,
                    onClick = vm::toggleAutoRefresh,
                    label = { Text("Auto", fontSize = 13.sp) }
                )
            }

            if (snapshot.timestamp > 0) {
                LastUpdatedText(snapshot.timestamp)
            }

            snapshot.successes.forEach { QuotaCard(it, snapshot.timestamp) }
            snapshot.errors.forEach { ErrorCard(serviceDisplayName(it.service), it.message) }
            snapshot.unavailable.forEach { UnavailableCard(serviceDisplayName(it.service), it.reason) }

            if (snapshot.results.isEmpty()) {
                Text(
                    "Log in to Claude/Codex and add a GitHub token, then tap Refresh.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
        }
    }
}

@Composable
fun BleCard(state: BleClient.State, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("M5 Connection", fontWeight = FontWeight.Bold)
                Text(
                    when (state) {
                        is BleClient.State.Disconnected -> "Not connected"
                        is BleClient.State.Scanning -> "Scanning..."
                        is BleClient.State.Connecting -> "Connecting..."
                        is BleClient.State.Connected -> "Connected"
                        is BleClient.State.Reconnecting -> "Reconnecting..."
                        is BleClient.State.Error -> state.message
                    },
                    fontSize = 13.sp,
                    color = when (state) {
                        is BleClient.State.Connected -> Color(0xFF4CAF50)
                        is BleClient.State.Reconnecting -> Color(0xFFFF9800)
                        is BleClient.State.Error -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            when (state) {
                is BleClient.State.Connected ->
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                is BleClient.State.Scanning, is BleClient.State.Connecting, is BleClient.State.Reconnecting ->
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                else ->
                    Button(onClick = onConnect) { Text("Connect") }
            }
        }
    }
}

/** Label + color for a [LoginStatus], shared by the Claude and Codex rows below. */
@Composable
private fun loginStatusLabelAndColor(status: LoginStatus): Pair<String, Color> = when (status) {
    LoginStatus.LOGGED_IN -> "Logged in" to Color(0xFF4CAF50)
    LoginStatus.SESSION_EXPIRED -> "Session expired" to MaterialTheme.colorScheme.error
    LoginStatus.NOT_LOGGED_IN -> "Not logged in" to MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun SettingsCard(
    keys: ApiKeys,
    claudeStatus: LoginStatus,
    codexStatus: LoginStatus,
    onUpdateKeys: (ApiKeys) -> Unit,
    onLoginClaude: () -> Unit,
    onLoginCodex: () -> Unit
) {
    var githubToken by remember(keys) { mutableStateOf(keys.githubToken ?: "") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", fontWeight = FontWeight.Bold)

            // Claude login
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Claude Code", fontWeight = FontWeight.Medium)
                    val (label, color) = loginStatusLabelAndColor(claudeStatus)
                    Text(label, fontSize = 12.sp, color = color)
                }
                OutlinedButton(onClick = onLoginClaude) {
                    Text(if (claudeStatus == LoginStatus.NOT_LOGGED_IN) "Log in" else "Re-login")
                }
            }

            // Codex login
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Codex", fontWeight = FontWeight.Medium)
                    val (label, color) = loginStatusLabelAndColor(codexStatus)
                    Text(label, fontSize = 12.sp, color = color)
                }
                OutlinedButton(onClick = onLoginCodex) {
                    Text(if (codexStatus == LoginStatus.NOT_LOGGED_IN) "Log in" else "Re-login")
                }
            }

            // GitHub token
            OutlinedTextField(
                value = githubToken,
                onValueChange = {
                    githubToken = it
                    onUpdateKeys(keys.copy(githubToken = it.ifBlank { null }))
                },
                label = { Text("GitHub Classic Token") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Text(
                "Classic token (ghp_...) with 'user' scope",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// A carried-over (last-known-good) result is only flagged as stale once it's older than this by
// more than a normal fetch pass would explain — a full Claude+Codex+GitHub refresh can itself
// take on the order of tens of seconds, and we don't want that alone to read as "stale".
private const val STALE_DISPLAY_THRESHOLD_MS = 120_000L

@Composable
fun QuotaCard(result: QuotaResult.Success, snapshotTimestamp: Long) {
    val quota = result.quota
    val staleMs = snapshotTimestamp - result.fetchedAt
    val barColor = when {
        quota.limit <= 0 -> Color(0xFF4CAF50)
        quota.percent < 0.5f -> Color(0xFF4CAF50)
        quota.percent < 0.75f -> Color(0xFFFFEB3B)
        quota.percent < 0.9f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(quota.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    when {
                        quota.unit == "$" -> "${"$"}${"%.2f".format(quota.used)}"
                        quota.limit > 0 -> "${quota.used.toInt()} / ${quota.limit.toInt()} ${quota.unit}"
                        else -> "${quota.used.toInt()} ${quota.unit}"
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (staleMs > STALE_DISPLAY_THRESHOLD_MS) {
                Text(
                    "stale · ${staleMs / 60_000}m",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            if (quota.limit > 0) {
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(
                        Modifier.fillMaxHeight().fillMaxWidth(quota.percent)
                            .clip(RoundedCornerShape(6.dp)).background(barColor)
                    )
                }
                Text(
                    "${"%.0f".format(quota.percent * 100)}%",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ErrorCard(service: String, message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(service, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, fontSize = 13.sp, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun UnavailableCard(service: String, reason: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(service, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reason, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun LastUpdatedText(timestamp: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(10_000)
        }
    }
    val ago = (now - timestamp) / 1000
    val text = when {
        ago < 5 -> "just now"
        ago < 60 -> "${ago}s ago"
        ago < 3600 -> "${ago / 60}m ago"
        else -> "${ago / 3600}h ago"
    }
    Text(
        "Checked $text",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth()
    )
}
