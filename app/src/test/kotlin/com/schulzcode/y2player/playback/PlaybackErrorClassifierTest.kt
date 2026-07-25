package com.schulzcode.y2player.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The classifier decides whether a failure is remembered against a file, so a
 * false positive means a working track is labelled unplayable forever. These
 * tests exist mostly to pin down what must *not* be recorded.
 */
class PlaybackErrorClassifierTest {

    private companion object {
        const val ERROR_UNKNOWN = 1
        const val SERVER_DIED = 100
        const val EXTRA_UNSUPPORTED = -1010
        const val EXTRA_MALFORMED = -1007
        const val EXTRA_IO = -1004
        const val EXTRA_TIMED_OUT = -110
        const val EXTRA_SYSTEM = -2147483648
    }

    @Test fun theFrameworkBlamingTheMediaIsRecorded() {
        assertEquals(
            PlaybackFailure.UNSUPPORTED,
            PlaybackErrorClassifier.classify(ERROR_UNKNOWN, EXTRA_UNSUPPORTED)
        )
        assertEquals(
            PlaybackFailure.UNSUPPORTED,
            PlaybackErrorClassifier.classify(ERROR_UNKNOWN, EXTRA_MALFORMED)
        )
    }

    /**
     * The case that made this a separate class. A device log from this player
     * showed `MEDIA_ERROR_SERVER_DIED` arriving because mediaserver died during a
     * USB eject — the file was fine, and marking it would have been wrong.
     */
    @Test fun mediaserverDyingIsNeverBlamedOnTheFile() {
        assertEquals(PlaybackFailure.TRANSIENT, PlaybackErrorClassifier.classify(SERVER_DIED, 0))
        // Even when it arrives alongside a code that would otherwise be damning.
        assertEquals(
            PlaybackFailure.TRANSIENT,
            PlaybackErrorClassifier.classify(SERVER_DIED, EXTRA_UNSUPPORTED)
        )
    }

    @Test fun ioAndTimeoutAreTransient() {
        assertEquals(PlaybackFailure.TRANSIENT, PlaybackErrorClassifier.classify(ERROR_UNKNOWN, EXTRA_IO))
        assertEquals(PlaybackFailure.TRANSIENT, PlaybackErrorClassifier.classify(ERROR_UNKNOWN, EXTRA_TIMED_OUT))
    }

    /** Anything ambiguous is left unrecorded rather than guessed at. */
    @Test fun unrecognisedCodesAreNotRecorded() {
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackErrorClassifier.classify(ERROR_UNKNOWN, 0))
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackErrorClassifier.classify(ERROR_UNKNOWN, EXTRA_SYSTEM))
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackErrorClassifier.classify(0, 0))
        assertEquals(PlaybackFailure.UNKNOWN, PlaybackErrorClassifier.classify(42, 99))
    }
}
