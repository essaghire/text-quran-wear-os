package com.example.textreaderwear

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

enum class ReaderLanguage(val label: String, val edition: String) {
    ARABIC("العربية", "quran-uthmani"),
    ENGLISH("English", "en.sahih")
}

enum class ReaderTheme(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark")
}

enum class ReaderTextColor(
    val label: String,
    val lightColor: Color?,
    val darkColor: Color?
) {
    DEFAULT("Auto", null, null),
    AMBER("Amber", Color(0xFF8D5A00), Color(0xFFFFD54F)),
    CYAN("Cyan", Color(0xFF006978), Color(0xFF80DEEA)),
    MINT("Mint", Color(0xFF176B43), Color(0xFF80CBC4)),
    ROSE("Rose", Color(0xFF9A3554), Color(0xFFF48FB1));

    fun color(useDarkColors: Boolean): Color? = if (useDarkColors) darkColor else lightColor
}

data class ReaderUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val rawText: String = "",
    val pages: List<String> = emptyList(),
    val pageIndex: Int = 0,
    val fontSize: Float = 17f,
    val language: ReaderLanguage = ReaderLanguage.ARABIC,
    val theme: ReaderTheme = ReaderTheme.SYSTEM,
    val textColor: ReaderTextColor = ReaderTextColor.DEFAULT,
    val bookmarks: List<Int> = emptyList()
)

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("reader_settings", Application.MODE_PRIVATE)
    private val bookmarkPreferences = application.getSharedPreferences("reader_bookmarks", Application.MODE_PRIVATE)
    private val initialLanguage = when (preferences.getString("language", ReaderLanguage.ARABIC.name)) {
        ReaderLanguage.ENGLISH.name -> ReaderLanguage.ENGLISH
        else -> ReaderLanguage.ARABIC
    }
    private val initialTheme = runCatching {
        ReaderTheme.valueOf(preferences.getString("theme", ReaderTheme.SYSTEM.name) ?: ReaderTheme.SYSTEM.name)
    }.getOrDefault(ReaderTheme.SYSTEM)
    private val initialTextColor = runCatching {
        ReaderTextColor.valueOf(preferences.getString("text_color", ReaderTextColor.DEFAULT.name) ?: ReaderTextColor.DEFAULT.name)
    }.getOrDefault(ReaderTextColor.DEFAULT)
    private val initialFontSize = preferences.getFloat("font_size", 17f).coerceIn(12f, 30f)
    private val _state = MutableStateFlow(
        ReaderUiState(
            language = initialLanguage,
            theme = initialTheme,
            textColor = initialTextColor,
            fontSize = initialFontSize,
            bookmarks = loadBookmarks(initialLanguage)
        )
    )
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()
    private var downloadJob: Job? = null

    init { loadText(initialLanguage) }

    fun downloadText() = loadText(_state.value.language)

    fun selectLanguage(language: ReaderLanguage) {
        if (language == _state.value.language && !_state.value.isLoading) return
        preferences.edit().putString("language", language.name).apply()
        loadText(language)
    }

    fun selectTheme(theme: ReaderTheme) {
        preferences.edit().putString("theme", theme.name).apply()
        _state.value = _state.value.copy(theme = theme)
    }

    fun selectTextColor(textColor: ReaderTextColor) {
        preferences.edit().putString("text_color", textColor.name).apply()
        _state.value = _state.value.copy(textColor = textColor)
    }

    fun toggleBookmark() {
        val state = _state.value
        if (state.pages.isEmpty()) return
        val pageSize = charsPerPage(state.fontSize)
        val offset = (state.pageIndex * pageSize).coerceAtMost(state.rawText.length)
        val updated = if (offset in state.bookmarks) {
            state.bookmarks - offset
        } else {
            (state.bookmarks + offset).sorted()
        }
        saveBookmarks(state.language, updated)
        _state.value = state.copy(bookmarks = updated)
    }

    fun removeBookmark(offset: Int) {
        val state = _state.value
        val updated = state.bookmarks - offset
        saveBookmarks(state.language, updated)
        _state.value = state.copy(bookmarks = updated)
    }

    fun goToBookmark(offset: Int) {
        val state = _state.value
        if (state.pages.isEmpty()) return
        val targetPage = offset.coerceAtLeast(0) / charsPerPage(state.fontSize) + 1
        goToPage(targetPage)
    }

    fun resetSettings() {
        preferences.edit().clear().apply()
        _state.value = _state.value.copy(
            fontSize = 17f,
            language = ReaderLanguage.ARABIC,
            theme = ReaderTheme.SYSTEM,
            textColor = ReaderTextColor.DEFAULT
        )
        loadText(ReaderLanguage.ARABIC)
    }

    private fun loadText(language: ReaderLanguage) {
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                error = null,
                language = language,
                bookmarks = loadBookmarks(language),
                rawText = "",
                pages = emptyList(),
                pageIndex = 0
            )
            var cachedText: String? = null
            try {
                cachedText = withContext(Dispatchers.IO) {
                    cacheFile(language).takeIf(File::exists)?.readText(Charsets.UTF_8)?.takeIf(String::isNotBlank)
                }
                if (cachedText != null) {
                    val cachedPages = paginate(cachedText, _state.value.fontSize)
                    _state.value = _state.value.copy(
                        isLoading = false,
                        rawText = cachedText,
                        pages = cachedPages,
                        pageIndex = 0
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                _state.value = _state.value.copy(error = "Cache locale illisible : ${cause.message}")
            }

            try {
                val text = download(language)
                val pages = paginate(text, _state.value.fontSize)
                var cacheWarning: String? = null
                try {
                    withContext(Dispatchers.IO) {
                        val destination = cacheFile(language)
                        val temporary = File(destination.parentFile, "${destination.name}.tmp")
                        temporary.writeText(text, Charsets.UTF_8)
                        if (!temporary.renameTo(destination)) {
                            temporary.delete()
                            error("Impossible d’enregistrer le cache local.")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (cause: Exception) {
                    cacheWarning = "Texte chargé, mais cache local indisponible : ${cause.message}"
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = cacheWarning,
                    rawText = text,
                    pages = pages,
                    pageIndex = 0
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Exception) {
                val message = if (cachedText != null) {
                    "Hors ligne : texte enregistré utilisé. Actualisation impossible : ${cause.message}"
                } else {
                    cause.message ?: "Téléchargement impossible"
                }
                _state.value = _state.value.copy(isLoading = false, error = message)
            }
        }
    }

    private fun cacheFile(language: ReaderLanguage) =
        File(getApplication<Application>().filesDir, "quran-${language.name.lowercase()}.txt")

    private fun bookmarkKey(language: ReaderLanguage) = "bookmarks_${language.name.lowercase()}"

    private fun loadBookmarks(language: ReaderLanguage): List<Int> =
        bookmarkPreferences.getString(bookmarkKey(language), "")
            .orEmpty()
            .split(',')
            .mapNotNull(String::toIntOrNull)
            .filter { it >= 0 }
            .distinct()
            .sorted()

    private fun saveBookmarks(language: ReaderLanguage, bookmarks: List<Int>) {
        bookmarkPreferences.edit().putString(bookmarkKey(language), bookmarks.joinToString(",")).apply()
    }

    fun goToPage(oneBasedPage: Int) {
        val max = _state.value.pages.lastIndex
        if (max >= 0) _state.value = _state.value.copy(pageIndex = (oneBasedPage - 1).coerceIn(0, max))
    }

    fun changePage(delta: Int) = goToPage(_state.value.pageIndex + 1 + delta)

    fun changeFontSize(delta: Float) {
        val newSize = (_state.value.fontSize + delta).coerceIn(12f, 30f)
        if (newSize == _state.value.fontSize) return
        preferences.edit().putFloat("font_size", newSize).apply()
        val old = _state.value
        val anchor = old.pageIndex.toFloat() / old.pages.size.coerceAtLeast(1)
        val newPages = paginate(old.rawText, newSize)
        _state.value = old.copy(
            fontSize = newSize,
            pages = newPages,
            pageIndex = (anchor * newPages.size).toInt().coerceIn(0, newPages.lastIndex.coerceAtLeast(0))
        )
    }

    fun scaleFontSize(scale: Float) {
        val newSize = (_state.value.fontSize * scale).coerceIn(12f, 30f)
        if (kotlin.math.abs(newSize - _state.value.fontSize) < 0.5f) return
        val oldSize = _state.value.fontSize
        changeFontSize(newSize - oldSize)
    }

    private suspend fun download(language: ReaderLanguage): String = withContext(Dispatchers.IO) {
        val address = "https://api.alquran.cloud/v1/quran/${language.edition}"
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) error("Serveur: HTTP ${connection.responseCode}")
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(response)
            if (root.optInt("code") != 200) error("La source du Coran a renvoyé une réponse invalide.")
            val surahs = root.getJSONObject("data").getJSONArray("surahs")
            buildString {
                for (surahIndex in 0 until surahs.length()) {
                    val ayahs = surahs.getJSONObject(surahIndex).getJSONArray("ayahs")
                    for (ayahIndex in 0 until ayahs.length()) {
                        if (isNotEmpty()) append('\n')
                        append(ayahs.getJSONObject(ayahIndex).getString("text").trim())
                    }
                }
            }.removePrefix("\uFEFF")
        } finally { connection.disconnect() }
    }

    private fun paginate(text: String, fontSize: Float): List<String> {
        return text.trim().chunked(charsPerPage(fontSize)).ifEmpty { listOf("Le fichier est vide.") }
    }

    private fun charsPerPage(fontSize: Float) = (760 * 17f / fontSize).toInt().coerceAtLeast(240)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ReaderScreen() }
    }
}

@Composable
private fun ReaderScreen(vm: ReaderViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val useDarkColors = when (state.theme) {
        ReaderTheme.SYSTEM -> isSystemInDarkTheme()
        ReaderTheme.LIGHT -> false
        ReaderTheme.DARK -> true
    }
    var requestedPage by remember { mutableStateOf("") }
    var showSettings by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }

    WristTiltNavigation(enabled = !showSettings && state.pages.isNotEmpty()) {
        vm.changePage(1)
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (useDarkColors) Color.Black else Color.White,
            contentColor = if (useDarkColors) Color.White else Color.Black
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when {
                    state.pages.isNotEmpty() -> {
                        state.error?.let { error ->
                            Text(
                                error,
                                color = Color(0xFFFF8A65),
                                fontSize = 9.sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2
                            )
                        }
                        BackHandler(enabled = showSettings) { showSettings = false }
                        if (showSettings) {
                            ReaderSettings(
                                fontSize = state.fontSize,
                                language = state.language,
                                theme = state.theme,
                                textColor = state.textColor,
                                isDarkTheme = useDarkColors,
                                bookmarks = state.bookmarks,
                                vm = vm,
                                onOpenBookmark = { offset ->
                                    vm.goToBookmark(offset)
                                    showSettings = false
                                },
                                onBack = { showSettings = false }
                            )
                        } else {
                            ReaderContent(
                                state = state,
                                requestedPage = requestedPage,
                                onRequestedPageChanged = { requestedPage = it },
                                vm = vm,
                                showControls = showControls,
                                onToggleControls = { showControls = !showControls },
                                onSwipe = vm::changePage,
                                onPinchZoom = vm::scaleFontSize,
                                useDarkColors = useDarkColors,
                                onOpenSettings = { showSettings = true },
                                onRetry = vm::downloadText
                            )
                        }
                    }
                    state.isLoading -> Text("Téléchargement du texte…", textAlign = TextAlign.Center)
                    state.error != null -> {
                        Text(state.error!!, color = Color.Red, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        ActionButton("Réessayer") { vm.downloadText() }
                    }
                }
            }
        }
    }
}

@Composable
private fun WristTiltNavigation(enabled: Boolean, onDoubleTilt: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val sensor = gyroscope ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (enabled && sensor != null) {
            var smoothed = FloatArray(3)
            var baseline = FloatArray(3)
            var calibrationSamples = 0
            var isCalibrated = false
            var isTilted = false
            var tiltCount = 0
            var firstTiltAt = 0L
            var isRotating = false
            var gestureAxis = -1
            var gestureDirection = 0f

            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                        val dominantAxis = (0..2).maxBy { kotlin.math.abs(event.values[it]) }
                        val angularSpeed = kotlin.math.sqrt(
                            event.values.fold(0f) { sum, value -> sum + value * value }
                        )
                        if (angularSpeed < 0.45f) {
                            isRotating = false
                            return
                        }
                        if (angularSpeed < 0.8f || isRotating) return

                        val direction = kotlin.math.sign(event.values[dominantAxis])
                        if (gestureAxis == -1) {
                            gestureAxis = dominantAxis
                            gestureDirection = direction
                        }
                        isRotating = true
                        if (dominantAxis != gestureAxis || direction != gestureDirection) return

                        val now = SystemClock.elapsedRealtime()
                        if (tiltCount == 0 || now - firstTiltAt > 2_500L) {
                            tiltCount = 1
                            firstTiltAt = now
                        } else {
                            tiltCount++
                            if (tiltCount >= 2) {
                                tiltCount = 0
                                firstTiltAt = 0L
                                gestureAxis = -1
                                gestureDirection = 0f
                                onDoubleTilt()
                            }
                        }
                        return
                    }

                    if (event.sensor.type != Sensor.TYPE_ACCELEROMETER || gyroscope != null) return
                    for (axis in 0..2) {
                        smoothed[axis] = if (calibrationSamples == 0 && !isCalibrated) {
                            event.values[axis]
                        } else {
                            smoothed[axis] * 0.8f + event.values[axis] * 0.2f
                        }
                    }
                    if (!isCalibrated) {
                        for (axis in 0..2) baseline[axis] += smoothed[axis]
                        calibrationSamples++
                        if (calibrationSamples >= 5) {
                            for (axis in 0..2) baseline[axis] /= calibrationSamples
                            isCalibrated = true
                        }
                        return
                    }

                    val baselineMagnitude = baseline.fold(0f) { sum, value -> sum + value * value }
                    val currentMagnitude = smoothed.fold(0f) { sum, value -> sum + value * value }
                    if (baselineMagnitude == 0f || currentMagnitude == 0f) return
                    val dot = (0..2).fold(0f) { sum, axis -> sum + baseline[axis] * smoothed[axis] }
                    val cosine = (dot / kotlin.math.sqrt(baselineMagnitude * currentMagnitude)).coerceIn(-1f, 1f)
                    val angleDegrees = Math.toDegrees(kotlin.math.acos(cosine).toDouble())

                    if (!isTilted && angleDegrees >= 15.0) {
                        val now = SystemClock.elapsedRealtime()
                        if (tiltCount == 0 || now - firstTiltAt > 2_500L) {
                            tiltCount = 1
                            firstTiltAt = now
                        } else {
                            tiltCount++
                            if (tiltCount >= 2) {
                                tiltCount = 0
                                firstTiltAt = 0L
                                onDoubleTilt()
                            }
                        }
                        isTilted = true
                    } else if (isTilted && angleDegrees <= 8.0) {
                        isTilted = false
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
            onDispose { sensorManager.unregisterListener(listener) }
        } else {
            onDispose { }
        }
    }
}

@Composable
private fun ColumnScope.ReaderSettings(
    fontSize: Float,
    language: ReaderLanguage,
    theme: ReaderTheme,
    textColor: ReaderTextColor,
    isDarkTheme: Boolean,
    bookmarks: List<Int>,
    vm: ReaderViewModel,
    onOpenBookmark: (Int) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Réglages", fontSize = 18.sp)
        Text("Langue / Language", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ActionButton(if (language == ReaderLanguage.ARABIC) "${language.label} ✓" else ReaderLanguage.ARABIC.label) {
                vm.selectLanguage(ReaderLanguage.ARABIC)
            }
            Spacer(Modifier.width(6.dp))
            ActionButton(if (language == ReaderLanguage.ENGLISH) "${language.label} ✓" else ReaderLanguage.ENGLISH.label) {
                vm.selectLanguage(ReaderLanguage.ENGLISH)
            }
        }
        Text("Theme", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ReaderTheme.values().forEach { option ->
                ActionButton(if (theme == option) "${option.label} ✓" else option.label) {
                    vm.selectTheme(option)
                }
            }
        }
        Text("Text color", fontSize = 14.sp)
        ReaderTextColor.values().toList().chunked(3).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                rowColors.forEach { option ->
                    ActionButton(
                        if (textColor == option) "${option.label} ✓" else option.label,
                        color = option.color(isDarkTheme) ?: if (isDarkTheme) Color.White else Color.Black
                    ) { vm.selectTextColor(option) }
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
        Text("Bookmarks", fontSize = 14.sp)
        if (bookmarks.isEmpty()) {
            Text("No saved pages", fontSize = 11.sp)
        } else {
            bookmarks.forEach { offset ->
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    ActionButton("Page ${offset / (760 * 17f / fontSize).toInt().coerceAtLeast(240) + 1}") {
                        onOpenBookmark(offset)
                    }
                    ActionButton("×") { vm.removeBookmark(offset) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Taille du texte", fontSize = 14.sp)
        Text("${fontSize.toInt()} sp", fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ActionButton("A−") { vm.changeFontSize(-2f) }
            Spacer(Modifier.width(8.dp))
            ActionButton("A+") { vm.changeFontSize(2f) }
        }
        Text("Uthmani • Saheeh International • AlQuran Cloud", fontSize = 10.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        ActionButton("Reset defaults") { vm.resetSettings() }
        ActionButton("Retour", onClick = onBack)
    }
}

@Composable
private fun ColumnScope.ReaderContent(
    state: ReaderUiState,
    requestedPage: String,
    onRequestedPageChanged: (String) -> Unit,
    vm: ReaderViewModel,
    showControls: Boolean,
    onToggleControls: () -> Unit,
    onSwipe: (Int) -> Unit,
    onPinchZoom: (Float) -> Unit,
    useDarkColors: Boolean,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .pointerInput(onToggleControls) {
                detectTapGestures(onLongPress = { onToggleControls() })
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(onSwipe, onPinchZoom) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var horizontalDistance = 0f
                        var initialPinchDistance = 0f
                        var pinchScale = 1f
                        var multiTouch = false
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val first = pressed[0].position
                                val second = pressed[1].position
                                val dx = first.x - second.x
                                val dy = first.y - second.y
                                val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                                if (!multiTouch) {
                                    initialPinchDistance = distance
                                    multiTouch = true
                                }
                                if (initialPinchDistance > 0f) pinchScale = distance / initialPinchDistance
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1 && !multiTouch) {
                                val x = pressed.first().position.x
                                horizontalDistance = x - down.position.x
                            }
                        } while (event.changes.any { it.pressed })

                        if (multiTouch && kotlin.math.abs(pinchScale - 1f) >= 0.08f) {
                            onPinchZoom(pinchScale)
                        } else if (!multiTouch) {
                            val threshold = size.width * 0.2f
                            if (horizontalDistance < -threshold) onSwipe(1)
                            if (horizontalDistance > threshold) onSwipe(-1)
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                targetState = state.pageIndex,
                transitionSpec = {
                    if (targetState > initialState) {
                        slideInHorizontally(tween(220)) { it } togetherWith
                            slideOutHorizontally(tween(220)) { -it }
                    } else {
                        slideInHorizontally(tween(220)) { -it } togetherWith
                            slideOutHorizontally(tween(220)) { it }
                    }
                },
                label = "page"
            ) { pageIndex ->
                val pageOffset = (pageIndex * (760 * 17f / state.fontSize).toInt().coerceAtLeast(240))
                    .coerceAtMost(state.rawText.length)
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.pages[pageIndex],
                        fontSize = state.fontSize.sp,
                        lineHeight = (state.fontSize * 1.28f).sp,
                        textAlign = if (state.language == ReaderLanguage.ARABIC) TextAlign.Right else TextAlign.Start,
                        color = state.textColor.color(useDarkColors) ?: if (useDarkColors) Color.White else Color.Black,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ActionButton(if (pageOffset in state.bookmarks) "★ Saved" else "☆ Bookmark") {
                        vm.toggleBookmark()
                    }
                }
            }
        }
        if (showControls) {
            if (state.error != null) {
                ActionButton("Retry") { onRetry() }
            }
            Text("Page ${state.pageIndex + 1} / ${state.pages.size}", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ActionButton("‹") { vm.changePage(-1) }
                Spacer(Modifier.width(6.dp))
                ActionButton("›") { vm.changePage(1) }
            }
            OutlinedTextField(
                value = requestedPage,
                onValueChange = { onRequestedPageChanged(it.filter(Char::isDigit)) },
                label = { androidx.compose.material3.Text("Page") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().height(54.dp),
                colors = TextFieldDefaults.colors(unfocusedContainerColor = Color.Transparent, focusedContainerColor = Color.Transparent)
            )
            ActionButton("Aller à la page") { requestedPage.toIntOrNull()?.let(vm::goToPage) }
            ActionButton("Réglages") { onOpenSettings() }
        }
    }
}

@Composable
private fun ActionButton(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors()
    ) { Text(label, color = color, fontSize = 11.sp, maxLines = 1) }
}
