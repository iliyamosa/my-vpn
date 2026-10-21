package app.vpn.admin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vpn.common.Jalali
import app.vpn.common.RemoteConfig
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.Pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackendMissing() {
    AppCard {
        Text("سرور مرکزی تنظیم نشده است", style = MaterialTheme.typography.titleSmall, color = AppColors.Connecting)
        Text(
            "آمار و لیست کاربران از سرور مرکزی (Cloudflare) می‌آید. آدرس آن را در تب «سایر» وارد کنید.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
    }
}

// ---------------------------------------------------------------- stats

@Composable
fun StatsTab(backend: BackendClient, cfg: RemoteConfig) {
    if (!backend.available) {
        BackendMissing()
        return
    }
    val scope = rememberCoroutineScope()
    var days by remember { mutableIntStateOf(14) }
    var summary by remember { mutableStateOf<Summary?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    suspend fun load() {
        loading = true
        try {
            summary = withContext(Dispatchers.IO) { backend.summary(days) }
            error = null
        } catch (e: Exception) {
            error = (e.message ?: "خطای شبکه") + "\nاگر Cloudflare روی اینترنت شما فیلتر است، فیلترشکن را روشن کنید."
        }
        loading = false
    }
    LaunchedEffect(days) {
        while (true) {
            load()
            delay(30_000)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(7, 14, 30).forEach { d ->
            Chip("${d} روز".faDigits(), selected = d == days) { days = d }
        }
        Spacer(Modifier.weight(1f))
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppColors.Primary)
        } else {
            Text(
                "به‌روزرسانی",
                color = AppColors.Primary,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { scope.launch { load() } }.padding(6.dp),
            )
        }
    }

    error?.let { Text(it, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall) }
    val s = summary ?: return

    // Key numbers
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox("آنلاین الان", s.online.toString(), AppColors.Connected, Modifier.weight(1f))
        StatBox("متصل امروز", s.todayConnected.toString(), AppColors.Primary, Modifier.weight(1f))
        StatBox("فعال امروز", s.todayActive.toString(), AppColors.TextPrimary, Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatBox("کل کاربران", s.totalDevices.toString(), AppColors.TextPrimary, Modifier.weight(1f))
        StatBox("کاربران پرو", s.proDevices.toString(), AppColors.Gold, Modifier.weight(1f))
        StatBox(
            "رضایت کلی",
            s.satisfaction?.let { "$it٪" } ?: "—",
            satisfactionColor(s.satisfaction),
            Modifier.weight(1f),
        )
    }

    // Daily chart
    AppCard {
        Text("کاربران در هر روز", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend("فعال (برنامه را باز کرده)", AppColors.Primary.copy(alpha = 0.45f))
            Legend("متصل شده", AppColors.Connected)
        }
        Spacer(Modifier.height(10.dp))
        DailyChart(s.daily)
        Spacer(Modifier.height(10.dp))
        val week = s.daily.takeLast(7)
        Text(
            "میانگین ۷ روز اخیر: ${week.map { it.connected }.average().toInt()} کاربر متصل در روز  •  ${week.sumOf { it.connects }} اتصال".faDigits(),
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        val timeouts = s.daily.sumOf { it.freeTimeouts }
        if (timeouts > 0) {
            Text(
                "قطع خودکار اتصال رایگان (پایان زمان) در این بازه: $timeouts بار".faDigits(),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
            )
        }
    }

    // Ads
    AppCard {
        Text("بازدید تبلیغات", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        if (s.ads.isEmpty()) {
            Text("هنوز بازدیدی ثبت نشده است.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
        }
        s.ads.forEach { a ->
            val ad = cfg.ads.items.firstOrNull { it.id == a.id }
            val title = ad?.title?.ifBlank { null } ?: ad?.text?.take(30)?.ifBlank { null } ?: "تبلیغ حذف‌شده"
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AppColors.SurfaceHigh.copy(alpha = 0.6f))
                    .padding(10.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SmallStat("امروز", a.viewsToday)
                    SmallStat("کل بازه", a.views)
                    SmallStat("کلیک", a.clicks)
                    val ctr = if (a.views > 0) (100.0 * a.clicks / a.views) else 0.0
                    Text(String.format(java.util.Locale.US, "نرخ کلیک %.1f٪", ctr).faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }
        }
    }

    // Servers
    AppCard {
        Text("کیفیت و رضایت سرورها", style = MaterialTheme.typography.titleSmall)
        Text(
            "«موفقیت» = درصد اتصال‌هایی که سرور واقعاً جواب داد. «رضایت» = درصد 👍 از نظر کاربران.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        if (s.servers.isEmpty()) {
            Text("هنوز اتصالی ثبت نشده است.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
        }
        s.servers.forEach { sv ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sv.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    sv.satisfaction?.let { Pill("رضایت $it٪".faDigits(), satisfactionColor(it)) }
                }
                Spacer(Modifier.height(4.dp))
                val rate = sv.successRate ?: 0
                LinearProgressIndicator(
                    progress = { rate / 100f },
                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                    color = satisfactionColor(sv.successRate),
                    trackColor = Color.White.copy(alpha = 0.08f),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    ("موفقیت ${sv.successRate?.let { "$it٪" } ?: "—"}  •  ${sv.ok + sv.fail} اتصال  •  👍 ${sv.up}  👎 ${sv.down}" +
                        if (sv.minutes > 0) "  •  ${sv.minutes / 60} ساعت استفاده" else "").faDigits(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    AppCard(modifier = modifier, glow = color) {
        Text(value.faDigits(), style = MaterialTheme.typography.headlineSmall, color = color, fontWeight = FontWeight.Black)
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, maxLines = 1)
    }
}

@Composable
private fun SmallStat(label: String, n: Int) {
    Text("$label: $n".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
}

@Composable
private fun Legend(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
    }
}

fun satisfactionColor(v: Int?): Color = when {
    v == null -> AppColors.TextMuted
    v >= 80 -> AppColors.Connected
    v >= 55 -> AppColors.Connecting
    else -> AppColors.Danger
}

@Composable
private fun DailyChart(days: List<DayStat>) {
    if (days.isEmpty()) return
    val max = (days.maxOf { maxOf(it.active, it.connected) }).coerceAtLeast(1)
    // Charts read left to right in time; the screen is RTL, so draw explicitly.
    Column {
        Text("${max}".faDigits(), style = MaterialTheme.typography.labelSmall, color = AppColors.TextMuted)
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val n = days.size
            val slot = size.width / n
            val barW = (slot * 0.36f).coerceAtLeast(2f)
            for (i in 0 until n) {
                // newest day on the right side for an RTL reader is confusing; keep oldest on the right like Persian charts
                val d = days[n - 1 - i]
                val x = i * slot + slot / 2f
                val hA = size.height * d.active / max
                val hC = size.height * d.connected / max
                drawRoundRect(
                    AppColors.Primary.copy(alpha = 0.45f),
                    topLeft = Offset(x - barW, size.height - hA),
                    size = Size(barW, hA),
                    cornerRadius = CornerRadius(4f, 4f),
                )
                drawRoundRect(
                    AppColors.Connected,
                    topLeft = Offset(x, size.height - hC),
                    size = Size(barW, hC),
                    cornerRadius = CornerRadius(4f, 4f),
                )
            }
            drawLine(Color.White.copy(alpha = 0.15f), Offset(0f, size.height), Offset(size.width, size.height), 1f)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth()) {
            val step = if (days.size > 14) 7 else if (days.size > 7) 3 else 1
            // labels in the same order as the bars (newest first from the right in RTL layout)
            days.forEachIndexed { idx, d ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if ((days.size - 1 - idx) % step == 0) {
                        Text(Jalali.shortLabel(d.day), fontSize = 9.sp, color = AppColors.TextMuted, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- users

@Composable
fun UsersTab(backend: BackendClient) {
    if (!backend.available) {
        BackendMissing()
        return
    }
    val scope = rememberCoroutineScope()
    var tier by remember { mutableStateOf("all") }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<UserRow>>(emptyList()) }
    var total by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun load(reset: Boolean) {
        loading = true
        try {
            val page = withContext(Dispatchers.IO) { backend.users(tier, if (reset) 0 else users.size, query.trim()) }
            users = if (reset) page.users else users + page.users
            total = page.total
            error = null
        } catch (e: Exception) {
            error = e.message ?: "خطای شبکه"
        }
        loading = false
    }
    LaunchedEffect(tier, query) {
        delay(350)
        load(reset = true)
    }

    AppCard {
        Text(
            "هر نصب برنامه یک شناسه‌ی ناشناس دارد. IP، شماره و سایت‌های بازشده ذخیره نمی‌شود.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("همه", tier == "all") { tier = "all" }
            Chip("پرو", tier == "pro") { tier = "pro" }
            Chip("رایگان", tier == "free") { tier = "free" }
        }
        Spacer(Modifier.height(8.dp))
        Field("جستجو (مدل گوشی یا شناسه)", query, { query = it })
        Spacer(Modifier.height(6.dp))
        Text("$total کاربر".faDigits(), style = MaterialTheme.typography.titleSmall, color = AppColors.Primary)
    }
    error?.let { Text(it, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall) }

    users.forEach { u ->
        val online = System.currentTimeMillis() - u.lastSeen < 5 * 60_000
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(AppColors.Surface.copy(alpha = 0.85f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(10.dp).clip(CircleShape)
                    .background(if (online) AppColors.Connected else AppColors.TextMuted.copy(alpha = 0.5f)),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        u.model.ifBlank { "گوشی ناشناخته" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(6.dp))
                    if (u.pro) Pill("پرو", AppColors.Gold)
                }
                Text(
                    "اندروید ${u.android}  •  نسخه‌ی ${u.appVersion}  •  ${u.connects} اتصال".faDigits(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
                Text(
                    "آخرین فعالیت: ${Jalali.ago(u.lastSeen)}  •  عضو از ${Jalali.fullDate(u.firstSeen)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMuted,
                )
                Text("#" + u.id.take(8), style = MaterialTheme.typography.labelSmall, color = AppColors.TextMuted)
            }
        }
    }
    if (users.size < total) {
        OutlinedButton(
            onClick = { scope.launch { load(reset = false) } },
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        ) { Text(if (loading) "در حال بارگذاری…" else "نمایش بیشتر") }
    } else if (loading) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = AppColors.Primary)
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) AppColors.Primary else AppColors.SurfaceHigh)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) Color.White else AppColors.TextSecondary, style = MaterialTheme.typography.labelLarge)
    }
}
