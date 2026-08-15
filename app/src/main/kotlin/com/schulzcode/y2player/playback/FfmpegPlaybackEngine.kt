package com.schulzcode.y2player.playback

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import com.schulzcode.y2player.core.model.Track
import com.schulzcode.y2player.diagnostics.DiagnosticLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.ArrayDeque
import kotlin.math.min

internal sealed interface EngineCommand {
    val clearsPending: Boolean get() = false

    fun isSupersededBy(incoming: EngineCommand): Boolean = false

    data class Prepare(val track: Track, val requestId: Long) : EngineCommand {
        override val clearsPending: Boolean get() = true
    }

    data class ConfigureTransition(
        val gaplessEnabled: Boolean,
        val crossfadeMs: Long
    ) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean =
            incoming is ConfigureTransition
    }

    data class PrepareNext(
        val track: Track,
        val requestId: Long
    ) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean =
            incoming is PrepareNext || incoming is ClearNext
    }

    data object ClearNext : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean =
            incoming is PrepareNext || incoming is ClearNext
    }

    data object SkipToPrepared : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean = incoming is SkipToPrepared
    }

    data object Start : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean =
            incoming is Start || incoming is Pause
    }

    data object Pause : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean =
            incoming is Start || incoming is Pause
    }

    data class Seek(val positionMs: Long) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean = incoming is Seek
    }

    data class OutputGain(
        val value: Float,
        val onComplete: ((OutputGainApplyResult) -> Unit)? = null
    ) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean =
            onComplete == null && incoming is OutputGain
    }

    data class Balance(val value: Int) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean = incoming is Balance
    }

    data class ConfigureReplayGain(
        val mode: ReplayGainMode,
        val shuffling: Boolean
    ) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean =
            incoming is ConfigureReplayGain
    }

    data object Cancel : EngineCommand {
        override val clearsPending: Boolean get() = true
    }

    data object Release : EngineCommand {
        override val clearsPending: Boolean get() = true
    }
}

internal object PcmGain {
    const val UNITY = 1f
    const val MAX_LEVEL = 16f

    fun apply(
        pcm: FloatArray,
        offsetSamples: Int,
        sampleCount: Int,
        level: Float,
        balance: Int
    ) {
        require(offsetSamples >= 0) { "offsetSamples must not be negative" }
        require(sampleCount >= 0) { "sampleCount must not be negative" }
        require(offsetSamples + sampleCount <= pcm.size) {
            "gain range exceeds the PCM array"
        }
        require(offsetSamples % PcmFormat.CHANNELS == 0) {
            "offsetSamples must start on a PCM frame boundary"
        }
        require(sampleCount % PcmFormat.CHANNELS == 0) {
            "sampleCount must contain complete PCM frames"
        }
        if (level == UNITY && AudioBalance.isCentred(balance)) return

        val safeLevel = level.coerceIn(0f, MAX_LEVEL)
        val left = safeLevel * AudioBalance.leftGain(balance)
        val right = safeLevel * AudioBalance.rightGain(balance)
        val end = offsetSamples + sampleCount
        var index = offsetSamples
        while (index < end) {
            pcm[index] *= left
            pcm[index + 1] *= right
            index += PcmFormat.CHANNELS
        }
    }

    fun crossfadeInto(
        current: FloatArray,
        currentOffsetSamples: Int,
        next: FloatArray,
        nextOffsetSamples: Int,
        frameCount: Int,
        transitionFrame: Long,
        transitionFrames: Long,
        level: Float,
        nextLevel: Float = level,
        balance: Int
    ) {
        require(frameCount >= 0)
        require(currentOffsetSamples >= 0 && nextOffsetSamples >= 0)
        require(currentOffsetSamples % PcmFormat.CHANNELS == 0)
        require(nextOffsetSamples % PcmFormat.CHANNELS == 0)
        require(currentOffsetSamples + frameCount * PcmFormat.CHANNELS <= current.size)
        require(nextOffsetSamples + frameCount * PcmFormat.CHANNELS <= next.size)

        val leftBalance = AudioBalance.leftGain(balance)
        val rightBalance = AudioBalance.rightGain(balance)
        val span = transitionFrames.coerceAtLeast(1L).toFloat()
        val currentLevel = level.coerceIn(0f, MAX_LEVEL)
        val followingLevel = nextLevel.coerceIn(0f, MAX_LEVEL)
        var decodedFrameIndex = 0
        while (decodedFrameIndex < frameCount) {
            val fraction = ((transitionFrame + decodedFrameIndex).toFloat() / span)
                .coerceIn(0f, 1f)
            val currentGain = currentLevel * (1f - fraction)
            val nextGain = followingLevel * fraction
            val currentIndex = currentOffsetSamples + decodedFrameIndex * PcmFormat.CHANNELS
            val nextIndex = nextOffsetSamples + decodedFrameIndex * PcmFormat.CHANNELS
            val currentLeft = current[currentIndex]
            val currentRight = current[currentIndex + 1]
            val nextLeft = next[nextIndex]
            val nextRight = next[nextIndex + 1]
            current[currentIndex] =
                (currentLeft * currentGain + nextLeft * nextGain) * leftBalance
            current[currentIndex + 1] =
                (currentRight * currentGain + nextRight * nextGain) * rightBalance
            decodedFrameIndex += 1
        }
    }
}

internal class FfmpegPlaybackEngine(
    context: Context,
    private val logger: DiagnosticLogger,
    private val output: AudioOutput = AudioTrackOutput()
) : PlaybackEngine {
    internal class PcmBlock {
        val bytes: ByteBuffer = ByteBuffer
            .allocateDirect(PcmFormat.FLOAT_BLOCK_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        // Never narrow this view's limit. On API 19 that can leave the backing
        // ByteBuffer's limit reduced even after clear().
        private val floatView: FloatBuffer = bytes.asFloatBuffer()

        val pcm = FloatArray(PcmFormat.BLOCK_SAMPLES)

        var decodedFrameCount = NO_STAGED_BLOCK
            private set

        var consumedFrameCount = 0
            private set

        val hasStagedBlock: Boolean get() = decodedFrameCount != NO_STAGED_BLOCK

        val remainingFrameCount: Int
            get() = if (hasStagedBlock) decodedFrameCount - consumedFrameCount else 0

        val consumedSampleOffset: Int get() = consumedFrameCount * PcmFormat.CHANNELS

        fun stage(frameCount: Int) {
            require(frameCount in 0..PcmFormat.BLOCK_FRAMES) {
                "decoded frame count out of range: $frameCount"
            }
            floatView.clear()
            floatView.get(pcm, 0, frameCount * PcmFormat.CHANNELS)
            floatView.clear()
            decodedFrameCount = frameCount
            consumedFrameCount = 0
        }

        fun consume(frameCount: Int) {
            require(frameCount in 0..remainingFrameCount) {
                "cannot consume $frameCount of $remainingFrameCount frames"
            }
            consumedFrameCount += frameCount
            if (decodedFrameCount > 0 && consumedFrameCount == decodedFrameCount) discard()
        }

        fun discard() {
            decodedFrameCount = NO_STAGED_BLOCK
            consumedFrameCount = 0
        }

        companion object {
            private const val NO_STAGED_BLOCK = -1
        }
    }

    private class Slot(
        val decoder: NativeDecoder,
        val requestId: Long,
        val durationMs: Long,
        val replayGainMetadata: ReplayGainMetadata,
        var replayGain: ReplayGainAdjustment = ReplayGainAdjustment(),
        var firstPcmWriteLogged: Boolean = false,
        var completionCause: DecoderCompletionCause = DecoderCompletionCause.NATURAL
    )

    private val appContext = context.applicationContext
    private val thread = HandlerThread("y2-ffmpeg", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    // This explicit mailbox is also the oracle that distinguishes an expected
    // native abort from a decoder failure; ordinary Handler messages cannot do that.
    private val commandLock = Any()

    private val commands = ArrayDeque<EngineCommand>(MAX_PENDING_COMMANDS)
    private var commandDrainScheduled = false
    private var pumpScheduled = false

    private val currentBlock = PcmBlock()
    private val nextBlock = PcmBlock()
    private val completionPadding = FloatArray(PcmFormat.BLOCK_SAMPLES)

    private val wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y2Player:Playback")
        .apply { setReferenceCounted(false) }

    @Volatile private var listener: PlaybackEngine.Listener? = null

    @Volatile private var releaseRequested = false

    @Volatile private var engineState: EngineState = EngineState.EMPTY
    @Volatile private var transitioning = false
    @Volatile private var preparedNextRequestId: Long? = null
    @Volatile private var publishedDurationMs = 0L
    @Volatile private var publishedPositionMs = 0L

    @Volatile private var currentDecoder: NativeDecoder? = null
    @Volatile private var nextDecoder: NativeDecoder? = null

    private var current: Slot? = null
    private var next: Slot? = null
    private var balance = AudioBalance.CENTRE
    private var replayGainMode = ReplayGainMode.OFF
    private var shuffling = false
    private var seekBaseMs = 0L
    private var currentOutputStartFrame = 0L
    private var completionPending = false
    private var completionBoundaryFrame = 0L
    private var transitionFrames = 0L
    private var transitionedFrames = 0L
    private var transitionNextStartFrame = 0L
    private var pendingStartRequestId = 0L
    private var pendingTransitionRequestId = 0L
    private var pendingTransitionDurationMs = 0L
    private var pendingTransitionBoundaryFrame = 0L
    private var pendingOutputGain: EngineCommand.OutputGain? = null
    private var pendingOutputGainBoundaryFrame = OutputGainActivationPolicy.IMMEDIATE
    private var lastStarvationLogUptimeMs = 0L
    private var gaplessEnabled = true
    private var crossfadeMs = 0L
    private var nextTrackRequested = false

    override val state: EngineState get() = engineState
    override val isTransitioning: Boolean get() = transitioning
    override val audioSessionId: Int = output.audioSessionId

    init {
        try {
            check(audioSessionId > 0) { "AudioTrack did not allocate an audio session" }
            logger.info("PlaybackEngine", "FFmpeg runtime=${NativeDecoder.buildInformation()}")
            logger.info("PlaybackEngine", "AudioTrack ${output.configuration}")
        } catch (error: Throwable) {
            runCatching { thread.quitSafely() }
            runCatching { output.release() }
            throw error
        }
    }

    override fun setListener(listener: PlaybackEngine.Listener) {
        this.listener = listener
    }

    override fun prepare(track: Track, requestId: Long) {
        if (releaseRequested) return
        enqueue(EngineCommand.Prepare(track, requestId), abortCurrent = true, abortNext = true)
    }

    override fun configureTransition(gaplessEnabled: Boolean, crossfadeMs: Long) {
        if (releaseRequested) return
        enqueue(EngineCommand.ConfigureTransition(gaplessEnabled, crossfadeMs.coerceAtLeast(0L)))
    }

    override fun prepareNext(track: Track, requestId: Long) {
        if (releaseRequested) return
        enqueue(EngineCommand.PrepareNext(track, requestId), abortNext = true)
    }

    override fun clearNext() {
        if (releaseRequested) return
        enqueue(EngineCommand.ClearNext, abortNext = true)
    }

    override fun skipToPreparedNext(): Boolean {
        if (releaseRequested || preparedNextRequestId == null) return false
        enqueue(EngineCommand.SkipToPrepared)
        return true
    }

    override fun cancel() {
        if (releaseRequested) return
        enqueue(EngineCommand.Cancel, abortCurrent = true, abortNext = true)
    }

    override fun start() {
        if (releaseRequested) return
        enqueue(EngineCommand.Start)
    }

    override fun pause() {
        if (releaseRequested) return
        enqueue(EngineCommand.Pause)
    }

    override fun seekTo(positionMs: Long) {
        if (releaseRequested) return
        enqueue(EngineCommand.Seek(positionMs.coerceAtLeast(0L)), abortCurrent = true)
    }

    override fun setOutputGain(
        gain: Float,
        onComplete: ((OutputGainApplyResult) -> Unit)?
    ) {
        if (releaseRequested) {
            onComplete?.invoke(OutputGainApplyResult.RELEASED)
            return
        }
        enqueue(EngineCommand.OutputGain(gain.coerceIn(0f, 1f), onComplete))
    }

    override fun setBalance(balance: Int) {
        if (releaseRequested) return
        enqueue(EngineCommand.Balance(AudioBalance.clamp(balance)))
    }

    override fun configureReplayGain(mode: ReplayGainMode, shuffling: Boolean) {
        if (releaseRequested) return
        enqueue(EngineCommand.ConfigureReplayGain(mode, shuffling))
    }

    override fun currentPositionMs(): Long = publishedPositionMs

    override fun durationMs(): Long = publishedDurationMs

    override fun isPlaying(): Boolean = engineState == EngineState.PLAYING

    override fun release() {
        if (releaseRequested) return
        releaseRequested = true
        enqueue(EngineCommand.Release, abortCurrent = true, abortNext = true)
    }

    private fun enqueue(
        command: EngineCommand,
        abortCurrent: Boolean = false,
        abortNext: Boolean = false
    ) {
        var scheduleDrain = false
        var discardedAcknowledgements: ArrayList<EngineCommand.OutputGain>? = null
        synchronized(commandLock) {
            if (command.clearsPending) {
                commands.forEach {
                    if (it is EngineCommand.OutputGain && it.onComplete != null) {
                        val discarded = discardedAcknowledgements
                            ?: ArrayList<EngineCommand.OutputGain>(1).also {
                                discardedAcknowledgements = it
                            }
                        discarded += it
                    }
                }
                commands.clear()
            }
            else commands.removeAll { it.isSupersededBy(command) }

            if (commands.size >= MAX_PENDING_COMMANDS) {
                val evicted = commands.removeFirst()
                if (evicted is EngineCommand.OutputGain && evicted.onComplete != null) {
                    val discarded = discardedAcknowledgements
                        ?: ArrayList<EngineCommand.OutputGain>(1).also {
                            discardedAcknowledgements = it
                        }
                    discarded += evicted
                }
            }
            commands.addLast(command)

            // Publish the superseding command before aborting old JNI work. Otherwise
            // a late abort can poison the decoder opened for the new command.
            if (abortCurrent) currentDecoder?.requestAbort()
            if (abortNext) nextDecoder?.requestAbort()

            if (!commandDrainScheduled) {
                commandDrainScheduled = true
                scheduleDrain = true
            }
        }
        val discardResult = if (command is EngineCommand.Release) {
            OutputGainApplyResult.RELEASED
        } else {
            OutputGainApplyResult.CANCELLED
        }
        discardedAcknowledgements?.forEach { completeOutputGain(it, discardResult) }
        if (scheduleDrain) handler.post(commandDrain)
    }

    private fun hasPendingCommands(): Boolean = synchronized(commandLock) { commands.isNotEmpty() }

    private val commandDrain = object : Runnable {
        override fun run() {
            var processed = 0
            while (processed < COMMANDS_PER_TURN) {
                val command = synchronized(commandLock) {
                    commands.pollFirst().also {
                        if (it == null) commandDrainScheduled = false
                    }
                } ?: break
                try {
                    perform(command)
                    confirmOutputGainActivation()
                } catch (error: Throwable) {
                    containCommandFailure(command, error)
                }
                processed += 1
            }

            val reschedule = synchronized(commandLock) {
                if (commands.isNotEmpty()) true
                else {
                    commandDrainScheduled = false
                    false
                }
            }
            if (reschedule) handler.post(this)
            else if (engineState == EngineState.PLAYING) schedulePump()
        }
    }

    private fun containCommandFailure(command: EngineCommand, error: Throwable) {
        if (command is EngineCommand.OutputGain) {
            completeOutputGain(command, OutputGainApplyResult.FAILED)
        }
        containEngineFailure("command=${command.javaClass.simpleName}", error)
    }

    private fun containEngineFailure(origin: String, error: Throwable) {
        val requestId = current?.requestId ?: 0L
        logger.error(
            "PlaybackEngine",
            "$origin state=$engineState request=$requestId " +
                "failed unexpectedly; engine reset so a later prepare still works",
            error
        )
        pendingStartRequestId = 0L
        discardPendingTransition()
        cancelPendingOutputGain(OutputGainApplyResult.FAILED)
        runCatching { output.pause() }
        runCatching { closeCurrent() }
        runCatching { closeNext() }
        clearCompletion()
        setTransitioning(false)
        runCatching { output.flush() }
        publishedPositionMs = 0L
        seekBaseMs = 0L
        currentOutputStartFrame = 0L
        if (engineState != EngineState.RELEASED) enterState(EngineState.ERROR)
        if (requestId > 0L) {
            listener?.onError(
                requestId,
                "engine command failed: ${error.message ?: error.javaClass.simpleName}",
                PlaybackFailure.UNKNOWN
            )
        }
    }

    private fun perform(command: EngineCommand) {
        when (command) {
            is EngineCommand.Prepare -> performPrepare(command)
            is EngineCommand.ConfigureTransition -> {
                gaplessEnabled = command.gaplessEnabled
                crossfadeMs = command.crossfadeMs
            }
            is EngineCommand.PrepareNext -> performPrepareNext(command)
            EngineCommand.ClearNext -> performClearNext()
            EngineCommand.SkipToPrepared -> performSkipToPrepared()
            EngineCommand.Start -> performStart()
            EngineCommand.Pause -> performPause()
            is EngineCommand.Seek -> performSeek(command.positionMs)
            is EngineCommand.OutputGain -> performOutputGain(command)
            is EngineCommand.Balance -> balance = command.value
            is EngineCommand.ConfigureReplayGain -> {
                replayGainMode = command.mode
                shuffling = command.shuffling
                current?.updateReplayGain()
                next?.updateReplayGain()
            }
            EngineCommand.Cancel -> performCancel()
            EngineCommand.Release -> performRelease()
        }
    }

    private fun completeOutputGain(
        command: EngineCommand.OutputGain,
        result: OutputGainApplyResult
    ) {
        runCatching { command.onComplete?.invoke(result) }
            .onFailure {
                logger.warn(
                    "PlaybackEngine",
                    "output-gain acknowledgement failed: ${it.javaClass.simpleName}"
                )
            }
    }

    private fun performOutputGain(command: EngineCommand.OutputGain) {
        cancelPendingOutputGain(OutputGainApplyResult.CANCELLED)
        output.setOutputGain(command.value)
        if (command.onComplete == null) return

        val playedAtApplication = output.playedFrames
        val confirmationFrame = OutputGainActivationPolicy.confirmationFrame(
            engineState = engineState,
            outputIsPlaying = output.isPlaying,
            playedFrames = playedAtApplication
        )
        if (confirmationFrame == OutputGainActivationPolicy.IMMEDIATE) {
            completeOutputGain(command, OutputGainApplyResult.APPLIED)
            return
        }
        pendingOutputGain = command
        pendingOutputGainBoundaryFrame = confirmationFrame
        logger.info(
            "PlaybackEngine",
            "output gain applied gain=${command.value} awaitingFrame=$confirmationFrame " +
                "playedFrame=$playedAtApplication state=$engineState"
        )
    }

    private fun confirmOutputGainActivation() {
        val command = pendingOutputGain ?: return
        val played = output.playedFrames
        if (!OutputGainActivationPolicy.isActive(
                confirmationFrame = pendingOutputGainBoundaryFrame,
                engineState = engineState,
                outputIsPlaying = output.isPlaying,
                playedFrames = played
            )
        ) return
        pendingOutputGain = null
        val boundary = pendingOutputGainBoundaryFrame
        pendingOutputGainBoundaryFrame = OutputGainActivationPolicy.IMMEDIATE
        logger.info(
            "PlaybackEngine",
            "output gain active gain=${command.value} playedFrame=$played boundaryFrame=$boundary"
        )
        completeOutputGain(command, OutputGainApplyResult.APPLIED)
    }

    private fun cancelPendingOutputGain(result: OutputGainApplyResult) {
        val command = pendingOutputGain ?: return
        pendingOutputGain = null
        pendingOutputGainBoundaryFrame = OutputGainActivationPolicy.IMMEDIATE
        completeOutputGain(command, result)
    }

    private fun enterState(next: EngineState) {
        engineState = next
        syncWakeLock()
    }

    private fun setTransitioning(value: Boolean) {
        transitioning = value
    }

    private fun syncWakeLock() {
        val required = engineState == EngineState.PREPARING || engineState == EngineState.PLAYING
        when {
            required && !wakeLock.isHeld -> wakeLock.acquire()
            !required && wakeLock.isHeld -> wakeLock.release()
        }
    }

    private fun closeCurrent() {
        current?.decoder?.close()
        current = null
        currentDecoder = null
        currentBlock.discard()
    }

    private fun closeNext() {
        next?.decoder?.close()
        next = null
        nextDecoder = null
        nextBlock.discard()
        preparedNextRequestId = null
    }

    private fun Slot.updateReplayGain() {
        replayGain = ReplayGain.resolve(replayGainMode, shuffling, replayGainMetadata)
    }

    private fun ReplayGainAdjustment.logValue(): String = when (source) {
        ReplayGainSource.NONE -> "replayGain=none"
        else -> "replayGain=${source.name.lowercase()} gainDb=$gainDb factor=$linearGain " +
            "peak=${peak ?: "unknown"} limited=$clippingPrevented"
    }

    private fun performPrepare(command: EngineCommand.Prepare) {
        cancelPendingOutputGain(OutputGainApplyResult.CANCELLED)
        closeCurrent()
        closeNext()
        discardPendingTransition()
        pendingStartRequestId = 0L
        clearCompletion()
        setTransitioning(false)
        transitionFrames = 0L
        transitionedFrames = 0L
        nextTrackRequested = false
        runCatching { output.stop() }
        runCatching { output.flush() }
        publishedPositionMs = 0L
        publishedDurationMs = 0L
        seekBaseMs = 0L
        currentOutputStartFrame = 0L
        enterState(EngineState.PREPARING)

        val decoder = NativeDecoder()
        // Publish before open so a superseding request can interrupt a cold-card open.
        currentDecoder = decoder
        try {
            val info = decoder.open(command.track.absolutePath, PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS)
            val slot = Slot(decoder, command.requestId, info.durationMs, info.replayGain)
            slot.updateReplayGain()
            current = slot
            publishedDurationMs = info.durationMs
            enterState(EngineState.READY)
            logger.info(
                "PlaybackEngine",
                "prepared request=${command.requestId} codec=${info.codecName} " +
                    "source=${info.sourceSampleRate}Hz/${info.sourceChannels}ch duration=${info.durationMs} " +
                    slot.replayGain.logValue()
            )
            listener?.onPrepared(command.requestId, info.durationMs)
        } catch (error: NativeDecoderException) {
            decoder.close()
            currentDecoder = null
            if (isSupersededAbort(error)) {
                enterState(EngineState.EMPTY)
            } else {
                failCurrent(command.requestId, "prepare", error)
            }
        } catch (error: Throwable) {
            decoder.close()
            currentDecoder = null
            failCurrent(command.requestId, "prepare", error)
        }
    }

    private fun performPrepareNext(command: EngineCommand.PrepareNext) {
        closeNext()
        if (engineState !in PLAYABLE_STATES) return

        val decoder = NativeDecoder()
        nextDecoder = decoder
        try {
            val info = decoder.open(command.track.absolutePath, PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS)
            val slot = Slot(decoder, command.requestId, info.durationMs, info.replayGain)
            slot.updateReplayGain()
            next = slot
            preparedNextRequestId = command.requestId
            logger.info(
                "PlaybackEngine",
                "preloaded request=${command.requestId} codec=${info.codecName} " +
                    "gapless=$gaplessEnabled crossfadeMs=$crossfadeMs ${slot.replayGain.logValue()}"
            )
            listener?.onNextPrepared(command.requestId, info.durationMs)
        } catch (error: NativeDecoderException) {
            discardFailedNext(decoder)
            if (!isSupersededAbort(error)) failNext(command.requestId, "preload", error)
        } catch (error: Throwable) {
            discardFailedNext(decoder)
            failNext(command.requestId, "preload", error)
        }
    }

    private fun discardFailedNext(decoder: NativeDecoder) {
        next = null
        preparedNextRequestId = null
        nextDecoder = null
        nextBlock.discard()
        runCatching { decoder.close() }
    }

    private fun performClearNext() {
        closeNext()
        nextTrackRequested = false
        setTransitioning(false)
        transitionFrames = 0L
        transitionedFrames = 0L
    }

    private fun performSkipToPrepared() {
        val prepared = next ?: return
        promoteWithFlush(prepared)
    }

    private fun promoteWithFlush(prepared: Slot) {
        cancelPendingOutputGain(OutputGainApplyResult.CANCELLED)
        val previousRequestId = current?.requestId ?: 0L
        closeCurrent()
        current = prepared
        currentDecoder = prepared.decoder
        next = null
        nextDecoder = null
        preparedNextRequestId = null
        nextBlock.discard()
        nextTrackRequested = false
        discardPendingTransition()
        setTransitioning(false)
        transitionFrames = 0L
        transitionedFrames = 0L
        clearCompletion()
        output.flush()
        currentOutputStartFrame = 0L
        seekBaseMs = 0L
        publishedPositionMs = 0L
        publishedDurationMs = prepared.durationMs
        enterState(EngineState.PLAYING)
        output.resume()
        logger.info(
            "PlaybackEngine",
            "output resumed request=${prepared.requestId} playing=${output.isPlaying} transition=standard"
        )
        notifyPromotion(previousRequestId, prepared)
        scheduleTransitionAnnouncement(
            prepared.requestId,
            prepared.durationMs,
            FIRST_AUDIBLE_OUTPUT_FRAME
        )
        schedulePump()
    }

    private fun notifyPromotion(previousRequestId: Long, promoted: Slot) {
        listener?.onTrackPromoted(previousRequestId, promoted.requestId, promoted.durationMs)
    }

    private fun maybeScheduleTransition() {
        if (transitioning || completionPending) return
        val slot = current ?: return
        val duration = publishedDurationMs
        if (duration <= 0L) return
        val remainingMs = duration - submittedPositionMs()

        if (!nextTrackRequested &&
            next == null &&
            NearEndPreloadPolicy.isWithinWindow(remainingMs, crossfadeMs)
        ) {
            nextTrackRequested = true
            listener?.onNextTrackNeeded(slot.requestId)
        }

        if (crossfadeMs > 0L && next != null && remainingMs in 1..crossfadeMs) {
            beginCrossfade(remainingMs.coerceAtLeast(MIN_CROSSFADE_MS))
        }
    }

    private fun beginCrossfade(durationMs: Long) {
        if (current == null || next == null) return
        transitionFrames = (durationMs * PcmFormat.SAMPLE_RATE / 1_000L).coerceAtLeast(1L)
        transitionedFrames = 0L
        transitionNextStartFrame = output.submittedFrames
        setTransitioning(true)
        logger.info(
            "PlaybackEngine",
            "crossfade begins durationMs=$durationMs frames=$transitionFrames"
        )
    }

    private fun submittedPositionMs(): Long {
        val submittedForTrack = (output.submittedFrames - currentOutputStartFrame)
            .coerceAtLeast(0L)
        return seekBaseMs + submittedForTrack * 1_000L / PcmFormat.SAMPLE_RATE
    }

    private fun performStart() {
        if (current == null || engineState !in STARTABLE_STATES) return
        val hasRetainedAudio = output.submittedFrames > output.playedFrames
        output.resume()
        enterState(EngineState.PLAYING)
        pendingStartRequestId = current?.requestId ?: 0L
        if (hasRetainedAudio) confirmStartedIfPending()
        schedulePump()
    }

    private fun performPause() {
        output.pause()
        if (engineState != EngineState.RELEASED) enterState(EngineState.PAUSED)
        pendingStartRequestId = 0L
        announcePendingTransition()
        updatePublishedPosition()
    }

    private fun performSeek(positionMs: Long) {
        val slot = current ?: return
        cancelPendingOutputGain(OutputGainApplyResult.CANCELLED)
        val shouldResume = engineState == EngineState.PLAYING
        clearCompletion()
        if (transitioning) performClearNext()
        nextTrackRequested = false
        currentBlock.discard()
        val target = if (slot.durationMs > 0L) {
            positionMs.coerceAtMost(slot.durationMs)
        } else {
            positionMs
        }
        try {
            slot.decoder.seekTo(target)
            slot.completionCause = DecoderCompletionCause.SEEK
            output.flush()
            retargetPendingTransitionAfterFlush()
            seekBaseMs = target
            currentOutputStartFrame = 0L
            publishedPositionMs = target
            if (shouldResume) {
                output.resume()
                schedulePump()
            }
            logger.info(
                "PlaybackEngine",
                "seek applied request=${slot.requestId} requestedMs=$positionMs targetMs=$target " +
                    "durationMs=${slot.durationMs} resumed=$shouldResume"
            )
        } catch (error: NativeDecoderException) {
            if (!isSupersededAbort(error)) failCurrent(slot.requestId, "seek", error)
        } catch (error: Throwable) {
            failCurrent(slot.requestId, "seek", error)
        }
    }

    private fun performCancel() {
        cancelPendingOutputGain(OutputGainApplyResult.CANCELLED)
        closeCurrent()
        performClearNext()
        discardPendingTransition()
        pendingStartRequestId = 0L
        clearCompletion()
        runCatching { output.stop() }
        runCatching { output.flush() }
        if (engineState != EngineState.RELEASED && engineState != EngineState.PREPARING) {
            enterState(EngineState.EMPTY)
        }
        publishedPositionMs = 0L
        publishedDurationMs = 0L
        seekBaseMs = 0L
        currentOutputStartFrame = 0L
    }

    private fun performRelease() {
        cancelPendingOutputGain(OutputGainApplyResult.RELEASED)
        closeCurrent()
        performClearNext()
        discardPendingTransition()
        pendingStartRequestId = 0L
        clearCompletion()
        runCatching { output.stop() }
        enterState(EngineState.RELEASED)
        try {
            output.release()
            logger.info("PlaybackEngine", "released")
        } finally {
            thread.quitSafely()
        }
    }

    private fun schedulePump(delayMs: Long = 0L) {
        if (pumpScheduled || engineState != EngineState.PLAYING) return
        pumpScheduled = true
        if (delayMs <= 0L) handler.post(decodePump)
        else handler.postDelayed(decodePump, delayMs)
    }

    private val decodePump = object : Runnable {
        override fun run() {
            try {
                runDecodePumpIteration()
            } catch (error: Throwable) {
                containEngineFailure("decode pump", error)
            }
        }
    }

    private fun runDecodePumpIteration() {
        pumpScheduled = false
        if (engineState != EngineState.PLAYING) return
        if (hasPendingCommands()) {
            val shouldPost = synchronized(commandLock) {
                if (!commandDrainScheduled) {
                    commandDrainScheduled = true
                    true
                } else false
            }
            if (shouldPost) handler.post(commandDrain)
            return
        }

        confirmOutputGainActivation()

        announceTransitionIfAudible()

        if (completionPending) {
            updatePublishedPosition()
            if (PlaybackCompletionDrain.contentWasPlayed(
                    output.playedFrames,
                    completionBoundaryFrame
                )
            ) finishCompletion()
            else schedulePump(COMPLETION_CHECK_MS)
            return
        }

        try {
            if (transitioning) pumpCrossfade() else pumpCurrent()
        } catch (error: NativeDecoderException) {
            val slot = current
            if (!isSupersededAbort(error) && slot != null) {
                failCurrent(slot.requestId, "decode", error)
            }
        } catch (error: Throwable) {
            current?.let { failCurrent(it.requestId, "output", error, PlaybackFailure.TRANSIENT) }
        }
        if (engineState == EngineState.PLAYING) schedulePump()
    }

    private fun pumpCurrent() {
        val slot = current ?: return
        if (!currentBlock.hasStagedBlock) {
            currentBlock.stage(slot.decoder.decode(currentBlock.bytes, PcmFormat.BLOCK_FRAMES))
        }
        val frameCount = currentBlock.remainingFrameCount
        if (frameCount == 0) {
            when (DecoderEofPolicy.action(next != null, gaplessEnabled, crossfadeMs)) {
                DecoderEofAction.PROMOTE_GAPLESS -> promoteGapless()
                DecoderEofAction.DRAIN_CURRENT_OUTPUT -> beginCompletion(slot)
            }
            return
        }

        val offsetSamples = currentBlock.consumedSampleOffset
        val sampleCount = frameCount * PcmFormat.CHANNELS
        PcmGain.apply(
            currentBlock.pcm,
            offsetSamples,
            sampleCount,
            slot.replayGain.linearGain,
            balance
        )
        output.write(currentBlock.pcm, offsetSamples, sampleCount)
        noteFirstPcmWrite(slot, frameCount, "current")
        currentBlock.consume(frameCount)
        updatePublishedPosition()
        confirmStartedIfPending()
        maybeScheduleTransition()
        checkForStarvation()
    }

    private fun pumpCrossfade() {
        val currentSlot = current ?: return
        val nextSlot = next ?: run {
            setTransitioning(false)
            return
        }

        if (hasPendingCommands()) return
        if (!currentBlock.hasStagedBlock) {
            currentBlock.stage(currentSlot.decoder.decode(currentBlock.bytes, PcmFormat.BLOCK_FRAMES))
        }
        if (hasPendingCommands()) return
        if (!nextBlock.hasStagedBlock) {
            nextBlock.stage(nextSlot.decoder.decode(nextBlock.bytes, PcmFormat.BLOCK_FRAMES))
        }

        val currentRemaining = currentBlock.remainingFrameCount
        val nextRemaining = nextBlock.remainingFrameCount

        if (currentRemaining == 0) {
            finishCrossfade(nextSlot)
            return
        }
        if (nextRemaining == 0) {
            failNext(
                nextSlot.requestId,
                "crossfade",
                IllegalStateException("next track ended during crossfade")
            )
            performClearNext()
            return
        }

        // Codecs return different AVFrame sizes. Consume only the overlap and keep
        // the longer block's remainder; padding it would distort time and add silence.
        val mixFrameCount = min(currentRemaining, nextRemaining)
        val currentOffsetSamples = currentBlock.consumedSampleOffset
        PcmGain.crossfadeInto(
            currentBlock.pcm,
            currentOffsetSamples,
            nextBlock.pcm,
            nextBlock.consumedSampleOffset,
            mixFrameCount,
            transitionedFrames,
            transitionFrames,
            currentSlot.replayGain.linearGain,
            nextSlot.replayGain.linearGain,
            balance
        )
        output.write(
            currentBlock.pcm,
            currentOffsetSamples,
            mixFrameCount * PcmFormat.CHANNELS
        )
        noteFirstPcmWrite(currentSlot, mixFrameCount, "crossfade-current")
        noteFirstPcmWrite(nextSlot, mixFrameCount, "crossfade-next")
        currentBlock.consume(mixFrameCount)
        nextBlock.consume(mixFrameCount)
        transitionedFrames += mixFrameCount
        updatePublishedPosition()
        confirmStartedIfPending()

        if (transitionedFrames >= transitionFrames) finishCrossfade(nextSlot)
    }

    private fun promoteGapless() {
        val old = current ?: return
        val prepared = next ?: return
        val boundary = output.submittedFrames
        current = prepared
        currentDecoder = prepared.decoder
        next = null
        nextDecoder = null
        preparedNextRequestId = null
        currentBlock.discard()
        nextBlock.discard()
        nextTrackRequested = false
        currentOutputStartFrame = boundary
        seekBaseMs = 0L
        publishedPositionMs = 0L
        publishedDurationMs = prepared.durationMs
        old.decoder.close()
        notifyPromotion(old.requestId, prepared)
        scheduleTransitionAnnouncement(prepared.requestId, prepared.durationMs, boundary)
    }

    private fun finishCrossfade(prepared: Slot) {
        val old = current
        val boundary = output.submittedFrames
        current = prepared
        currentDecoder = prepared.decoder
        next = null
        nextDecoder = null
        preparedNextRequestId = null
        currentBlock.discard()
        nextBlock.discard()
        nextTrackRequested = false
        currentOutputStartFrame = transitionNextStartFrame
        seekBaseMs = 0L
        publishedDurationMs = prepared.durationMs
        setTransitioning(false)
        transitionFrames = 0L
        transitionedFrames = 0L
        val previousRequestId = old?.requestId ?: 0L
        old?.decoder?.close()
        updatePublishedPosition()
        notifyPromotion(previousRequestId, prepared)
        scheduleTransitionAnnouncement(prepared.requestId, prepared.durationMs, boundary)
    }

    private fun finishCompletion() {
        val slot = current ?: return
        clearCompletion()
        announcePendingTransition()
        val prepared = next
        if (prepared != null) {
            promoteWithFlush(prepared)
            return
        }
        enterState(EngineState.READY)
        publishedPositionMs = slot.durationMs.coerceAtLeast(publishedPositionMs)
        listener?.onCompleted(slot.requestId)
    }

    private fun beginCompletion(slot: Slot) {
        val plan = PlaybackCompletionDrain.plan(
            output.submittedFrames,
            output.bufferFrameCount
        )
        completionBoundaryFrame = plan.contentBoundaryFrame
        completionPending = true
        repeat(plan.paddingBlockCount) {
            output.write(completionPadding, 0, completionPadding.size)
        }
        logger.info(
            "PlaybackEngine",
            "decoder EOF request=${slot.requestId} cause=${slot.completionCause.diagnosticId} " +
                "contentEndFrame=" +
                "${plan.contentBoundaryFrame} drainPaddingFrames=${plan.paddingFrameCount}"
        )
        updatePublishedPosition()
    }

    private fun clearCompletion() {
        completionPending = false
        completionBoundaryFrame = 0L
    }

    private fun noteFirstPcmWrite(slot: Slot, frameCount: Int, path: String) {
        if (slot.firstPcmWriteLogged) return
        slot.firstPcmWriteLogged = true
        logger.info(
            "PlaybackEngine",
            "first PCM written request=${slot.requestId} frames=$frameCount path=$path " +
                "playing=${output.isPlaying} submittedFrames=${output.submittedFrames}"
        )
    }

    private fun scheduleTransitionAnnouncement(
        requestId: Long,
        durationMs: Long,
        boundaryFrame: Long
    ) {
        pendingTransitionRequestId = requestId
        pendingTransitionDurationMs = durationMs
        pendingTransitionBoundaryFrame = boundaryFrame
    }

    private fun announceTransitionIfAudible() {
        if (pendingTransitionRequestId <= 0L) return
        if (output.playedFrames < pendingTransitionBoundaryFrame) return
        announcePendingTransition()
    }

    private fun announcePendingTransition() {
        val requestId = pendingTransitionRequestId
        if (requestId <= 0L) return
        val durationMs = pendingTransitionDurationMs
        discardPendingTransition()
        listener?.onTransitioned(requestId, durationMs)
    }

    private fun discardPendingTransition() {
        pendingTransitionRequestId = 0L
        pendingTransitionDurationMs = 0L
        pendingTransitionBoundaryFrame = 0L
    }

    private fun retargetPendingTransitionAfterFlush() {
        if (pendingTransitionRequestId > 0L) {
            pendingTransitionBoundaryFrame = FIRST_AUDIBLE_OUTPUT_FRAME
        }
    }

    private fun confirmStartedIfPending() {
        val requestId = pendingStartRequestId
        if (requestId <= 0L) return
        pendingStartRequestId = 0L
        listener?.onStarted(requestId)
    }

    private fun checkForStarvation() {
        if (engineState != EngineState.PLAYING || completionPending) return
        val submitted = output.submittedFrames
        if (submitted < STARVATION_MIN_SUBMITTED_FRAMES) return
        val queuedFrames = submitted - output.playedFrames
        if (queuedFrames >= STARVATION_FRAMES) return
        val now = SystemClock.uptimeMillis()
        if (now - lastStarvationLogUptimeMs < STARVATION_LOG_INTERVAL_MS) return
        lastStarvationLogUptimeMs = now
        logger.warn(
            "PlaybackEngine",
            "output starving: queuedFrames=$queuedFrames " +
                "(~${queuedFrames * 1_000L / PcmFormat.SAMPLE_RATE} ms) transitioning=$transitioning"
        )
    }

    private fun updatePublishedPosition() {
        val playedForTrack = (output.playedFrames - currentOutputStartFrame).coerceAtLeast(0L)
        val calculated = seekBaseMs + playedForTrack * 1_000L / PcmFormat.SAMPLE_RATE
        publishedPositionMs = if (publishedDurationMs > 0L) {
            calculated.coerceAtMost(publishedDurationMs)
        } else {
            calculated
        }
    }

    private fun isSupersededAbort(error: NativeDecoderException): Boolean =
        error.category == NativeErrorCategory.ABORTED && hasPendingCommands()

    private fun failCurrent(
        requestId: Long,
        operation: String,
        error: Throwable,
        explicitFailure: PlaybackFailure? = null
    ) {
        cancelPendingOutputGain(OutputGainApplyResult.FAILED)
        clearCompletion()
        setTransitioning(false)
        pendingStartRequestId = 0L
        discardPendingTransition()
        runCatching { output.pause() }
        runCatching { closeCurrent() }
        runCatching { performClearNext() }
        runCatching { enterState(EngineState.ERROR) }
        val failure = explicitFailure ?: when ((error as? NativeDecoderException)?.category) {
            NativeErrorCategory.UNSUPPORTED, NativeErrorCategory.CORRUPT -> PlaybackFailure.UNSUPPORTED
            NativeErrorCategory.SOURCE, NativeErrorCategory.ABORTED -> PlaybackFailure.TRANSIENT
            else -> PlaybackFailure.UNKNOWN
        }
        val message = "$operation failed: ${error.message ?: error.javaClass.simpleName}"
        logger.error("PlaybackEngine", "request=$requestId $message", error)
        listener?.onError(requestId, message, failure)
    }

    private fun failNext(requestId: Long, operation: String, error: Throwable) {
        val message = "$operation failed: ${error.message ?: error.javaClass.simpleName}"
        logger.warn("PlaybackEngine", "preload=$requestId $message")
        listener?.onNextError(requestId, message)
    }

    companion object {
        private const val COMPLETION_CHECK_MS = 20L
        private const val MAX_PENDING_COMMANDS = 32
        private const val COMMANDS_PER_TURN = 8
        private const val MIN_CROSSFADE_MS = 100L
        private const val FIRST_AUDIBLE_OUTPUT_FRAME = 1L
        private const val STARVATION_LOG_INTERVAL_MS = 5_000L
        private const val STARVATION_FRAMES = PcmFormat.BLOCK_FRAMES / 2
        private const val STARVATION_MIN_SUBMITTED_FRAMES = PcmFormat.BLOCK_FRAMES * 2L
        private val PLAYABLE_STATES = setOf(EngineState.READY, EngineState.PLAYING, EngineState.PAUSED)
        private val STARTABLE_STATES = PLAYABLE_STATES
    }
}
