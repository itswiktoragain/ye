package com.daniel.localradio

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LocalRadioApp() }
    }
}

data class Station(
    val uuid: String,
    val name: String,
    val streamUrl: String,
    val country: String,
    val state: String,
    val codec: String,
    val bitrate: Int,
    val tags: String,
    val clickCount: Int
)

private object StationRepository {
    private val executor = Executors.newSingleThreadExecutor()
    private val hosts = listOf("de1.api.radio-browser.info", "nl1.api.radio-browser.info")

    fun loadPopular(countryCode: String, callback: (Result<List<Station>>) -> Unit) {
        executor.execute {
            var lastError: Throwable? = null
            for (host in hosts) {
                try {
                    val url = URL(
                        "https://$host/json/stations/bycountrycodeexact/$countryCode" +
                            "?order=clickcount&reverse=true&limit=100&hidebroken=true"
                    )
                    val connection = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 12000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "LocalRadio/1.0")
                        setRequestProperty("Accept", "application/json")
                    }
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) error("Radio service returned $responseCode")
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    callback(Result.success(parseStations(body)))
                    connection.disconnect()
                    return@execute
                } catch (t: Throwable) {
                    lastError = t
                }
            }
            callback(Result.failure(lastError ?: IllegalStateException("No radio server available")))
        }
    }

    fun countClick(uuid: String) {
        if (uuid.isBlank()) return
        executor.execute {
            for (host in hosts) {
                val succeeded = runCatching {
                    val connection = (URL("https://$host/json/url/$uuid").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", "LocalRadio/1.0")
                    }
                    connection.inputStream.close()
                    connection.disconnect()
                }.isSuccess
                if (succeeded) break
            }
        }
    }

    private fun parseStations(body: String): List<Station> {
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
                val name = item.optString("name").trim()
                if (stream.isBlank() || name.isBlank()) continue
                add(
                    Station(
                        uuid = item.optString("stationuuid"),
                        name = name,
                        streamUrl = stream,
                        country = item.optString("country"),
                        state = item.optString("state"),
                        codec = item.optString("codec"),
                        bitrate = item.optInt("bitrate"),
                        tags = item.optString("tags"),
                        clickCount = item.optInt("clickcount")
                    )
                )
            }
        }.distinctBy { it.uuid.ifBlank { it.streamUrl } }
    }
}

@Composable
private fun LocalRadioApp() {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }

    MaterialTheme(colorScheme = colorScheme) {
        RadioScreen()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RadioScreen() {
    val context = LocalContext.current
    val stations = remember { mutableStateListOf<Station>() }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var currentStation by remember { mutableStateOf<Station?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }

    val countryCode = remember { detectCountryCode(context) }
    val countryName = remember(countryCode) {
        Locale("", countryCode).getDisplayCountry(Locale.getDefault()).ifBlank { countryCode }
    }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(value: Boolean) {
                    isPlaying = value
                }

                override fun onPlayerError(error: PlaybackException) {
                    playerError = error.localizedMessage ?: "This station could not be played."
                }
            })
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    fun reload() {
        isLoading = true
        loadError = null
        StationRepository.loadPopular(countryCode) { result ->
            (context as? ComponentActivity)?.runOnUiThread {
                result.onSuccess {
                    stations.clear()
                    stations.addAll(it)
                    isLoading = false
                }.onFailure {
                    isLoading = false
                    loadError = it.localizedMessage ?: "Could not load stations."
                }
            }
        }
    }

    LaunchedEffect(countryCode) { reload() }

    val filtered = if (query.isBlank()) {
        stations.toList()
    } else {
        stations.filter {
            it.name.contains(query, ignoreCase = true) ||
                it.tags.contains(query, ignoreCase = true) ||
                it.state.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Local Radio", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Popular in $countryName",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { reload() }, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = currentStation != null) {
                currentStation?.let { station ->
                    PlayerBar(
                        station = station,
                        isPlaying = isPlaying,
                        error = playerError,
                        onToggle = {
                            if (player.isPlaying) player.pause() else player.play()
                        }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        placeholder = { Text("Search stations or genres") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                },
                expanded = false,
                onExpandedChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {}

            when {
                isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("Finding popular stations…")
                    }
                }
                loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("Couldn’t load stations", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(loadError.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        FilledIconButton(onClick = { reload() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Try again")
                        }
                    }
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No stations match “$query”.")
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered, key = { it.uuid.ifBlank { it.streamUrl } }) { station ->
                        StationRow(
                            station = station,
                            active = currentStation?.uuid == station.uuid,
                            onClick = {
                                currentStation = station
                                playerError = null
                                player.setMediaItem(MediaItem.fromUri(station.streamUrl))
                                player.prepare()
                                player.play()
                                StationRepository.countClick(station.uuid)
                            }
                        )
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StationRow(station: Station, active: Boolean, onClick: () -> Unit) {
    val container = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            headlineContent = {
                Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                val details = buildList {
                    if (station.state.isNotBlank()) add(station.state)
                    if (station.codec.isNotBlank()) add(station.codec.uppercase())
                    if (station.bitrate > 0) add("${station.bitrate} kbps")
                }.joinToString(" · ")
                Text(details.ifBlank { station.country }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                Surface(
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Radio, contentDescription = null)
                    }
                }
            },
            trailingContent = {
                Icon(
                    if (active) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun PlayerBar(station: Station, isPlaying: Boolean, error: String?, onToggle: () -> Unit) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(58.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Radio, contentDescription = null, modifier = Modifier.size(30.dp))
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(station.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    error ?: if (isPlaying) "Live · playing" else "Live · paused",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            FilledIconButton(onClick = onToggle, enabled = error == null) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play"
                )
            }
        }
    }
}

private fun detectCountryCode(context: Context): String {
    val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    val candidates = listOf(
        runCatching { telephony?.networkCountryIso }.getOrNull(),
        runCatching { telephony?.simCountryIso }.getOrNull(),
        Locale.getDefault().country
    )
    return candidates.firstOrNull { !it.isNullOrBlank() }?.uppercase(Locale.ROOT) ?: "PL"
}
