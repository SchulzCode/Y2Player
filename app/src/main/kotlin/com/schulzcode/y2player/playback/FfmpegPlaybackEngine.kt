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
import java.nio.ShortBuffer
import java.util.ArrayDeque
import kotlin.math.min

/**
 * One request to the playback engine.
 *
 * Supersession and cancellation policy lives on the commands themselves. It was
 * previously stated three times over: once as a sealed hierarchy, once as a
 * parallel `EngineCommandKind` enum, and once as a hand-written mapping between
 * them, which had to be kept in agreement by hand.
 */
internal sealed interface EngineCommand {

    /** True when this command invalidates everything already queued. */
    val clearsPending: Boolean get() = false

    /** True when [incoming], enqueued later, makes this queued command pointless. */
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

    /** An explicit skip to the prepared next track. Timed transitions are internal. */
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

    data class Volume(val value: Float) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean = incoming is Volume
    }

    data class Balance(val value: Int) : EngineCommand {
        override fun isSupersededBy(incoming: EngineCommand): Boolean = incoming is Balance
    }

    data object Cancel : EngineCommand {
        override val clearsPending: Boolean get() = true
    }

    data object Release : EngineCommand {
        override val clearsPending: Boolean get() = true
    }
}

/**
 * Gain, balance and crossfade mixing over decoded PCM16.
 *
 * Everything here works on `ShortArray`. The previous implementation walked a
 * `ByteBufferAsShortBuffer` with absolute `get(index)`/`put(index)`, which on
 * Dalvik is a bounds check, an index scale and a two-byte little-endian
 * assembly per sample — 88,200 times per second of audio.
 */
internal object PcmGain {

    /** No attenuation. Compared with `>=` so a rounded gain can never re-enable the loop. */
    const val UNITY = 1f

    /**
     * Scales [decodedShortCount] interleaved stereo shorts in place.
     *
     * Returns immediately at unity gain with centred balance, which is the
     * shipping default and would otherwise rewrite every sample with its own
     * value.
     */
    fun apply(
        pcm: ShortArray,
        offsetShorts: Int,
        decodedShortCount: Int,
        level: Float,
        balance: Int
    ) {
        require(offsetShorts >= 0) { "offsetShorts must not be negative" }
        require(decodedShortCount >= 0) { "decodedShortCount must not be negative" }
        require(offsetShorts + decodedShortCount <= pcm.size) {
            "gain range exceeds the PCM array"
        }
        require(offsetShorts % PcmFormat.CHANNELS == 0) {
            "offsetShorts must start on a PCM frame boundary"
        }
        require(decodedShortCount % PcmFormat.CHANNELS == 0) {
            "decodedShortCount must contain complete PCM frames"
        }
        if (level >= UNITY && AudioBalance.isCentred(balance)) return

        val safeLevel = level.coerceIn(0f, UNITY)
        val left = safeLevel * AudioBalance.leftGain(balance)
        val right = safeLevel * AudioBalance.rightGain(balance)
        val end = offsetShorts + decodedShortCount
        var index = offsetShorts
        while (index < end) {
            pcm[index] = saturate(pcm[index] * left)
            pcm[index + 1] = saturate(pcm[index + 1] * right)
            index += PcmFormat.CHANNELS
        }
    }

    /**
     * Mixes [frameCount] frames of [next] into [current], in place.
     *
     * Both sides supply exactly [frameCount] frames, each read from its own
     * offset. The caller is responsible for passing
     * `min(currentRemaining, nextRemaining)`.
     *
     * This used to mix over `max()` and substitute silence for whichever side
     * ran short. That was wrong: one native decode call returns **one converted
     * AVFrame**, so the two decoders routinely return different counts — 1152
     * for MP3, 1024 for AAC, commonly 4096 for FLAC. Consuming both blocks
     * regardless stretched the shorter side to `short/long` of real speed with
     * the remainder fabricated as silence. A short positive decode is not EOF.
     *
     * Reads and writes share an index within [current], so mixing in place is
     * safe and no third PCM block is needed.
     *
     * The law is linear amplitude, deliberately: the two gains sum to exactly
     * one, so a summed peak cannot exceed full scale for correlated material.
     * An equal-power law would sound more even through the midpoint but sums to
     * about +3 dB, which on a fixed-point output path means clipping unless
     * headroom is reserved for every track.
     */
    fun crossfadeInto(
        current: ShortArray,
        currentOffsetShorts: Int,
        next: ShortArray,
        nextOffsetShorts: Int,
        frameCount: Int,
        transitionFrame: Long,
        transitionFrames: Long,
        level: Float,
        balance: Int
    ) {
        require(frameCount >= 0)
        require(currentOffsetShorts >= 0 && nextOffsetShorts >= 0)
        require(currentOffsetShorts % PcmFormat.CHANNELS == 0)
        require(nextOffsetShorts % PcmFormat.CHANNELS == 0)
        require(currentOffsetShorts + frameCount * PcmFormat.CHANNELS <= current.size)
        require(nextOffsetShorts + frameCount * PcmFormat.CHANNELS <= next.size)

        val leftBalance = AudioBalance.leftGain(balance)
        val rightBalance = AudioBalance.rightGain(balance)
        val span = transitionFrames.coerceAtLeast(1L).toFloat()
        for (decodedFrameIndex in 0 until frameCount) {
            val fraction = ((transitionFrame + decodedFrameIndex).toFloat() / span)
                .coerceIn(0f, 1f)
            val currentGain = level * (1f - fraction)
            val nextGain = level * fraction
            val currentIndex = currentOffsetShorts + decodedFrameIndex * PcmFormat.CHANNELS
            val nextIndex = nextOffsetShorts + decodedFrameIndex * PcmFormat.CHANNELS
            val currentLeft = current[currentIndex].toInt()
            val currentRight = current[currentIndex + 1].toInt()
            val nextLeft = next[nextIndex].toInt()
            val nextRight = next[nextIndex + 1].toInt()
            current[currentIndex] =
                saturate((currentLeft * currentGain + nextLeft * nextGain) * leftBalance)
            current[currentIndex + 1] =
                saturate((currentRight * currentGain + nextRight * nextGain) * rightBalance)
        }
    }

    private fun saturate(value: Float): Short =
        value.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

/**
 * The sole playback implementation.
 *
 * One audio-priority thread owns both native decoders, both PCM blocks, the
 * transition state, the wake lock and the single AudioTrack. Every field that
 * crosses a thread boundary is written by that thread and only read elsewhere;
 * public methods enqueue work and never mutate playback state themselves.
 */
internal class FfmpegPlaybackEngine(
    context: Context,
    private val logger: DiagnosticLogger,
    private val output: AudioOutput = AudioTrackOutput()
) : PlaybackEngine {

    /**
     * One reusable decode destination.
     *
     * Two of these exist for the life of the engine. Previously a fresh 16 KB
     * direct buffer was allocated per `Slot`, so every prepare and every preload
     * orphaned one for the finalizer to reclaim.
     *
     * [decodedFrameCount] doubles as a hold: frames that have left the decoder
     * but that the current pump turn could not consume stay staged here until
     * the next turn takes them, instead of being deleted.
     */
    /**
     * One reusable decode destination, consumable in parts.
     *
     * Two of these exist for the life of the engine. A staged block is not
     * necessarily written in one turn: a crossfade mixes only as many frames as
     * both decoders can supply, so the longer block keeps its remainder and the
     * next turn continues from [consumedFrameCount] rather than decoding past it.
     *
     * [decodedFrameCount] also doubles as the staged/held marker.
     * [NO_STAGED_BLOCK] means nothing is staged; a staged **zero** is the
     * decoder's EOF report and is deliberately sticky.
     */
    internal class PcmBlock {
        val bytes: ByteBuffer = ByteBuffer
            .allocateDirect(PcmFormat.BLOCK_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)

        /**
         * The only use of a ShortBuffer view anywhere in the pipeline: one bulk
         * copy per staged block. Its limit is never narrowed, because on API 19
         * a narrowed `ByteBufferAsShortBuffer` can leave the backing ByteBuffer's
         * limit reduced even after `clear()`.
         */
        private val shortView: ShortBuffer = bytes.asShortBuffer()

        val pcm = ShortArray(PcmFormat.BLOCK_SHORTS)

        var decodedFrameCount = NO_STAGED_BLOCK
            private set

        var consumedFrameCount = 0
            private set

        val hasStagedBlock: Boolean get() = decodedFrameCount != NO_STAGED_BLOCK

        val remainingFrameCount: Int
            get() = if (hasStagedBlock) decodedFrameCount - consumedFrameCount else 0

        /** Where the unconsumed remainder starts, in PCM16 shorts. */
        val consumedShortOffset: Int get() = consumedFrameCount * PcmFormat.CHANNELS

        /** A staged block of zero frames: the decoder reported end of stream. */
        val atEndOfStream: Boolean get() = decodedFrameCount == 0

        fun stage(frameCount: Int) {
            require(frameCount in 0..PcmFormat.BLOCK_FRAMES) {
                "decoded frame count out of range: $frameCount"
            }
            shortView.clear()
            shortView.get(pcm, 0, frameCount * PcmFormat.CHANNELS)
            shortView.clear()
            decodedFrameCount = frameCount
            consumedFrameCount = 0
        }

        /**
         * Marks [frameCount] frames written. A block consumed to its end is
         * released so the next turn decodes a fresh one; an EOF block is kept,
         * because the decoder will keep reporting EOF.
         */
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
        val durationMs: Long
    )

    private val appContext = context.applicationContext
    private val thread = HandlerThread("y2-ffmpeg", Process.THREAD_PRIORITY_AUDIO).apply { start() }
    private val handler = Handler(thread.looper)
    private val commandLock = Any()

    /**
     * Retained rather than replaced by `Handler` messages: [hasPendingCommands]
     * is the oracle that decides whether a native abort was expected, and it has
     * to be exact. A Handler cannot distinguish a queued command from the queued
     * decode pump, so the same guarantee would need a side-channel counter that
     * can drift.
     */
    private val commands = ArrayDeque<EngineCommand>(MAX_PENDING_COMMANDS)
    private var commandDrainScheduled = false
    private var pumpScheduled = false

    private val currentBlock = PcmBlock()
    private val nextBlock = PcmBlock()

    private val wakeLock = (appContext.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Y2Player:Playback")
        .apply { setReferenceCounted(false) }

    @Volatile private var listener: PlaybackEngine.Listener? = null

    /** Latched by [release] on the caller thread; the only caller-thread write. */
    @Volatile private var releaseRequested = false

    // Written by the engine thread only; volatile so other threads read them.
    @Volatile private var engineState: EngineState = EngineState.EMPTY
    @Volatile private var transitioning = false
    @Volatile private var preparedNextRequestId: Long? = null
    @Volatile private var publishedDurationMs = 0L
    @Volatile private var publishedPositionMs = 0L

    /**
     * The decoders a superseding command may abort.
     *
     * Published as soon as a decoder is constructed — before `open`, so a slow
     * `avformat_open_input` can be cut short — and cleared when it is closed.
     * `NativeDecoder.requestAbort` is safe on a closed decoder, so a caller
     * racing a close signals nothing rather than touching a freed handle. This
     * replaces the previous non-atomic `abortTarget`/`abortRole` pair, which
     * could route an abort to whichever decoder happened to be in JNI.
     */
    @Volatile private var currentDecoder: NativeDecoder? = null
    @Volatile private var nextDecoder: NativeDecoder? = null

    // Engine-thread-only.
    private var current: Slot? = null
    private var next: Slot? = null
    private var masterVolume = PcmGain.UNITY
    private var balance = AudioBalance.CENTRE
    private var seekBaseMs = 0L
    private var currentOutputStartFrame = 0L
    private var completionPending = false
    private var transitionFrames = 0L
    private var transitionedFrames = 0L
    private var transitionNextStartFrame = 0L
    private var pendingStartRequestId = 0L
    private var pendingTransitionRequestId = 0L
    private var pendingTransitionDurationMs = 0L
    private var pendingTransitionBoundaryFrame = 0L
    private var lastStarvationLogUptimeMs = 0L
    private var gaplessEnabled = true
    private var crossfadeMs = 0L
    /** One request for the next track per current track. Reset on every promotion. */
    private var nextTrackRequested = false

    override val state: EngineState get() = engineState
    override val isTransitioning: Boolean get() = transitioning
    // Assigned by its initialiser rather than inside init, so the try/catch
    // below cannot interfere with definite assignment of a val.
    override val audioSessionId: Int = output.audioSessionId

    init {
        // The HandlerThread is started by its property initialiser, so anything
        // that throws below would strand it. No retry is attempted here: a
        // failed AudioTrack usually means another app holds the resource, and
        // the service already falls back to UnavailablePlaybackEngine.
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

    // ---------------------------------------------------------------------
    // Caller-thread API. These enqueue and, where a blocking native call has to
    // be cut short, signal an abort. The command is queued *before* the abort so
    // that an ABORTED error can never surface with an empty mailbox.
    // ---------------------------------------------------------------------

    override fun prepare(track: Track, requestId: Long) {
        if (releaseRequested) return
        enqueue(EngineCommand.Prepare(track, requestId))
        currentDecoder?.requestAbort()
        nextDecoder?.requestAbort()
    }

    override fun configureTransition(gaplessEnabled: Boolean, crossfadeMs: Long) {
        if (releaseRequested) return
        enqueue(EngineCommand.ConfigureTransition(gaplessEnabled, crossfadeMs.coerceAtLeast(0L)))
    }

    override fun prepareNext(track: Track, requestId: Long) {
        if (releaseRequested) return
        enqueue(EngineCommand.PrepareNext(track, requestId))
        nextDecoder?.requestAbort()
    }

    override fun clearNext() {
        if (releaseRequested) return
        enqueue(EngineCommand.ClearNext)
        nextDecoder?.requestAbort()
    }

    override fun skipToPreparedNext(): Boolean {
        if (releaseRequested || preparedNextRequestId == null) return false
        enqueue(EngineCommand.SkipToPrepared)
        return true
    }

    override fun cancel() {
        if (releaseRequested) return
        enqueue(EngineCommand.Cancel)
        currentDecoder?.requestAbort()
        nextDecoder?.requestAbort()
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
        enqueue(EngineCommand.Seek(positionMs.coerceAtLeast(0L)))
        currentDecoder?.requestAbort()
    }

    override fun setVolume(volume: Float) {
        if (releaseRequested) return
        enqueue(EngineCommand.Volume(volume.coerceIn(0f, PcmGain.UNITY)))
    }

    override fun setBalance(balance: Int) {
        if (releaseRequested) return
        enqueue(EngineCommand.Balance(AudioBalance.clamp(balance)))
    }

    override fun currentPositionMs(): Long = publishedPositionMs

    override fun durationMs(): Long = publishedDurationMs

    override fun isPlaying(): Boolean = engineState == EngineState.PLAYING

    override fun release() {
        if (releaseRequested) return
        releaseRequested = true
        enqueue(EngineCommand.Release)
        currentDecoder?.requestAbort()
        nextDecoder?.requestAbort()
    }

    private fun enqueue(command: EngineCommand) {
        var scheduleDrain = false
        synchronized(commandLock) {
            if (command.clearsPending) commands.clear()
            else commands.removeAll { it.isSupersededBy(command) }

            if (commands.size >= MAX_PENDING_COMMANDS) {
                // Coalescing above normally prevents this. If a burst still
                // fills the fixed mailbox, the oldest non-terminal request is
                // less authoritative than the new command.
                commands.removeFirst()
            }
            commands.addLast(command)
            if (!commandDrainScheduled) {
                commandDrainScheduled = true
                scheduleDrain = true
            }
        }
        if (scheduleDrain) handler.post(commandDrain)
    }

    private fun hasPendingCommands(): Boolean = synchronized(commandLock) { commands.isNotEmpty() }

    // ---------------------------------------------------------------------
    // Engine thread. Sole writer of every field above.
    // ---------------------------------------------------------------------

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

    /**
     * Last-resort containment for a command handler that threw.
     *
     * An uncaught throwable on this looper would terminate the engine thread,
     * orphaning both native decoders and the AudioTrack with nothing left alive
     * to report it or to accept a new request. Every step here is best-effort
     * and must not throw again.
     *
     * The AudioTrack is paused and flushed but deliberately **not** released:
     * the audio session id is fixed for the life of the engine and the effects
     * backend is attached to it, so releasing here would silently detach every
     * effect the user configured.
     */
    private fun containCommandFailure(command: EngineCommand, error: Throwable) {
        containEngineFailure("command=${command.javaClass.simpleName}", error)
    }

    /**
     * Last-resort containment for anything that threw on the engine thread.
     *
     * Both loops route here. [origin] names which one, so a decode-pump failure
     * is not filed as a command failure.
     *
     * The AudioTrack is paused and flushed but deliberately **not** released: the
     * audio session id is fixed for the life of the engine and the effects
     * backend is attached to it, so releasing here would silently detach every
     * effect the user configured.
     *
     * Listener notification happens at most once per failure: [failCurrent]
     * clears `current` before returning, so if this runs after it the request id
     * resolves to 0 and nothing further is published.
     */
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
        runCatching { output.pause() }
        runCatching { closeCurrent() }
        runCatching { closeNext() }
        completionPending = false
        setTransitioning(false)
        runCatching { output.flush() }
        publishedPositionMs = 0L
        seekBaseMs = 0L
        currentOutputStartFrame = 0L
        // ERROR, not RELEASED: prepare() must still be accepted afterwards.
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
            is EngineCommand.Volume -> masterVolume = command.value
            is EngineCommand.Balance -> balance = command.value
            EngineCommand.Cancel -> performCancel()
            EngineCommand.Release -> performRelease()
        }
    }

    /** The one place playback state changes, and therefore the one wake-lock owner. */
    private fun enterState(next: EngineState) {
        engineState = next
        syncWakeLock()
    }

    private fun setTransitioning(value: Boolean) {
        transitioning = value
    }

    /**
     * The single wake-lock invariant.
     *
     * Acquire and release were previously scattered across seven call sites on
     * two threads, so a release from the engine thread could land after an
     * acquire from the service thread and drop the lock while audio was still
     * playing — a stall with the screen off, because AudioTrack holds no wake
     * lock of its own.
     */
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

    private fun performPrepare(command: EngineCommand.Prepare) {
        closeCurrent()
        closeNext()
        discardPendingTransition()
        pendingStartRequestId = 0L
        completionPending = false
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
        // Published before open so a superseding command can interrupt a slow
        // avformat_open_input on a cold card.
        currentDecoder = decoder
        try {
            val info = decoder.open(command.track.absolutePath, PcmFormat.SAMPLE_RATE, PcmFormat.CHANNELS)
            current = Slot(decoder, command.requestId, info.durationMs)
            publishedDurationMs = info.durationMs
            enterState(EngineState.READY)
            logger.info(
                "PlaybackEngine",
                "prepared request=${command.requestId} codec=${info.codecName} " +
                    "source=${info.sourceSampleRate}Hz/${info.sourceChannels}ch duration=${info.durationMs}"
            )
            listener?.onPrepared(command.requestId, info.durationMs)
        } catch (error: NativeDecoderException) {
            decoder.close()
            currentDecoder = null
            if (isSupersededAbort(error)) {
                // A newer command is already queued and will set the next state.
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
            next = Slot(decoder, command.requestId, info.durationMs)
            preparedNextRequestId = command.requestId
            logger.info(
                "PlaybackEngine",
                "preloaded request=${command.requestId} codec=${info.codecName} " +
                    "gapless=$gaplessEnabled crossfadeMs=$crossfadeMs"
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

    /**
     * Clears every trace of a failed preload before reporting it.
     *
     * `next` and `preparedNextRequestId` are published before the listener is
     * told, so a throw from the listener could otherwise leave a *closed*
     * decoder visible as the prepared next track. Promoting it would then fail
     * on the first decode.
     */
    private fun discardFailedNext(decoder: NativeDecoder) {
        next = null
        preparedNextRequestId = null
        nextDecoder = null
        nextBlock.discard()
        runCatching { decoder.close() }
    }

    private fun performClearNext() {
        closeNext()
        // Let the engine ask again: the service usually clears a preload because
        // the queue changed, which means a different next track is now wanted.
        nextTrackRequested = false
        setTransitioning(false)
        transitionFrames = 0L
        transitionedFrames = 0L
    }

    /** An explicit skip: cut to the prepared track now, discarding the tail. */
    private fun performSkipToPrepared() {
        val prepared = next ?: return
        promoteWithFlush(prepared)
    }

    /**
     * Promotes the prepared track, restarting the output clock.
     *
     * Used for an explicit skip, and for an ordinary (non-gapless) track change
     * once the previous track has finished draining. Because the output is
     * flushed, the new track is audible from frame zero and the transition is
     * announced straight away rather than deferred to a boundary.
     */
    private fun promoteWithFlush(prepared: Slot) {
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
        completionPending = false
        output.flush()
        currentOutputStartFrame = 0L
        seekBaseMs = 0L
        publishedPositionMs = 0L
        publishedDurationMs = prepared.durationMs
        enterState(EngineState.PLAYING)
        output.resume()
        notifyPromotion(previousRequestId, prepared)
        // A flushed output starts this track at frame zero, so it is audible at
        // once and the two callbacks coincide.
        listener?.onTransitioned(prepared.requestId, prepared.durationMs)
        schedulePump()
    }

    /**
     * Announces that the engine has changed which decoder it owns.
     *
     * Separate from [PlaybackEngine.Listener.onTransitioned], which is deferred
     * until the new track is *audible*. Ownership moves inside the engine the
     * instant it promotes, and for gapless that is up to one AudioTrack buffer
     * before the boundary is heard. During that window the service still
     * believed the old track was current, so a pause could run `clearPreload()`
     * and destroy the only request id the later transition callback was gated
     * on — leaving audio on the new track and the queue, notification and UI on
     * the old one.
     */
    private fun notifyPromotion(previousRequestId: Long, promoted: Slot) {
        listener?.onTrackPromoted(previousRequestId, promoted.requestId, promoted.durationMs)
    }

    /**
     * Decides, per pump turn, when to ask for the next track and when to start
     * the crossfade.
     *
     * This is the work that used to live in the service's progress loop, where
     * it ran on a 250 ms timer and could only ever be approximately on time.
     */
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

    /**
     * Position measured in *submitted* frames rather than played ones.
     *
     * A transition has to be mixed before it can be heard, so it is scheduled
     * against what has been handed to the output, not against what the listener
     * is hearing one buffer-depth earlier.
     */
    private fun submittedPositionMs(): Long {
        val submittedForTrack = (output.submittedFrames - currentOutputStartFrame)
            .coerceAtLeast(0L)
        return seekBaseMs + submittedForTrack * 1_000L / PcmFormat.SAMPLE_RATE
    }

    private fun performStart() {
        if (current == null || engineState !in STARTABLE_STATES) return
        output.resume()
        enterState(EngineState.PLAYING)
        // Confirmed by the pump once PCM has actually reached the track.
        pendingStartRequestId = current?.requestId ?: 0L
        schedulePump()
    }

    private fun performPause() {
        output.pause()
        if (engineState != EngineState.RELEASED) enterState(EngineState.PAUSED)
        completionPending = false
        pendingStartRequestId = 0L
        // A transition is frozen, not discarded: the mixed frames already
        // submitted stay submitted, transitionedFrames keeps its value and the
        // prepared next decoder is retained, so resume continues the crossfade
        // from where it stopped instead of snapping the outgoing track back to
        // full gain.
        announcePendingTransition()
        updatePublishedPosition()
    }

    private fun performSeek(positionMs: Long) {
        val slot = current ?: return
        val shouldResume = engineState == EngineState.PLAYING
        completionPending = false
        // Seeking the outgoing track invalidates any crossfade in progress.
        if (transitioning) performClearNext()
        // Remaining time has changed; let the engine re-decide when to ask.
        nextTrackRequested = false
        announcePendingTransition()
        currentBlock.discard()
        val target = if (slot.durationMs > 0L) {
            positionMs.coerceAtMost(slot.durationMs)
        } else {
            positionMs
        }
        try {
            slot.decoder.seekTo(target)
            output.flush()
            seekBaseMs = target
            currentOutputStartFrame = 0L
            publishedPositionMs = target
            if (shouldResume) {
                output.resume()
                schedulePump()
            }
        } catch (error: NativeDecoderException) {
            if (!isSupersededAbort(error)) failCurrent(slot.requestId, "seek", error)
        } catch (error: Throwable) {
            failCurrent(slot.requestId, "seek", error)
        }
    }

    private fun performCancel() {
        closeCurrent()
        performClearNext()
        discardPendingTransition()
        pendingStartRequestId = 0L
        completionPending = false
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
        closeCurrent()
        performClearNext()
        discardPendingTransition()
        pendingStartRequestId = 0L
        completionPending = false
        runCatching { output.stop() }
        enterState(EngineState.RELEASED)
        // The thread must stop even if the output or the logger throws on the
        // way out; otherwise the engine thread outlives the engine and the
        // service's shutdown latch simply times out.
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
            // Throwable, not Exception, and around the whole iteration rather
            // than only the decode call: an uncaught throwable here terminates
            // the engine HandlerThread, which on this device means playback is
            // silently dead until the service is recreated. Transition
            // announcement, completion finalisation and promotion all sit on
            // this path and can reach AudioTrack.
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

        announceTransitionIfAudible()

        if (completionPending) {
            updatePublishedPosition()
            if (output.playedFrames >= output.submittedFrames) finishCompletion()
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
        // A crossfade superseded mid-turn can leave a partly consumed block
        // here; continue from its offset rather than decoding past it.
        if (!currentBlock.hasStagedBlock) {
            currentBlock.stage(slot.decoder.decode(currentBlock.bytes, PcmFormat.BLOCK_FRAMES))
        }
        val frameCount = currentBlock.remainingFrameCount
        if (frameCount == 0) {
            // Only a staged zero reaches here: a fully consumed block releases
            // itself and is re-decoded above.
            // Gapless joins across the buffer boundary without flushing, so the
            // two tracks are contiguous. Every other case drains first, which
            // keeps the tail of the current track instead of cutting it off.
            if (next != null && gaplessEnabled && crossfadeMs <= 0L) promoteGapless()
            else {
                completionPending = true
                updatePublishedPosition()
            }
            return
        }

        val offsetShorts = currentBlock.consumedShortOffset
        val decodedShortCount = frameCount * PcmFormat.CHANNELS
        PcmGain.apply(currentBlock.pcm, offsetShorts, decodedShortCount, masterVolume, balance)
        output.write(currentBlock.pcm, offsetShorts, decodedShortCount)
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

        // Neither native read is entered while a superseding command is already
        // waiting, so this turn cannot produce a block it is unable to consume.
        if (hasPendingCommands()) return
        if (!currentBlock.hasStagedBlock) {
            currentBlock.stage(currentSlot.decoder.decode(currentBlock.bytes, PcmFormat.BLOCK_FRAMES))
        }
        // A queue edit, volume step or balance change that arrived during the
        // decode above is handled before the next file is read. The frames have
        // already left the decoder, so the block stays staged and the following
        // turn consumes it.
        if (hasPendingCommands()) return
        if (!nextBlock.hasStagedBlock) {
            nextBlock.stage(nextSlot.decoder.decode(nextBlock.bytes, PcmFormat.BLOCK_FRAMES))
        }

        val currentRemaining = currentBlock.remainingFrameCount
        val nextRemaining = nextBlock.remainingFrameCount

        // Zero remaining can only mean a staged zero, i.e. real end of stream.
        // A short positive decode is not EOF and must never be padded.
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

        // Only as many frames as both sides can supply. One native decode call
        // returns one converted AVFrame, so these counts differ per codec —
        // 1152 for MP3, 1024 for AAC, commonly 4096 for FLAC. The longer block
        // keeps its remainder for the next turn.
        val mixFrameCount = min(currentRemaining, nextRemaining)
        val currentOffsetShorts = currentBlock.consumedShortOffset
        PcmGain.crossfadeInto(
            currentBlock.pcm,
            currentOffsetShorts,
            nextBlock.pcm,
            nextBlock.consumedShortOffset,
            mixFrameCount,
            transitionedFrames,
            transitionFrames,
            masterVolume,
            balance
        )
        output.write(
            currentBlock.pcm,
            currentOffsetShorts,
            mixFrameCount * PcmFormat.CHANNELS
        )
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
        completionPending = false
        announcePendingTransition()
        val prepared = next
        if (prepared != null) {
            // The previous track has finished draining; join to the next one.
            promoteWithFlush(prepared)
            return
        }
        enterState(EngineState.READY)
        publishedPositionMs = slot.durationMs.coerceAtLeast(publishedPositionMs)
        listener?.onCompleted(slot.requestId)
    }

    /**
     * Records a track change that has been *submitted* but is not yet audible.
     *
     * Everything the service does on a transition — advancing the queue, the
     * notification, the recently-played row — used to happen up to one full
     * AudioTrack buffer before the listener could hear it.
     */
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

    /** Delivers a pending transition now, because its audio is committed. */
    private fun announcePendingTransition() {
        val requestId = pendingTransitionRequestId
        if (requestId <= 0L) return
        val durationMs = pendingTransitionDurationMs
        discardPendingTransition()
        listener?.onTransitioned(requestId, durationMs)
    }

    /** Drops a pending transition whose audio is being thrown away. */
    private fun discardPendingTransition() {
        pendingTransitionRequestId = 0L
        pendingTransitionDurationMs = 0L
        pendingTransitionBoundaryFrame = 0L
    }

    /**
     * Confirms a start only once PCM has actually reached the track.
     *
     * `engine.state == PLAYING` immediately after `start()` proved nothing: the
     * caller thread had just written that value itself.
     */
    private fun confirmStartedIfPending() {
        val requestId = pendingStartRequestId
        if (requestId <= 0L) return
        pendingStartRequestId = 0L
        listener?.onStarted(requestId)
    }

    /**
     * Rate-limited starvation warning.
     *
     * `AudioTrack.getUnderrunCount()` is API 24, but the engine already counts
     * submitted and played frames, and their difference is the queued depth.
     * Ignored while draining to EOF or before the track has had a chance to
     * fill, so a normal end-of-track is never reported as starvation.
     */
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
        // Primitive branch rather than `takeIf { it > 0 } ?: Long.MAX_VALUE`:
        // that boxes a java.lang.Long on every decoded block.
        publishedPositionMs = if (publishedDurationMs > 0L) {
            calculated.coerceAtMost(publishedDurationMs)
        } else {
            calculated
        }
    }

    /**
     * Whether an abort was this engine's own doing.
     *
     * Aborts are only ever raised by a public method, and every public method
     * enqueues its command *before* signalling, so a superseded native call
     * always finds its replacement already in the mailbox. That replaces the
     * previous cross-thread `expectedCommandAbort` boolean, which leaked `true`
     * whenever an abort arrived after its native call had already returned and
     * could then swallow an unrelated real failure.
     */
    private fun isSupersededAbort(error: NativeDecoderException): Boolean =
        error.category == NativeErrorCategory.ABORTED && hasPendingCommands()

    private fun failCurrent(
        requestId: Long,
        operation: String,
        error: Throwable,
        explicitFailure: PlaybackFailure? = null
    ) {
        completionPending = false
        setTransitioning(false)
        pendingStartRequestId = 0L
        discardPendingTransition()
        // Every cleanup step is individually guarded: if one of them threw, the
        // throwable would escape into the pump's containment handler, which
        // would then see `current` still set and publish a second failure for
        // the same request.
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
        private const val STARVATION_LOG_INTERVAL_MS = 5_000L
        private const val STARVATION_FRAMES = PcmFormat.BLOCK_FRAMES / 2
        private const val STARVATION_MIN_SUBMITTED_FRAMES = PcmFormat.BLOCK_FRAMES * 2L
        private val PLAYABLE_STATES = setOf(EngineState.READY, EngineState.PLAYING, EngineState.PAUSED)
        private val STARTABLE_STATES = PLAYABLE_STATES
    }
}
