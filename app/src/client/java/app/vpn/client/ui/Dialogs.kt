package app.vpn.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vpn.client.MainActivity
import app.vpn.client.data.Analytics
import app.vpn.client.data.ClientStore
import app.vpn.client.data.ServerRepository
import app.vpn.client.vpn.EndReason
import app.vpn.client.vpn.VpnState
import app.vpn.common.faDigits
import app.vpn.ui.AppColors

/** "Free time is over" and "how was the server?" dialogs, shown after a connection ends. */
@Composable
fun SessionEndDialogs(activity: MainActivity, onReconnect: () -> Unit, onOpenPro: () -> Unit) {
    val end by VpnState.lastSessionEnd.collectAsState()
    val config by ServerRepository.config.collectAsState()
    val store = remember { ClientStore(activity) }
    val e = end ?: return
    if (System.currentTimeMillis() - e.at > 30 * 60_000L) {
        LaunchedEffect(e) { VpnState.lastSessionEnd.value = null }
        return
    }
    fun done() {
        VpnState.lastSessionEnd.value = null
    }

    when {
        e.reason == EndReason.FREE_LIMIT -> {
            AlertDialog(
                onDismissRequest = { done() },
                title = { Text("زمان اتصال رایگان تمام شد", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Text(
                        "هر اتصال رایگان حداکثر ${config.free.sessionMinutes} دقیقه است. برای ادامه دوباره وصل شوید" +
                            if (config.pro.enabled) "، یا با اشتراک پرو بدون محدودیت وصل بمانید." else ".",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        done()
                        onReconnect()
                    }) { Text("اتصال دوباره") }
                },
                dismissButton = if (config.pro.enabled) {
                    {
                        TextButton(onClick = {
                            done()
                            onOpenPro()
                        }) { Text("اشتراک پرو", color = AppColors.Gold) }
                    }
                } else null,
                containerColor = AppColors.SurfaceHigh,
            )
        }

        e.reason == EndReason.USER && e.minutes >= 2 &&
            System.currentTimeMillis() - store.lastRatingAskAt > 6 * 3600_000L -> {
            fun rate(up: Boolean) {
                Analytics.track(activity, "rate", server = e.server, extra = mapOf("up" to if (up) 1 else 0))
                store.lastRatingAskAt = System.currentTimeMillis()
                activity.toast("ممنون از نظرتان!")
                done()
            }
            AlertDialog(
                onDismissRequest = {
                    store.lastRatingAskAt = System.currentTimeMillis()
                    done()
                },
                title = { Text("کیفیت اتصال چطور بود؟", style = MaterialTheme.typography.titleMedium) },
                text = {
                    Column {
                        Text(
                            "${e.server}\n${e.minutes} دقیقه متصل بودید".faDigits(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.TextSecondary,
                            textAlign = TextAlign.Start,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { rate(true) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.Connected, contentColor = Color.White),
                            ) { Text("👍  خوب بود", fontSize = 15.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                            OutlinedButton(
                                onClick = { rate(false) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                            ) { Text("👎  بد بود", fontSize = 15.sp, color = AppColors.Danger, modifier = Modifier.padding(vertical = 4.dp)) }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        store.lastRatingAskAt = System.currentTimeMillis()
                        done()
                    }) { Text("بعداً", color = AppColors.TextMuted) }
                },
                containerColor = AppColors.SurfaceHigh,
            )
        }

        else -> LaunchedEffect(e) { VpnState.lastSessionEnd.value = null }
    }
}
