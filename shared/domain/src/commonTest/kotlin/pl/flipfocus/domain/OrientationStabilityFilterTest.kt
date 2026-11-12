package pl.flipfocus.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class OrientationStabilityFilterTest {
    @Test fun downRequiresContinuousStability() {
        val filter = OrientationStabilityFilter()
        assertEquals(DeviceOrientation.UNKNOWN, filter.update(-9.81, 9.81, 0))
        assertEquals(DeviceOrientation.UNKNOWN, filter.update(-9.81, 9.81, 300))
        assertEquals(DeviceOrientation.UNKNOWN, filter.update(-9.81, 9.81, 599))
        assertEquals(DeviceOrientation.FACE_DOWN, filter.update(-9.81, 9.81, 600))
    }

    @Test fun joltDoesNotInterruptButSustainedLiftDoes() {
        val filter = OrientationStabilityFilter()
        listOf(0L, 300L, 600L).forEach { filter.update(-1.0, 1.0, it) }
        assertEquals(DeviceOrientation.FACE_DOWN, filter.update(0.0, 1.0, 700))
        assertEquals(DeviceOrientation.FACE_DOWN, filter.update(-1.0, 1.0, 800))
        assertEquals(DeviceOrientation.FACE_DOWN, filter.update(0.0, 1.0, 900))
        assertEquals(DeviceOrientation.FACE_DOWN, filter.update(0.0, 1.0, 1_099))
        assertEquals(DeviceOrientation.LIFTED, filter.update(0.0, 1.0, 1_100))
    }

    @Test fun hysteresisAvoidsOscillationWhileHoldingAtAngle() {
        val filter = OrientationStabilityFilter()
        listOf(0L, 300L, 600L).forEach { filter.update(-1.0, 1.0, it) }
        assertEquals(DeviceOrientation.FACE_DOWN, filter.update(-0.85, 1.0, 800))
        assertEquals(DeviceOrientation.FACE_DOWN, filter.update(-0.85, 1.0, 1_000))
    }

    @Test fun staleSamplesAndInvalidValuesInvalidateOrientation() {
        val filter = OrientationStabilityFilter()
        listOf(0L, 300L, 600L).forEach { filter.update(-1.0, 1.0, it) }
        assertEquals(DeviceOrientation.UNKNOWN, filter.update(-1.0, 1.0, 2_000))
        assertEquals(DeviceOrientation.UNKNOWN, filter.update(Double.NaN, 1.0, 2_100))
        assertEquals(DeviceOrientation.UNKNOWN, filter.update(-1.0, 0.0, 2_200))
    }

    @Test fun restartingClockRequiresFreshStableSamples() {
        val filter = OrientationStabilityFilter()
        listOf(1_000L, 1_300L, 1_600L).forEach { filter.update(-1.0, 1.0, it) }
        assertEquals(DeviceOrientation.UNKNOWN, filter.update(-1.0, 1.0, 100))
    }
}
