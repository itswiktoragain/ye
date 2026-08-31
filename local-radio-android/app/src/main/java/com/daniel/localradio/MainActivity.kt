package com.daniel.localradio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.telephony.TelephonyManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) LocalRadio/1.2"
private const val PREFS_NAME = "local_radio_preferences"
private const val FAVORITES_KEY = "favorite_station_keys"

data class Station(
    val uuid: String,
    val name: String,
    val streamUrls: List<String>,
    val favicon: String,
    val homepage: String,
    val country: String,
    val state: String,
    val codec: String,
    val bitrate: Int,
    val tags: String,
    val clickCount: Int,
    val hls: Boolean
) {
    val streamUrl: String get() = streamUrls.first()
    val key: String get() = uuid.ifBlank { streamUrl }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideStatusBar()
        setContent { LocalRadioApp() }
    }

    override fun onResume() {
        super.onResume()
        hideStatusBar()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    @Suppress("DEPRECATION")
    private fun hideStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}

private object FavoriteStore {
    fun load(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getStringSet(FAVORITES_KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun save(context: Context, favorites: Set<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(FAVORITES_KEY, HashSet(favorites))
            .apply()
    }
}

private object StationRepository {
    private val executor = Executors.newCachedThreadPool()
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
                        connectTimeout = 5000
                        readTimeout = 8000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", USER_AGENT)
                        setRequestProperty("Accept", "application/json")
                    }
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) error("Radio service returned $responseCode")
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    val parsed = parseStations(body)
                    connection.disconnect()
                    callback(Result.success(parsed))
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
                        connectTimeout = 3000
                        readTimeout = 3000
                        setRequestProperty("User-Agent", USER_AGENT)
                    }
                    connection.inputStream.close()
                    connection.disconnect()
                }.isSuccess
                if (succeeded) break
            }
        }
    }

    fun resolvePlaylistUrls(urls: List<String>, callback: (List<String>) -> Unit) {
        executor.execute {
            val resolved = buildList {
                for (url in urls.distinct()) {
                    runCatching { resolvePlaylist(url) }.getOrNull()?.forEach { candidate ->
                        if (candidate.startsWith("http://") || candidate.startsWith("https://")) add(candidate)
                    }
                }
            }.distinct()
            callback(resolved)
        }
    }

    private fun resolvePlaylist(source: String): List<String> {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 3500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "audio/x-mpegurl, audio/mpegurl, audio/x-scpls, text/plain, */*")
            setRequestProperty("Range", "bytes=0-65535")
        }
        return try {
            if (connection.responseCode !in 200..299) return emptyList()
            val contentType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            val lowerUrl = source.substringBefore('?').lowercase(Locale.ROOT)
            val looksLikePlaylist = lowerUrl.endsWith(".m3u") || lowerUrl.endsWith(".pls") ||
                contentType.contains("mpegurl") || contentType.contains("scpls") || contentType.contains("text/plain")
            if (!looksLikePlaylist) return emptyList()

            val bytes = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                var remaining = 65536
                while (remaining > 0) {
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count <= 0) break
                    bytes.write(buffer, 0, count)
                    remaining -= count
                }
            }
            val text = bytes.toString(Charsets.UTF_8.name())
            parsePlaylistText(source, text)
        } finally {
            connection.disconnect()
        }
    }

    private fun parsePlaylistText(baseUrl: String, text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .mapNotNull { line ->
                val candidate = when {
                    line.startsWith("File", ignoreCase = true) && '=' in line -> line.substringAfter('=').trim()
                    line.isNotBlank() && !line.startsWith("#") && !line.startsWith("[") && !line.contains('=') -> line
                    else -> null
                } ?: return@mapNotNull null
                runCatching { URL(URL(baseUrl), candidate).toString() }.getOrNull()
            }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(8)
            .toList()
    }

    private fun parseStations(body: String): List<Station> {
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.optString("name").trim()
                val urls = listOf(item.optString("url_resolved"), item.optString("url"))
                    .map { normalizeHttpUrl(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                if (urls.isEmpty() || name.isBlank()) continue

                val homepage = normalizeHttpUrl(item.optString("homepage"))
                val favicon = normalizeHttpUrl(item.optString("favicon")).ifBlank {
                    runCatching {
                        val page = URL(homepage)
                        if (page.protocol == "http" || page.protocol == "https") {
                            URL(page.protocol, page.host, page.port, "/favicon.ico").toString()
                        } else ""
                    }.getOrDefault("")
                }

                add(
                    Station(
                        uuid = item.optString("stationuuid"),
                        name = name,
                        streamUrls = urls,
                        favicon = favicon,
                        homepage = homepage,
                        country = item.optString("country"),
                        state = item.optString("state"),
                        codec = item.optString("codec"),
                        bitrate = item.optInt("bitrate"),
                        tags = item.optString("tags"),
                        clickCount = item.optInt("clickcount"),
                        hls = item.optInt("hls") == 1
                    )
                )
            }
        }.distinctBy { it.key }
    }

    private fun normalizeHttpUrl(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> ""
        }
    }
}

private object ArtworkCache {
    private val cache = ConcurrentHashMap<String, Bitmap>()

    fun get(url: String): Bitmap? = cache[url]

    suspend fun load(url: String): Bitmap? {
        if (url.isBlank()) return null
        cache[url]?.let { return it }
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "image/*,*/*;q=0.8")
                }
                try {
                    if (connection.responseCode !in 200..299) return@runCatching null
                    val contentLength = connection.contentLengthLong
                    if (contentLength > 3_000_000L) return@runCatching null
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
        }
        if (bitmap != null) cache[url] = bitmap
        return bitmap
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
    val favoriteKeys = remember {
        mutableStateListOf<String>().apply { addAll(FavoriteStore.load(context)) }
    }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var currentStation by remember { mutableStateOf<Station?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var playerError by remember { mutableStateOf<String?>(null) }
    var playbackStatus by remember { mutableStateOf("Paused") }
    var playbackUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var playbackIndex by remember { mutableStateOf(0) }
    var playlistRecoveryTried by remember { mutableStateOf(false) }
    var playerExpanded by rememberSaveable { mutableStateOf(false) }

    val countryCode = remember { detectCountryCode(context) }
    val countryName = remember(countryCode) {
        Locale("", countryCode).getDisplayCountry(Locale.getDefault()).ifBlank { countryCode }
    }

    val player = remember {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(4500)
            .setReadTimeoutMs(8000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(USER_AGENT)
        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(httpFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1))
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1_000, 12_000, 250, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
            }
    }

    fun toggleFavorite(station: Station) {
        if (favoriteKeys.contains(station.key)) favoriteKeys.remove(station.key)
        else favoriteKeys.add(station.key)
        FavoriteStore.save(context, favoriteKeys.toSet())
    }

    fun playUrl(station: Station, url: String, status: String) {
        playerError = null
        playbackStatus = status
        val lowerUrl = url.substringBefore('?').lowercase(Locale.ROOT)
        val builder = MediaItem.Builder().setUri(url)
        if (station.hls || lowerUrl.endsWith(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(builder.build())
        player.prepare()
        player.playWhenReady = true
    }

    DisposableEffect(player, currentStation, playbackUrls, playbackIndex, playlistRecoveryTried) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) {
                isPlaying = value
                if (value) playbackStatus = "Live · playing"
                else if (player.playbackState == Player.STATE_READY && !player.playWhenReady) playbackStatus = "Live · paused"
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> if (currentStation != null) playbackStatus = "Connecting…"
                    Player.STATE_READY -> if (player.playWhenReady && !player.isPlaying) playbackStatus = "Starting audio…"
                    Player.STATE_ENDED -> playbackStatus = "Stream ended"
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val station = currentStation ?: return
                val nextIndex = playbackIndex + 1
                if (nextIndex < playbackUrls.size) {
                    playbackIndex = nextIndex
                    playUrl(station, playbackUrls[nextIndex], "Trying alternate stream…")
                    return
                }

                if (!playlistRecoveryTried) {
                    playlistRecoveryTried = true
                    playbackStatus = "Repairing stream address…"
                    val originalUrls = playbackUrls
                    StationRepository.resolvePlaylistUrls(originalUrls) { recovered ->
                        (context as? ComponentActivity)?.runOnUiThread {
                            val combined = (originalUrls + recovered).distinct()
                            if (combined.size > originalUrls.size && currentStation?.key == station.key) {
                                playbackUrls = combined
                                playbackIndex = originalUrls.size
                                playUrl(station, combined[playbackIndex], "Trying recovered stream…")
                            } else if (currentStation?.key == station.key) {
                                playerError = friendlyPlaybackError(error)
                                playbackStatus = "Couldn’t play this station"
                            }
                        }
                    }
                } else {
                    playerError = friendlyPlaybackError(error)
                    playbackStatus = "Couldn’t play this station"
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    DisposableEffect(player) { onDispose { player.release() } }

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

    fun playStation(station: Station) {
        currentStation = station
        playbackUrls = station.streamUrls
        playbackIndex = 0
        playlistRecoveryTried = false
        playerError = null
        playUrl(station, station.streamUrls.first(), "Connecting…")
        StationRepository.countClick(station.uuid)
    }

    fun togglePlayback(station: Station) {
        if (playerError != null) playStation(station)
        else if (player.isPlaying) player.pause()
        else player.play()
    }

    LaunchedEffect(countryCode) { reload() }

    if (playerExpanded && currentStation != null) {
        BackHandler { playerExpanded = false }
        NowPlayingScreen(
            station = currentStation!!,
            isPlaying = isPlaying,
            status = playbackStatus,
            error = playerError,
            favorite = favoriteKeys.contains(currentStation!!.key),
            onClose = { playerExpanded = false },
            onToggle = { togglePlayback(currentStation!!) },
            onFavorite = { toggleFavorite(currentStation!!) }
        )
        return
    }

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
                        status = playbackStatus,
                        error = playerError,
                        favorite = favoriteKeys.contains(station.key),
                        onOpen = { playerExpanded = true },
                        onFavorite = { toggleFavorite(station) },
                        onToggle = { togglePlayback(station) }
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
                    items(filtered, key = { it.key }) { station ->
                        StationRow(
                            station = station,
                            active = currentStation?.key == station.key,
                            playing = currentStation?.key == station.key && isPlaying,
                            favorite = favoriteKeys.contains(station.key),
                            onFavorite = { toggleFavorite(station) },
                            onClick = {
                                if (currentStation?.key == station.key && player.isPlaying) player.pause()
                                else if (currentStation?.key == station.key && playerError == null) player.play()
                                else playStation(station)
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
private fun StationArtwork(station: Station, modifier: Modifier, corner: Int, large: Boolean = false) {
    var bitmap by remember(station.favicon) { mutableStateOf(ArtworkCache.get(station.favicon)) }
    LaunchedEffect(station.favicon) {
        if (bitmap == null && station.favicon.isNotBlank()) bitmap = ArtworkCache.load(station.favicon)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(corner.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = "${station.name} cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = station.name.firstOrNull()?.uppercaseChar()?.toString() ?: "♪",
                    style = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun StationRow(
    station: Station,
    active: Boolean,
    playing: Boolean,
    favorite: Boolean,
    onFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val container = if (active) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Surface(color = container, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(onClick = onClick),
            headlineContent = { Text(station.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val details = buildList {
                    if (station.state.isNotBlank()) add(station.state)
                    if (station.codec.isNotBlank()) add(station.codec.uppercase())
                    if (station.bitrate > 0) add("${station.bitrate} kbps")
                }.joinToString(" · ")
                Text(details.ifBlank { station.country }, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            leadingContent = {
                StationArtwork(station, Modifier.size(52.dp), corner = 14)
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onFavorite) {
                        Icon(
                            if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (favorite) "Remove favorite" else "Add favorite",
                            tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play"
                    )
                }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun PlayerBar(
    station: Station,
    isPlaying: Boolean,
    status: String,
    error: String?,
    favorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onToggle: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StationArtwork(station, Modifier.size(58.dp), corner = 16)
            Column(modifier = Modifier.weight(1f)) {
                Text(station.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    error ?: status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (favorite) "Remove favorite" else "Add favorite"
                )
            }
            FilledIconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else if (error != null) "Retry" else "Play"
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NowPlayingScreen(
    station: Station,
    isPlaying: Boolean,
    status: String,
    error: String?,
    favorite: Boolean,
    onClose: () -> Unit,
    onToggle: () -> Unit,
    onFavorite: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Close player")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(0.25f))
            StationArtwork(
                station = station,
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f),
                corner = 32,
                large = true
            )
            Spacer(Modifier.height(28.dp))
            Text(
                station.name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            val details = buildList {
                if (station.state.isNotBlank()) add(station.state)
                else if (station.country.isNotBlank()) add(station.country)
                if (station.codec.isNotBlank()) add(station.codec.uppercase())
                if (station.bitrate > 0) add("${station.bitrate} kbps")
            }.joinToString(" · ")
            if (details.isNotBlank()) {
                Text(
                    details,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                error ?: status,
                color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(24.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                FilledIconButton(onClick = onFavorite, modifier = Modifier.size(58.dp)) {
                    Icon(
                        if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = if (favorite) "Remove favorite" else "Add favorite"
                    )
                }
                FilledIconButton(onClick = onToggle, modifier = Modifier.size(76.dp)) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "Pause" else if (error != null) "Retry" else "Play",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
            Spacer(Modifier.weight(0.35f))
        }
    }
}

private fun friendlyPlaybackError(error: PlaybackException): String {
    return when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Station server didn’t respond. Tap play to retry."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Station server rejected the stream. Tap play to retry."
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This station uses an unsupported stream format."
        else -> "This station is unavailable right now. Tap play to retry."
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
