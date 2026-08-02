package com.schulzcode.y2player.playback

import android.content.Context
import android.media.AudioManager
import com.schulzcode.y2player.core.model.DacState
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import java.io.File

class DacController(context: Context, private val logger: DiagnosticLogger) {
    private val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val policy by lazy(LazyThreadSafetyMode.NONE) { readPolicyCapabilities() }

    @Volatile private var directMode = false
    @Volatile private var lastAppliedDirectMode: Boolean? = null
    @Volatile private var hiFiRequestAccepted = false

    fun applyDirectMode(enabled: Boolean) {
        if (lastAppliedDirectMode == enabled) return
        directMode = enabled
        lastAppliedDirectMode = enabled
        hiFiRequestAccepted = false
        cached = null
        logger.info(
            "audio",
            if (enabled) {
                "Direct DAC requested but unavailable; standard AudioTrack fallback remains active"
            } else {
                "Balanced AudioTrack output selected"
            }
        )
    }

    fun snapshot(track: Track?): DacState {
        val sourceRate = track?.sampleRate
        cached?.let { if (cachedSourceRate == sourceRate) return it }
        val capabilities = policy
        val outputRate = cachedOutputRate
        val limitation = when {
            directMode ->
                "Direct CS43131 PCM access is unverified; using standard AudioTrack output"
            !capabilities.dacDetected -> "CS43131 device node was not detected; using the Android audio route"
            capabilities.stockRateOnly ->
                "Stock firmware advertises 44.1 kHz / 16-bit PCM; higher-rate PCM or native DSD needs an Audio HAL/kernel patch"
            sourceRate != null && outputRate != null && sourceRate != outputRate ->
                "Source and advertised output rates differ; Android may resample this track"
            else -> null
        }
        return DacState(
            detected = capabilities.dacDetected,
            hiFiRequestAccepted = hiFiRequestAccepted,
            outputSampleRate = outputRate,
            outputFormat = capabilities.formats.firstOrNull(),
            limitation = limitation
        ).also {
            cached = it
            cachedSourceRate = sourceRate
        }
    }

    @Volatile private var cached: DacState? = null
    @Volatile private var cachedSourceRate: Int? = null

    private val cachedOutputRate: Int? by lazy(LazyThreadSafetyMode.NONE) {
        frameworkOutputRate()?.takeIf { it > 0 } ?: policy.sampleRates.firstOrNull()
    }

    private fun frameworkOutputRate(): Int? = runCatching {
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
    }.getOrNull()

    private data class PolicyCapabilities(
        val dacDetected: Boolean,
        val sampleRates: List<Int>,
        val formats: List<String>
    ) {
        val stockRateOnly: Boolean = sampleRates.size == 1 && sampleRates.firstOrNull() == 44_100 &&
            formats.none {
                it.contains("24") || it.contains("32") || it.contains("FLOAT") || it.contains("DSD")
            }
    }

    private fun readPolicyCapabilities(): PolicyCapabilities {
        val candidates = listOf(
            "/system/etc/audio_policy.conf",
            "/vendor/etc/audio_policy.conf",
            "/system/etc/audio_policy_configuration.xml",
            "/vendor/etc/audio_policy_configuration.xml"
        )
        val text = candidates.asSequence().map(::File).firstOrNull { it.isFile && it.canRead() }
            ?.let(::readBoundedText).orEmpty()
        val primary = extractPrimaryOutput(text)
        val sampleRates = RATE_REGEX.find(primary)?.groupValues?.getOrNull(1)
            ?.split(',', '|', ' ', '\t', '\n')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.distinct()
            .orEmpty()
        val formats = FORMAT_REGEX.find(primary)?.groupValues?.getOrNull(1)
            ?.split(',', '|', ' ', '\t', '\n')
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.distinct()
            .orEmpty()
        return PolicyCapabilities(
            dacDetected = File("/dev/cs43131_dac").exists() || text.contains("cs43131", ignoreCase = true),
            sampleRates = sampleRates,
            formats = formats
        )
    }

    private fun readBoundedText(file: File): String = runCatching {
        file.inputStream().buffered().reader().use { reader ->
            val buffer = CharArray(MAX_POLICY_CHARS)
            val count = reader.read(buffer)
            if (count <= 0) "" else String(buffer, 0, count)
        }
    }.getOrDefault("")

    private fun extractPrimaryOutput(text: String): String {
        if (text.isBlank()) return text
        val lower = text.lowercase()
        val primaryIndex = lower.indexOf("primary")
        if (primaryIndex < 0) return text
        return text.substring(primaryIndex, (primaryIndex + 8_192).coerceAtMost(text.length))
    }

    companion object {
        private const val MAX_POLICY_CHARS = 64 * 1024
        private val RATE_REGEX = Regex("sampling_rates?\\s*[=:]?\\s*([^\\r\\n}<]+)", RegexOption.IGNORE_CASE)
        private val FORMAT_REGEX = Regex("formats?\\s*[=:]?\\s*([^\\r\\n}<]+)", RegexOption.IGNORE_CASE)
    }
}
