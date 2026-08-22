package com.schulzcode.y2player.input

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelAccelerationTest {
    @Test fun slowDetentsAlwaysMoveOneRow() {
        val acceleration = WheelAcceleration()
        assertEquals(1, acceleration.delta(1, 0, enabled = true))
        assertEquals(1, acceleration.delta(1, 250, enabled = true))
        assertEquals(1, acceleration.delta(1, 500, enabled = true))
    }

    @Test fun sustainedMovementUsesBoundedTiers() {
        val acceleration = WheelAcceleration()
        assertEquals(1, acceleration.delta(1, 0, enabled = true))
        assertEquals(1, acceleration.delta(1, 150, enabled = true))
        assertEquals(3, acceleration.delta(1, 300, enabled = true))
        assertEquals(3, acceleration.delta(1, 380, enabled = true))
        assertEquals(5, acceleration.delta(1, 460, enabled = true))
        assertEquals(5, acceleration.delta(1, 540, enabled = true))
    }

    @Test fun slowingRestoresPrecisionImmediately() {
        val acceleration = WheelAcceleration()
        repeat(5) { acceleration.delta(1, it * 70L, enabled = true) }
        assertEquals(1, acceleration.delta(1, 500, enabled = true))
        assertEquals(1, acceleration.delta(1, 700, enabled = true))
    }

    @Test fun directionChangeRestoresPrecisionImmediately() {
        val acceleration = WheelAcceleration()
        repeat(5) { acceleration.delta(1, it * 70L, enabled = true) }
        assertEquals(-1, acceleration.delta(-1, 360, enabled = true))
        assertEquals(-1, acceleration.delta(-1, 430, enabled = true))
        assertEquals(-3, acceleration.delta(-1, 500, enabled = true))
    }

    @Test fun disabledScreensNeverAccumulateAcceleration() {
        val acceleration = WheelAcceleration()
        repeat(8) { index -> assertEquals(1, acceleration.delta(1, index * 40L, enabled = false)) }
        assertEquals(1, acceleration.delta(1, 400, enabled = true))
    }

    @Test fun noMovementIsQueuedAfterInputStops() {
        val acceleration = WheelAcceleration()
        repeat(5) { acceleration.delta(1, it * 70L, enabled = true) }
        assertEquals(1, acceleration.delta(1, 1_000, enabled = true))
    }

    @Test fun explicitResetMakesTheNextDetentPrecise() {
        val acceleration = WheelAcceleration()
        repeat(5) { acceleration.delta(1, it * 70L, enabled = true) }

        acceleration.reset()

        assertEquals(1, acceleration.delta(1, 300, enabled = true))
    }

    @Test fun veryFastSustainedMovementEntersAlphabetTier() {
        val acceleration = WheelAcceleration()
        var movement: WheelMovement = WheelMovement.Rows(0)

        repeat(9) { index ->
            movement = acceleration.movement(1, index * 50L, enabled = true, alphabetEnabled = true)
        }

        assertEquals(WheelMovement.Alphabet(1), movement)
    }

    @Test fun alphabetTierFollowsDirectionChangesWithoutAlsoMovingRows() {
        val acceleration = WheelAcceleration()
        repeat(9) { index ->
            acceleration.movement(1, index * 50L, enabled = true, alphabetEnabled = true)
        }

        assertEquals(
            WheelMovement.Alphabet(-1),
            acceleration.movement(-1, 440L, enabled = true, alphabetEnabled = true)
        )
    }

    @Test fun slowingDownLeavesAlphabetTierForExistingRowAcceleration() {
        val acceleration = WheelAcceleration()
        repeat(9) { index ->
            acceleration.movement(1, index * 50L, enabled = true, alphabetEnabled = true)
        }

        assertEquals(
            WheelMovement.Rows(3),
            acceleration.movement(1, 540L, enabled = true, alphabetEnabled = true)
        )
    }

    @Test fun alphabetTierIsNeverUsedWhenTheVisibleOrderIsIneligible() {
        val acceleration = WheelAcceleration()
        repeat(12) { index ->
            val movement = acceleration.movement(
                1,
                index * 40L,
                enabled = true,
                alphabetEnabled = false
            )
            assertEquals(WheelMovement.Rows(if (index < 2) 1 else if (index < 4) 3 else 5), movement)
        }
    }
}
