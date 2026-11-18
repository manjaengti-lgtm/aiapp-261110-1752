package com.fullshare.app

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val uri: Uri? = null,
    val info: VideoInfo? = null,
    val thumbnail: Bitmap? = null,
    val partSec: Int = 59,
    val parts: List<File> = emptyList(),
    val partsSec: Int = 0,
    val batch: Int = 0,
    val openStatusDirectly: Boolean = true,
    val busy: Boolean = false,
    val busyLabel: String = "",
    val progress: Float = 0f,
    val message: String? = null,
) {
    val estimatedParts: Int
        get() = info?.let { ((it.durationMs + partSec * 1000L - 1) / (partSec * 1000L)).toInt().coerceAtLeast(1) } ?: 0
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app.applicationContext
    private val partsDir = File(app.cacheDir, "parts")
    private val fullDir = File(app.cacheDir, "full")

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    fun setVideo(uri: Uri) {
        val old = _state.value
        _state.value = UiState(uri = uri, partSec = old.partSec, openStatusDirectly = old.openStatusDirectly)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val info = VideoSplitter.probe(ctx, uri)
                val thumb = VideoSplitter.thumbnail(ctx, uri)
                _state.update { if (it.uri == uri) it.copy(info = info, thumbnail = thumb) else it }
            } catch (e: Exception) {
                _state.update { it.copy(message = "Couldn't read this video: ${e.message}") }
            }
        }
    }

    fun setPartSec(sec: Int) = _state.update {
        if (it.partSec == sec) it else it.copy(partSec = sec, parts = emptyList(), batch = 0)
    }

    fun setBatch(i: Int) = _state.update { it.copy(batch = i) }
    fun setOpenStatusDirectly(v: Boolean) = _state.update { it.copy(openStatusDirectly = v) }
    fun showMessage(m: String?) = _state.update { it.copy(message = m) }

    /** Splits the video (or reuses an earlier split with the same part length). */
    suspend fun getParts(): List<File>? {
        val st = _state.value
        val uri = st.uri ?: return null
        if (st.busy) return null
        if (st.parts.isNotEmpty() && st.partsSec == st.partSec && st.parts.all { it.exists() }) return st.parts
        _state.update { it.copy(busy = true, busyLabel = "Preparing HD parts…", progress = 0f) }
        return try {
            val files = withContext(Dispatchers.IO) {
                VideoSplitter.split(ctx, uri, st.partSec * 1000L, partsDir) { p ->
                    _state.update { it.copy(progress = p) }
                }
            }
            _state.update { it.copy(parts = files, partsSec = st.partSec, busy = false, batch = 0) }
            files
        } catch (e: Exception) {
            _state.update {
                it.copy(busy = false, message = "Couldn't split this video (${e.message ?: e.javaClass.simpleName}). MP4 videos from your camera work best.")
            }
            null
        }
    }

    suspend fun getFull(): File? {
        val st = _state.value
        val uri = st.uri ?: return null
        if (st.busy) return null
        _state.update { it.copy(busy = true, busyLabel = "Preparing full video…", progress = 0f) }
        return try {
            val f = withContext(Dispatchers.IO) {
                VideoSplitter.copyFull(ctx, uri, fullDir, st.info?.name ?: "video.mp4")
            }
            _state.update { it.copy(busy = false) }
            f
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, message = "Couldn't open the video: ${e.message}") }
            null
        }
    }

    suspend fun saveParts() {
        val files = getParts() ?: return
        _state.update { it.copy(busy = true, busyLabel = "Saving to Gallery…") }
        val n = withContext(Dispatchers.IO) {
            try { Sharer.saveToGallery(ctx, files) } catch (e: Exception) { -1 }
        }
        _state.update {
            it.copy(
                busy = false,
                message = if (n >= 0) "Saved $n part(s) to Movies/FullStatusShare" else "Saving failed",
            )
        }
    }
}
