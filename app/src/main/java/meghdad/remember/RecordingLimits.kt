package meghdad.remember

import android.content.Context

enum class LimitAction { STOP, NEXT_CLIP }

data class RecordingLimit(val enabled: Boolean = false, val minutes: Int = 15, val action: LimitAction = LimitAction.STOP) {
    init { require(minutes in 1..1440) }
    val durationMs: Long get() = minutes * 60_000L
    fun actionAt(elapsed: Long): LimitAction? = if (enabled && elapsed >= durationMs) action else null
}

class RecordingPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("recording_settings", Context.MODE_PRIVATE)
    fun read() = RecordingLimit(
        preferences.getBoolean("limit_enabled", false),
        preferences.getInt("limit_minutes", 15).coerceIn(1, 1440),
        runCatching { LimitAction.valueOf(preferences.getString("limit_action", LimitAction.STOP.name)!!) }.getOrDefault(LimitAction.STOP)
    )
    fun write(limit: RecordingLimit) {
        preferences.edit().putBoolean("limit_enabled", limit.enabled).putInt("limit_minutes", limit.minutes)
            .putString("limit_action", limit.action.name).apply()
    }
}
