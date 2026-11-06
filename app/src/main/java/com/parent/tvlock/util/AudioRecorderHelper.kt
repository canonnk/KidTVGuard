package com.parent.tvlock.util

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 电视端专用纯内存音频采集器
 *
 * 核心特性：
 * 1. 严格契合百度语音识别规范：16,000Hz 采样率、16-bit PCM、单声道；
 * 2. 纯内存流式写入 ByteArrayOutputStream，全程零磁盘 IO，不写任何临时文件；
 * 3. 实时计算音频振幅比率（0.0 ~ 1.0），驱动界面 Apple 风格动效波纹；
 * 4. 内置超时自动截断守护协程，防止意外长录导致内存过载。
 */
class AudioRecorderHelper(private val scope: CoroutineScope) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var timeoutJob: Job? = null
    private var audioOutputStream: ByteArrayOutputStream? = null

    /**
     * 开始录制音频（纯内存）
     * @param maxDurationSeconds 最长持续时间（秒）
     * @param onAmplitude 实时振幅回调 (0.0f ~ 1.0f)
     * @param onFinished 录制完成回调（返回 16kHz PCM 字节数组，失败返回 null）
     */
    @SuppressLint("MissingPermission")
    fun startRecording(
        maxDurationSeconds: Int = 4,
        onAmplitude: ((Float) -> Unit)? = null,
        onFinished: (ByteArray?) -> Unit
    ): Boolean {
        if (isRecording) {
            L.w("AudioRecorder is already recording.")
            return false
        }

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            L.e("Invalid AudioRecord minBufferSize: $minBufferSize")
            onFinished(null)
            return false
        }

        val bufferSize = (minBufferSize * 2).coerceAtLeast(2048)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                L.e("AudioRecord initialization failed!")
                audioRecord?.release()
                audioRecord = null
                onFinished(null)
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            audioOutputStream = ByteArrayOutputStream()

            L.i("Started audio recording: 16000Hz 16bit MONO, max duration: ${maxDurationSeconds}s")

            // 1. 录音主循环协程
            recordingJob = scope.launch(Dispatchers.IO) {
                val buffer = ByteArray(bufferSize)
                while (isActive && isRecording) {
                    val readBytes = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readBytes > 0) {
                        audioOutputStream?.write(buffer, 0, readBytes)

                        // 计算 RMS 振幅比率 (0.0f ~ 1.0f)
                        if (onAmplitude != null) {
                            val amplitudeRatio = calculateAmplitudeRatio(buffer, readBytes)
                            withContext(Dispatchers.Main) {
                                onAmplitude.invoke(amplitudeRatio)
                            }
                        }
                    } else if (readBytes < 0) {
                        L.w("AudioRecord read error: $readBytes")
                        break
                    }
                }

                // 录音循环退出后封装返回字节流
                val resultBytes = audioOutputStream?.toByteArray()
                audioOutputStream?.close()
                audioOutputStream = null

                withContext(Dispatchers.Main) {
                    L.i("Recording finished, captured ${resultBytes?.size ?: 0} bytes.")
                    onFinished(resultBytes)
                }
            }

            // 2. 超时自动停止守护协程
            timeoutJob = scope.launch {
                delay(maxDurationSeconds * 1000L)
                if (isRecording) {
                    L.i("Max recording duration reached (${maxDurationSeconds}s), stopping automatically.")
                    stopRecording()
                }
            }

            return true
        } catch (e: Exception) {
            L.e("Failed to start audio recording", tr = e)
            cleanUp()
            onFinished(null)
            return false
        }
    }

    /**
     * 主动停止录音
     */
    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        timeoutJob?.cancel()

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            L.w("Exception stopping AudioRecord: ${e.message}")
        } finally {
            cleanUp()
        }
    }

    /**
     * 计算音频采样的 RMS 振幅并映射至 0.0f ~ 1.0f
     */
    private fun calculateAmplitudeRatio(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        val shortBuffer = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val shortCount = length / 2
        for (i in 0 until shortCount) {
            val sample = shortBuffer.get(i).toInt()
            sum += sample * sample
        }
        val rms = Math.sqrt(sum / shortCount.coerceAtLeast(1))
        // 16bit short 最大值 32767，一般正常语音 RMS 在 500~15000 之间
        val normalized = (rms / 8000.0).toFloat().coerceIn(0.05f, 1.0f)
        return normalized
    }

    private fun cleanUp() {
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            // ignore
        }
        audioRecord = null
    }

    fun isRecordingActive(): Boolean = isRecording
}
