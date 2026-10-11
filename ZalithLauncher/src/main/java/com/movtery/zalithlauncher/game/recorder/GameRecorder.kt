/*
 * Zalith Launcher 2
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.movtery.zalithlauncher.game.recorder

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "GameRecorder"
private const val FRAME_RATE = 20
private const val VIDEO_BIT_RATE = 4_000_000 // 4 Mbps
private const val MAX_VIDEO_DIMENSION = 1920
private const val RECORDINGS_RELATIVE_PATH = "Movies/ZalithLauncher/Recordings"

/**
 * Game-surface recorder adapted from TurboCraft-Launcher (commit 2bb7cb8),
 * https://github.com/TCL-Team/TurboCraft-Launcher, licensed under GPL-3.0 like this project.
 */
object GameRecorder {
    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val timerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var timerJob: Job? = null
    @Volatile private var accumulatedMs = 0L
    @Volatile private var resumeTimeMs = 0L

    @Volatile private var recorder: MediaRecorder? = null
    @Volatile private var inputSurface: Surface? = null
    @Volatile private var outputDescriptor: ParcelFileDescriptor? = null
    @Volatile private var recordingContext: Context? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    @Volatile private var recordingWidth = 0
    @Volatile private var recordingHeight = 0
    private val isCapturing = AtomicBoolean(false)
    @Volatile private var pendingUri: Uri? = null

    /** Starts a silent game-surface recording. Returns false when the surface or encoder is unavailable. */
    fun start(context: Context, withMic: Boolean = false): Boolean {
        if (_state.value != RecordingState.IDLE) return false

        val view = GameSurfaceRegistry.getView() ?: run {
            Log.e(TAG, "No game surface registered — cannot start recording")
            return false
        }
        val (width, height) = getRecordingSize(view.width, view.height)
        recordingWidth = width
        recordingHeight = height

        try {
            recordingContext = context.applicationContext
            val uri = createOutputEntry(context)
            pendingUri = uri
            val descriptor = context.contentResolver.openFileDescriptor(uri, "w")
                ?: throw IOException("Could not open the recording output")
            outputDescriptor = descriptor

            val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mediaRecorder.apply {
                if (withMic) setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (withMic) {
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128_000)
                    setAudioSamplingRate(44_100)
                }
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(width, height)
                setVideoFrameRate(FRAME_RATE)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setOutputFile(descriptor.fileDescriptor)
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaRecorder error what=$what extra=$extra")
                    cleanup()
                }
                prepare()
            }

            inputSurface = mediaRecorder.surface
            recorder = mediaRecorder
            mediaRecorder.start()

            captureThread = HandlerThread("GameRecorder-Capture").also { it.start() }
            captureHandler = Handler(captureThread!!.looper)
            isCapturing.set(true)
            _state.value = RecordingState.RECORDING

            accumulatedMs = 0L
            resumeTimeMs = System.currentTimeMillis()
            _elapsedMs.value = 0L
            startTimerTick()
            scheduleNextFrame()

            Log.i(TAG, "Recording started ${width}x${height} mic=$withMic")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            cleanup()
            return false
        }
    }

    fun pause() {
        if (_state.value != RecordingState.RECORDING) return
        try {
            recorder?.pause()
            accumulatedMs += System.currentTimeMillis() - resumeTimeMs
            timerJob?.cancel()
            timerJob = null
            _state.value = RecordingState.PAUSED
            Log.i(TAG, "Recording paused at ${accumulatedMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pause recording", e)
        }
    }

    fun resume() {
        if (_state.value != RecordingState.PAUSED) return
        try {
            recorder?.resume()
            resumeTimeMs = System.currentTimeMillis()
            startTimerTick()
            _state.value = RecordingState.RECORDING
            scheduleNextFrame()
            Log.i(TAG, "Recording resumed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resume recording", e)
        }
    }

    fun stopAndSave(context: Context): Boolean {
        if (_state.value == RecordingState.IDLE || _state.value == RecordingState.STOPPING) return false
        _state.value = RecordingState.STOPPING
        isCapturing.set(false)
        timerJob?.cancel()
        timerJob = null

        val appContext = context.applicationContext
        val handler = captureHandler
        if (handler == null || !handler.post { finalise(appContext) }) {
            finalise(appContext)
        }
        return true
    }

    private fun scheduleNextFrame() {
        if (!isCapturing.get() || _state.value != RecordingState.RECORDING) return
        captureHandler?.postDelayed({ captureFrame() }, 1000L / FRAME_RATE)
    }

    private fun captureFrame() {
        if (!isCapturing.get() || _state.value != RecordingState.RECORDING) return

        val view = GameSurfaceRegistry.getView()
        val surface = inputSurface
        if (view == null || surface == null) {
            scheduleNextFrame()
            return
        }

        when (view) {
            is SurfaceView -> captureFromSurfaceView(view, surface)
            is TextureView -> captureFromTextureView(view, surface)
            else -> scheduleNextFrame()
        }
    }

    private fun captureFromSurfaceView(view: SurfaceView, output: Surface) {
        val width = recordingWidth.coerceAtLeast(2)
        val height = recordingHeight.coerceAtLeast(2)
        val bitmap = runCatching { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
            .getOrElse {
                Log.e(TAG, "Could not allocate a recording frame", it)
                scheduleNextFrame()
                return
            }
        val handler = captureHandler
        if (handler == null) {
            bitmap.recycle()
            return
        }

        try {
            PixelCopy.request(view, bitmap, { result ->
                try {
                    if (result == PixelCopy.SUCCESS && isCapturing.get()) drawToSurface(bitmap, output)
                } finally {
                    bitmap.recycle()
                    scheduleNextFrame()
                }
            }, handler)
        } catch (e: Exception) {
            bitmap.recycle()
            Log.w(TAG, "Could not request a SurfaceView frame", e)
            scheduleNextFrame()
        }
    }

    private fun captureFromTextureView(view: TextureView, output: Surface) {
        val bitmap = runCatching {
            view.getBitmap(recordingWidth.coerceAtLeast(2), recordingHeight.coerceAtLeast(2))
        }.onFailure { Log.w(TAG, "Could not capture a TextureView frame", it) }.getOrNull()
        if (bitmap != null) {
            if (isCapturing.get()) drawToSurface(bitmap, output)
            bitmap.recycle()
        }
        scheduleNextFrame()
    }

    private fun drawToSurface(bitmap: Bitmap, surface: Surface) {
        runCatching {
            // Software Canvas avoids the HWUI/GLES path that can crash some mobile GPU drivers.
            val canvas = surface.lockCanvas(null)
            try {
                canvas.drawBitmap(bitmap, 0f, 0f, null)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
        }.onFailure { Log.w(TAG, "Could not send a frame to the encoder", it) }
    }

    private fun startTimerTick() {
        timerJob?.cancel()
        timerJob = timerScope.launch {
            while (isActive) {
                _elapsedMs.value = accumulatedMs + (System.currentTimeMillis() - resumeTimeMs)
                delay(250L)
            }
        }
    }

    private fun getRecordingSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        val safeWidth = sourceWidth.coerceAtLeast(2)
        val safeHeight = sourceHeight.coerceAtLeast(2)
        val scale = minOf(
            1f,
            MAX_VIDEO_DIMENSION.toFloat() / maxOf(safeWidth, safeHeight).toFloat()
        )
        val width = ((safeWidth * scale).toInt().coerceAtLeast(2) / 2) * 2
        val height = ((safeHeight * scale).toInt().coerceAtLeast(2) / 2) * 2
        return width to height
    }

    private fun finalise(context: Context) {
        val uri = pendingUri
        var recordingFinished = false
        try {
            val currentRecorder = recorder
            if (currentRecorder != null) {
                currentRecorder.stop()
                recordingFinished = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not finalize the recording", e)
        } finally {
            runCatching { recorder?.release() }
            recorder = null
            runCatching { inputSurface?.release() }
            inputSurface = null
            runCatching { outputDescriptor?.close() }
            outputDescriptor = null

            if (uri != null) {
                if (recordingFinished) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Video.Media.IS_PENDING, 0)
                        }
                        runCatching {
                            context.contentResolver.update(uri, values, null, null)
                        }.onFailure { Log.e(TAG, "Could not publish the recording", it) }
                    }
                    Log.i(TAG, "Recording saved: $uri")
                } else {
                    runCatching { context.contentResolver.delete(uri, null, null) }
                }
            }

            captureThread?.quitSafely()
            captureThread = null
            captureHandler = null
            recordingWidth = 0
            recordingHeight = 0
            recordingContext = null
            pendingUri = null
            timerJob?.cancel()
            timerJob = null
            _elapsedMs.value = 0L
            accumulatedMs = 0L
            _state.value = RecordingState.IDLE
        }
    }

    private fun cleanup() {
        isCapturing.set(false)
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        runCatching { outputDescriptor?.close() }
        outputDescriptor = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        recordingWidth = 0
        recordingHeight = 0
        timerJob?.cancel()
        timerJob = null
        _elapsedMs.value = 0L
        accumulatedMs = 0L
        pendingUri?.let { uri ->
            runCatching { recordingContext?.contentResolver?.delete(uri, null, null) }
        }
        pendingUri = null
        recordingContext = null
        _state.value = RecordingState.IDLE
    }

    private fun createOutputEntry(context: Context): Uri {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "ZalithRec_$timestamp.mp4"
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            "ZalithLauncher/Recordings"
        )

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, RECORDINGS_RELATIVE_PATH)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                if (!directory.exists() && !directory.mkdirs()) {
                    throw IOException("Could not create the recordings directory")
                }
                put(MediaStore.Video.Media.DATA, File(directory, fileName).absolutePath)
            }
        }

        return context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Failed to create a MediaStore entry for the recording")
    }
}
