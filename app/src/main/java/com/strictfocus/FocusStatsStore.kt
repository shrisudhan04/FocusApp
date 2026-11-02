package com.strictfocus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/** Lightweight local session history. No account or network connection is required. */
object FocusStatsStore {
    private const val PREFS = "strictfocus_focus_stats"
    private const val KEY_SESSIONS = "sessions_json"
    private const val KEY_GOAL = "daily_goal_minutes"
    private const val MAX_RECORDS = 500

    data class Snapshot(
        val todayMinutes: Int,
        val weeklyMinutes: List<Int>,
        val streakDays: Int,
        val sessionCount: Int,
        val totalMinutes: Int,
        val todaySessionCount: Int
    )

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun dailyGoalMinutes(context: Context): Int = prefs(context).getInt(KEY_GOAL, 180).coerceIn(15, 1440)

    fun setDailyGoalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_GOAL, minutes.coerceIn(15, 1440)).apply()
    }

    fun recordPlannedSession(context: Context, startedAt: Long, endsAt: Long, appCount: Int, mode: String) {
        val p = prefs(context)
        val list = runCatching { JSONArray(p.getString(KEY_SESSIONS, "[]")) }.getOrElse { JSONArray() }
        val row = JSONObject().apply {
            put("start", startedAt)
            put("end", endsAt)
            put("apps", appCount.coerceAtLeast(0))
            put("mode", mode)
        }
        val next = JSONArray()
        val startIndex = (list.length() - (MAX_RECORDS - 1)).coerceAtLeast(0)
        for (i in startIndex until list.length()) next.put(list.getJSONObject(i))
        next.put(row)
        p.edit().putString(KEY_SESSIONS, next.toString()).apply()
    }

    fun snapshot(context: Context, now: Long = System.currentTimeMillis()): Snapshot {
        val rows = runCatching { JSONArray(prefs(context).getString(KEY_SESSIONS, "[]")) }.getOrElse { JSONArray() }
        val today = dayStart(now)
        val weekly = MutableList(7) { 0 }
        val daysWithFocus = mutableSetOf<Long>()
        var todayMillis = 0L
        var totalMillis = 0L
        var sessionCount = 0
        var todaySessionCount = 0
        for (i in 0 until rows.length()) {
            val row = runCatching { rows.getJSONObject(i) }.getOrNull() ?: continue
            val start = row.optLong("start", 0L)
            val end = row.optLong("end", start)
            if (start <= 0L || end <= start) continue
            val effectiveEnd = minOf(end, now)
            if (effectiveEnd <= start) continue
            sessionCount++
            totalMillis += effectiveEnd - start
            val startDay = dayStart(start)
            daysWithFocus.add(startDay)
            if (startDay == today) todaySessionCount++
            for (offset in 0..6) {
                val day = today - offset * DAY_MS
                val dayEnd = day + DAY_MS
                val overlap = (minOf(effectiveEnd, dayEnd) - maxOf(start, day)).coerceAtLeast(0L)
                if (overlap > 0L) weekly[6 - offset] += (overlap / 60_000L).toInt()
                if (day == today) todayMillis += overlap
            }
        }
        var streak = 0
        var day = today
        // A session today is counted; if none yet, streak begins with yesterday.
        if (!daysWithFocus.contains(day)) day -= DAY_MS
        while (daysWithFocus.contains(day) && streak < 366) {
            streak++
            day -= DAY_MS
        }
        return Snapshot((todayMillis / 60_000L).toInt(), weekly, streak, sessionCount, (totalMillis / 60_000L).toInt(), todaySessionCount)
    }

    private fun dayStart(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private const val DAY_MS = 24L * 60L * 60L * 1000L
}

@Composable
fun FocusInsightsCard(
    isDark: Boolean,
    todayMinutes: Int,
    weeklyMinutes: List<Int>,
    streakDays: Int,
    sessionCount: Int,
    totalMinutes: Int,
    goalMinutes: Int,
    onGoalChange: (Int) -> Unit
) {
    val card = if (isDark) Color(0xFF27272A) else Color.White
    val foreground = if (isDark) Color(0xFFFAFAFA) else Color(0xFF1B1B27)
    val muted = if (isDark) Color(0xFFA1A1AA) else Color(0xFF666678)
    val accent = if (isDark) Color(0xFF8B5CF6) else Color(0xFF7C3AED)
    val progress = (todayMinutes.toFloat() / goalMinutes.coerceAtLeast(1)).coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = card)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("FOCUS INSIGHTS", color = accent, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    Text("Today’s progress", color = foreground, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(56.dp)) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(Color(0xFF9CA3AF).copy(alpha = .25f), -90f, 360f, false, style = Stroke(5.dp.toPx()))
                        drawArc(accent, -90f, 360f * progress, false, style = Stroke(5.dp.toPx()))
                    }
                    Text("${(progress * 100).toInt()}%", color = foreground, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${todayMinutes / 60}h ${todayMinutes % 60}m", color = foreground, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("of ${goalMinutes / 60}h ${goalMinutes % 60}m goal", color = muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 5.dp))
            }
            Text("Last 7 days", color = muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth().height(76.dp), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.Bottom) {
                val max = weeklyMinutes.maxOrNull()?.coerceAtLeast(1) ?: 1
                weeklyMinutes.forEachIndexed { index, minutes ->
                    Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                        Box(
                            Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                Modifier.fillMaxWidth(.72f).height((minutes.toFloat() / max * 52f).coerceAtLeast(if (minutes > 0) 4f else 2f).dp)
                                    .background(if (index == 6) accent else accent.copy(alpha = .48f), RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                            )
                        }
                        Text(listOf("M", "T", "W", "T", "F", "S", "S")[index], color = muted, fontSize = 10.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Daily goal", color = muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Text("${goalMinutes / 60}h ${goalMinutes % 60}m", color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            androidx.compose.material3.Slider(
                value = goalMinutes.toFloat(),
                onValueChange = { onGoalChange(it.toInt().coerceIn(15, 720)) },
                valueRange = 15f..720f,
                steps = 46,
                colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏅  $sessionCount sessions · ${totalMinutes / 60}h total", color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            val badges = buildList {
                if (sessionCount >= 1) add("First Focus")
                if (sessionCount >= 10) add("10 Sessions")
                if (streakDays >= 7) add("7-Day Streak")
                if (totalMinutes >= 6000) add("100 Focus Hours")
            }
            if (badges.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { badge: String ->
                        Text(badge, modifier = Modifier.background(accent.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp), color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔥  $streakDays day streak", color = foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text("${todayMinutes} min focused today", color = muted, fontSize = 11.sp)
            }
        }
    }
}

/** Local daily challenges and persisted rewards; entirely offline. */
object DailyChallengeStore {
    private const val PREFS = "strictfocus_daily_challenges"
    private const val KEY_BADGES = "earned_badges"

    data class Challenge(val id: String, val title: String, val target: Int, val unit: String, val reward: String)

    fun challengeForToday(): Challenge {
        val day = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        return when (day % 3) {
            0 -> Challenge("focus30", "Focus for 30 minutes today", 30, "minutes", "Steady Focus")
            1 -> Challenge("sessions2", "Complete 2 focus sessions", 2, "sessions", "Session Champion")
            else -> Challenge("focus60", "Reach 60 focused minutes", 60, "minutes", "Deep Work Star")
        }
    }

    fun earnedBadges(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_BADGES, emptySet())?.toSet() ?: emptySet()

    fun awardBadge(context: Context, badge: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet(KEY_BADGES, emptySet())?.toSet() ?: emptySet()) + badge
        prefs.edit().putStringSet(KEY_BADGES, updated).apply()
    }
}

@Composable
fun DailyChallengesCard(
    isDark: Boolean,
    todayMinutes: Int,
    sessionCount: Int,
    streakDays: Int
) {
    val context = LocalContext.current
    val challenge = remember { DailyChallengeStore.challengeForToday() }
    val current = if (challenge.unit == "minutes") todayMinutes else sessionCount
    val progress = (current.toFloat() / challenge.target.coerceAtLeast(1)).coerceIn(0f, 1f)
    val card = if (isDark) Color(0xFF27272A) else Color.White
    val foreground = if (isDark) Color(0xFFFAFAFA) else Color(0xFF1B1B27)
    val muted = if (isDark) Color(0xFFA1A1AA) else Color(0xFF666678)
    val accent = if (isDark) Color(0xFF8B5CF6) else Color(0xFF7C3AED)
    var earned by remember { mutableStateOf(DailyChallengeStore.earnedBadges(context)) }
    val complete = current >= challenge.target

    LaunchedEffect(challenge.id, complete) {
        if (complete && !earned.contains(challenge.reward)) {
            DailyChallengeStore.awardBadge(context, challenge.reward)
            earned = DailyChallengeStore.earnedBadges(context)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = card)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DAILY CHALLENGE", color = accent, fontSize = 11.sp, letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold)
                    Text(challenge.title, color = foreground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(if (complete) "🏆" else "🎯", fontSize = 28.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (challenge.unit == "minutes") "$current / ${challenge.target} min" else "$current / ${challenge.target} sessions", color = foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(if (complete) "Completed!" else "${(progress * 100).toInt()}%", color = if (complete) Color(0xFF16A34A) else accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (complete) Color(0xFF16A34A) else accent,
                trackColor = muted.copy(alpha = .18f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Reward: ${challenge.reward}", color = muted, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                if (challenge.reward in earned) Text("BADGE EARNED ✓", color = Color(0xFF16A34A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            if (earned.isNotEmpty()) {
                Text("YOUR BADGES", color = muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    earned.sorted().forEach { badge: String ->
                        Text("🏅 $badge", modifier = Modifier.background(accent.copy(alpha = .12f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 6.dp), color = accent, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Text("🔥 Current streak: $streakDays ${if (streakDays == 1) "day" else "days"}", color = foreground, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}
