package com.essaghir.textquran

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import kotlinx.coroutines.delay
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

enum class AyahMarker(val label: String, val symbol: String) {
    ORNAMENT("Ayah ۝", " ۝"),
    BULLET("Bullet •", " •"),
    CIRCLE("Circle ◦", " ◦"),
    STAR("Star ✦", " ✦"),
    NONE("None", "")
}

enum class ScrollSpeed(val label: String, val dpPerSecond: Float) {
    SLOW("Slow", 12f),
    MEDIUM("Medium", 24f),
    FAST("Fast", 48f)
}

enum class ReaderFontFamily(val label: String, val compose: FontFamily) {
    SANS("Sans", FontFamily.SansSerif),
    SERIF("Serif", FontFamily.Serif),
    MONOSPACE("Mono", FontFamily.Monospace)
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
    val theme: ReaderTheme = ReaderTheme.DARK,
    val textColor: ReaderTextColor = ReaderTextColor.DEFAULT,
    val ayahMarker: AyahMarker = AyahMarker.ORNAMENT,
    val scrollSpeed: ScrollSpeed = ScrollSpeed.MEDIUM,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SANS,
    val isBold: Boolean = false,
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
        ReaderTheme.valueOf(preferences.getString("theme", ReaderTheme.DARK.name) ?: ReaderTheme.DARK.name)
    }.getOrDefault(ReaderTheme.DARK)
    private val initialTextColor = runCatching {
        ReaderTextColor.valueOf(preferences.getString("text_color", ReaderTextColor.DEFAULT.name) ?: ReaderTextColor.DEFAULT.name)
    }.getOrDefault(ReaderTextColor.DEFAULT)
    private val initialAyahMarker = runCatching {
        AyahMarker.valueOf(preferences.getString("ayah_marker", AyahMarker.ORNAMENT.name) ?: AyahMarker.ORNAMENT.name)
    }.getOrDefault(AyahMarker.ORNAMENT)
    private val initialScrollSpeed = runCatching {
        ScrollSpeed.valueOf(preferences.getString("scroll_speed", ScrollSpeed.MEDIUM.name) ?: ScrollSpeed.MEDIUM.name)
    }.getOrDefault(ScrollSpeed.MEDIUM)
    private val initialFontFamily = runCatching {
        ReaderFontFamily.valueOf(preferences.getString("font_family", ReaderFontFamily.SANS.name) ?: ReaderFontFamily.SANS.name)
    }.getOrDefault(ReaderFontFamily.SANS)
    private val initialFontSize = preferences.getFloat("font_size", 17f).coerceIn(12f, 30f)
    private val _state = MutableStateFlow(
        ReaderUiState(
            language = initialLanguage,
            theme = initialTheme,
            textColor = initialTextColor,
            ayahMarker = initialAyahMarker,
            scrollSpeed = initialScrollSpeed,
            fontFamily = initialFontFamily,
            isBold = preferences.getBoolean("bold_text", false),
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

    fun selectAyahMarker(marker: AyahMarker) {
        preferences.edit().putString("ayah_marker", marker.name).apply()
        val old = _state.value
        val anchor = old.pageIndex.toFloat() / old.pages.size.coerceAtLeast(1)
        val newPages = paginate(old.rawText, old.fontSize, marker)
        _state.value = old.copy(
            ayahMarker = marker,
            pages = newPages,
            pageIndex = (anchor * newPages.size).toInt().coerceIn(0, newPages.lastIndex.coerceAtLeast(0))
        )
    }

    fun selectScrollSpeed(speed: ScrollSpeed) {
        preferences.edit().putString("scroll_speed", speed.name).apply()
        _state.value = _state.value.copy(scrollSpeed = speed)
    }

    fun selectFontFamily(fontFamily: ReaderFontFamily) {
        preferences.edit().putString("font_family", fontFamily.name).apply()
        _state.value = _state.value.copy(fontFamily = fontFamily)
    }

    fun setBold(isBold: Boolean) {
        preferences.edit().putBoolean("bold_text", isBold).apply()
        _state.value = _state.value.copy(isBold = isBold)
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
            theme = ReaderTheme.DARK,
            textColor = ReaderTextColor.DEFAULT,
            ayahMarker = AyahMarker.ORNAMENT,
            scrollSpeed = ScrollSpeed.MEDIUM,
            fontFamily = ReaderFontFamily.SANS,
            isBold = false
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
        File(getApplication<Application>().filesDir, "quran-v2-${language.name.lowercase()}.txt")

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
        val newPages = paginate(old.rawText, newSize, old.ayahMarker)
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
                        val ayah = ayahs.getJSONObject(ayahIndex)
                        append(ayah.getString("text").trim())
                        append('\u001F')
                        append(ayah.getInt("numberInSurah"))
                    }
                }
            }.removePrefix("\uFEFF")
        } finally { connection.disconnect() }
    }

    private fun paginate(text: String, fontSize: Float, marker: AyahMarker = _state.value.ayahMarker): List<String> {
        val maxChars = charsPerPage(fontSize)
        val pages = mutableListOf<String>()
        val page = StringBuilder()
        for (line in text.lineSequence()) {
            val separatorIndex = line.lastIndexOf('\u001F')
            val verseText = if (separatorIndex >= 0) line.substring(0, separatorIndex) else line
            val verseNumber = line.substring(separatorIndex + 1).toIntOrNull()
            val numberLabel = if (marker == AyahMarker.NONE) "" else verseNumber?.let(::toArabicIndicDigits).orEmpty()
            val displayVerse = verseText + marker.symbol + numberLabel
            if (page.isNotEmpty() && page.length + 1 + displayVerse.length > maxChars) {
                pages += page.toString()
                page.clear()
            }
            if (page.isNotEmpty()) page.append('\n')
            page.append(displayVerse)
        }
        if (page.isNotEmpty()) pages += page.toString()
        return pages.ifEmpty { listOf("Le fichier est vide.") }
    }

    private fun toArabicIndicDigits(value: Int): String =
        value.toString().map { char -> if (char in '0'..'9') ('٠'.code + char.code - '0'.code).toChar() else char }.joinToString("")

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
    var showSettings by remember { mutableStateOf(false) }

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
                                ayahMarker = state.ayahMarker,
                                scrollSpeed = state.scrollSpeed,
                                fontFamily = state.fontFamily,
                                isBold = state.isBold,
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
                                vm = vm,
                                onSwipe = vm::changePage,
                                onPinchZoom = vm::scaleFontSize,
                                useDarkColors = useDarkColors,
                                scrollSpeed = state.scrollSpeed,
                                onOpenSettings = { showSettings = true }
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
    ayahMarker: AyahMarker,
    scrollSpeed: ScrollSpeed,
    fontFamily: ReaderFontFamily,
    isBold: Boolean,
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
        Text("Auto-scroll speed", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            ScrollSpeed.values().forEach { option ->
                ActionButton(if (scrollSpeed == option) "${option.label} ✓" else option.label) {
                    vm.selectScrollSpeed(option)
                }
            }
            Text("Font family", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ReaderFontFamily.values().forEach { option ->
                    ActionButton(if (fontFamily == option) "${option.label} ✓" else option.label) {
                        vm.selectFontFamily(option)
                    }
                }
            }
            Text("Bold text", fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                ActionButton(if (isBold) "On ✓" else "On") { vm.setBold(true) }
                ActionButton(if (!isBold) "Off ✓" else "Off") { vm.setBold(false) }
            }
        }
        Text("Ayah end symbol", fontSize = 14.sp)
        AyahMarker.values().toList().chunked(3).forEach { markerRow ->
            Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                markerRow.forEach { option ->
                    ActionButton(if (ayahMarker == option) "${option.label} ✓" else option.label) {
                        vm.selectAyahMarker(option)
                    }
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
        Text(
            "API: AlQuran Cloud • Uthmani: Tanzil.net / Quran Academy • English: Saheeh International",
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        ActionButton("Reset defaults") { vm.resetSettings() }
        ActionButton("Retour", onClick = onBack)
    }
}

@Composable
private fun ColumnScope.ReaderContent(
    state: ReaderUiState,
    vm: ReaderViewModel,
    onSwipe: (Int) -> Unit,
    onPinchZoom: (Float) -> Unit,
    useDarkColors: Boolean,
    scrollSpeed: ScrollSpeed,
    onOpenSettings: () -> Unit
) {
    var isAutoScrolling by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
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
                val pageScrollState = rememberScrollState()
                val density = LocalDensity.current.density
                val scrollScope = rememberCoroutineScope()
                LaunchedEffect(isAutoScrolling, pageIndex, scrollSpeed) {
                    if (!isAutoScrolling) return@LaunchedEffect
                    var previousFrame = 0L
                    var fractionalPixels = 0f
                    withFrameNanos { it }
                    while (isAutoScrolling && pageScrollState.value < pageScrollState.maxValue) {
                        val frame = withFrameNanos { it }
                        if (previousFrame != 0L) {
                            val elapsedSeconds = (frame - previousFrame) / 1_000_000_000f
                            fractionalPixels += elapsedSeconds * scrollSpeed.dpPerSecond * density
                            val wholePixels = fractionalPixels.toInt()
                            if (wholePixels > 0) {
                                pageScrollState.scrollTo(
                                    (pageScrollState.value + wholePixels).coerceAtMost(pageScrollState.maxValue)
                                )
                                fractionalPixels -= wholePixels
                            }
                        }
                        previousFrame = frame
                    }
                    if (isAutoScrolling && pageIndex < state.pages.lastIndex) {
                        delay(2_000L)
                        if (isAutoScrolling) onSwipe(1)
                    } else if (isAutoScrolling) {
                        isAutoScrolling = false
                    }
                }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(pageScrollState),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ReaderToolButton("↓ End") {
                            isAutoScrolling = false
                            scrollScope.launch { pageScrollState.animateScrollTo(pageScrollState.maxValue) }
                        }
                        Spacer(Modifier.width(4.dp))
                        ReaderToolButton(if (isAutoScrolling) "Ⅱ" else "▶") {
                            isAutoScrolling = !isAutoScrolling
                        }
                    }
                    Text(
                        text = state.pages[pageIndex],
                        fontSize = state.fontSize.sp,
                        lineHeight = (state.fontSize * 1.28f).sp,
                        fontFamily = state.fontFamily.compose,
                        fontWeight = if (state.isBold) FontWeight.Bold else FontWeight.Normal,
                        textAlign = if (state.language == ReaderLanguage.ARABIC) TextAlign.Right else TextAlign.Start,
                        color = state.textColor.color(useDarkColors) ?: if (useDarkColors) Color.White else Color.Black,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        ReaderToolButton(
                            if (pageOffset in state.bookmarks) "★" else "☆",
                            modifier = Modifier.width(32.dp),
                            contentDescription = if (pageOffset in state.bookmarks) "Remove bookmark" else "Save bookmark"
                        ) {
                            vm.toggleBookmark()
                        }
                        ReaderToolButton(
                            "⚙",
                            modifier = Modifier.width(32.dp),
                            contentDescription = "Settings",
                            onClick = onOpenSettings
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderToolButton(
    label: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(30.dp)
            .then(if (contentDescription == null) Modifier else Modifier.semantics {
                this.contentDescription = contentDescription
            }),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
    ) { Text(label, fontSize = 9.sp, maxLines = 1) }
}

@Composable
private fun ActionButton(label: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        colors = ButtonDefaults.buttonColors()
    ) { Text(label, color = color, fontSize = 11.sp, maxLines = 1) }
}
