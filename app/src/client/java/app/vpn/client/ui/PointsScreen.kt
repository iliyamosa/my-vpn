package app.vpn.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.AccountManager
import app.vpn.common.Mission
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.Pill
import kotlinx.coroutines.launch

@Composable
fun PointsScreen(activity: MainActivity, onBack: () -> Unit, onOpenPro: () -> Unit) {
    val scope = rememberCoroutineScope()
    val account by AccountManager.account.collectAsState()
    val me by AccountManager.me.collectAsState()
    var busyId by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { AccountManager.refreshMe(activity) }

    fun claim(m: Mission) {
        busyId = m.id
        msg = null
        scope.launch {
            val r = AccountManager.claimMission(activity, m.id, confirm = true)
            busyId = null
            r.onSuccess { awarded -> msg = "آفرین! $awarded امتیاز گرفتید.".faDigits() }
            r.onFailure { msg = it.message ?: "هنوز شرط این ماموریت کامل نشده است." }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("امتیازها و ماموریت‌ها", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Points balance
            AppCard(color = Color(0xFF17132E), glow = AppColors.Violet) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(R.drawable.ic_gift, tint = AppColors.Violet, size = 34.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("امتیاز شما", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                        Text("${account?.points ?: 0}".faDigits(), style = MaterialTheme.typography.headlineSmall, color = AppColors.TextPrimary)
                    }
                    OutlinedButton(onClick = onOpenPro, shape = RoundedCornerShape(12.dp)) {
                        Text("تبدیل به پرو", color = AppColors.Gold)
                    }
                }
            }

            val m = msg
            if (m != null) {
                Text(m, color = AppColors.Connected, style = MaterialTheme.typography.bodySmall)
            }

            Text("ماموریت‌ها", style = MaterialTheme.typography.titleSmall, color = AppColors.TextSecondary)

            val missions = me?.missions ?: emptyList()
            if (missions.isEmpty()) {
                Text("فعلاً ماموریتی وجود ندارد.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextMuted)
            }
            missions.forEach { mission ->
                MissionCard(
                    mission = mission,
                    busy = busyId == mission.id,
                    onOpenLink = { if (mission.link.isNotBlank()) activity.openLink(mission.link) },
                    onClaim = { claim(mission) },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun MissionCard(mission: Mission, busy: Boolean, onOpenLink: () -> Unit, onClaim: () -> Unit) {
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(mission.title, style = MaterialTheme.typography.titleSmall)
                if (mission.body.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(mission.body, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                }
            }
            Spacer(Modifier.width(10.dp))
            Pill("+${mission.points}".faDigits(), AppColors.Gold)
        }
        if (mission.goal > 1) {
            Spacer(Modifier.height(10.dp))
            val fraction = (mission.progress.toFloat() / mission.goal.toFloat()).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(50)),
                color = AppColors.Violet,
                trackColor = Color.White.copy(alpha = 0.08f),
            )
            Spacer(Modifier.height(4.dp))
            Text("${mission.progress} از ${mission.goal}".faDigits(), style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
        }
        Spacer(Modifier.height(10.dp))
        when {
            mission.claimed -> Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(R.drawable.ic_check, tint = AppColors.Connected, size = 18.dp)
                Spacer(Modifier.width(6.dp))
                Text("دریافت شد", color = AppColors.Connected, style = MaterialTheme.typography.bodySmall)
            }
            mission.kind == "visit" && !mission.ready -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onOpenLink, shape = RoundedCornerShape(12.dp)) { Text("باز کردن") }
                OutlinedButton(onClick = onClaim, enabled = !busy, shape = RoundedCornerShape(12.dp)) { Text("انجام دادم") }
            }
            mission.ready || mission.kind == "visit" -> Button(
                onClick = onClaim,
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Violet, contentColor = Color.White),
                shape = RoundedCornerShape(12.dp),
            ) { Text(if (busy) "…" else "دریافت امتیاز") }
            else -> Text("هنوز کامل نشده", color = AppColors.TextMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
}
