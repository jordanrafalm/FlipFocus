package pl.flipfocus.domain

/**
 * Accepts normalized or physical gravity units; platform adapters align Z to Android's convention.
 * Face-down is negative Z. Hysteresis and a stable interval reject threshold noise and brief jolts.
 */
class OrientationStabilityFilter(
    private val downThreshold: Double = -0.90,
    private val liftedThreshold: Double = -0.80,
    private val downStabilityMillis: Long = 600,
    private val liftedStabilityMillis: Long = 200,
    private val maxSampleGapMillis: Long = 500,
) {
    init {
        require(downThreshold in -1.0..1.0 && liftedThreshold in -1.0..1.0)
        require(downThreshold < liftedThreshold)
        require(downStabilityMillis >= 0 && liftedStabilityMillis >= 0 && maxSampleGapMillis > 0)
    }

    private var orientation = DeviceOrientation.UNKNOWN
    private var candidate = DeviceOrientation.UNKNOWN
    private var candidateSince: Long? = null
    private var lastSample: Long? = null

    fun update(gravityZ: Double, gravityMagnitude: Double, monotonicMillis: Long): DeviceOrientation {
        if (!gravityZ.isFinite() || !gravityMagnitude.isFinite() || gravityMagnitude <= 0) return invalidate()
        val normalizedZ = gravityZ / gravityMagnitude
        if (normalizedZ !in -1.01..1.01) return invalidate()
        val previousSample = lastSample
        if (previousSample != null && (monotonicMillis < previousSample || monotonicMillis - previousSample > maxSampleGapMillis)) {
            invalidate()
        }
        lastSample = monotonicMillis
        val nextCandidate = when {
            normalizedZ <= downThreshold -> DeviceOrientation.FACE_DOWN
            normalizedZ >= liftedThreshold -> DeviceOrientation.LIFTED
            else -> orientation
        }
        if (nextCandidate == orientation) {
            candidate = orientation
            candidateSince = null
            return orientation
        }
        if (nextCandidate != candidate || candidateSince == null) {
            candidate = nextCandidate
            candidateSince = monotonicMillis
        }
        val stability = if (nextCandidate == DeviceOrientation.FACE_DOWN) downStabilityMillis else liftedStabilityMillis
        if (monotonicMillis - (candidateSince ?: monotonicMillis) >= stability) {
            orientation = nextCandidate
            candidateSince = null
        }
        return orientation
    }

    fun invalidate(): DeviceOrientation {
        orientation = DeviceOrientation.UNKNOWN
        candidate = DeviceOrientation.UNKNOWN
        candidateSince = null
        lastSample = null
        return orientation
    }
}
