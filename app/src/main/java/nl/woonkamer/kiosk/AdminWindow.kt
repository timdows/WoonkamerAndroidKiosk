package nl.woonkamer.kiosk

/** A confirmation remains valid for less than 30 seconds, using monotonic time. */
class AdminWindow(val authenticatedAt: Long) {
    fun isValidAt(nowMillis: Long): Boolean =
        nowMillis >= authenticatedAt && nowMillis - authenticatedAt < DURATION_MILLIS

    companion object { const val DURATION_MILLIS = 30_000L }
}
