package app.vpn.client.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.AccountManager
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.GradientButton
import app.vpn.ui.SectionTitle
import kotlinx.coroutines.launch

@Composable
fun InfoScreen(activity: MainActivity, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val account by AccountManager.account.collectAsState()
    val me by AccountManager.me.collectAsState()
    val avatar by AccountManager.avatar.collectAsState()

    var display by remember(account?.displayName) { mutableStateOf(account?.displayName ?: "") }
    var oldPass by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgError by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { AccountManager.refreshMe(activity) }

    fun say(text: String, error: Boolean) { msg = text; msgError = error }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("اطلاعات حساب", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Avatar
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .border(2.dp, AppColors.Violet, CircleShape)
                        .background(AppColors.SurfaceHigh)
                        .clickable {
                            activity.pickAvatar { dataUrl ->
                                if (dataUrl == null) return@pickAvatar
                                scope.launch {
                                    busy = true
                                    val r = AccountManager.uploadAvatar(activity, dataUrl)
                                    busy = false
                                    r.onSuccess { say("تصویر پروفایل به‌روز شد.", false) }
                                    r.onFailure { say(it.message ?: "خطا در ذخیره تصویر.", true) }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val a = avatar
                    if (a != null) {
                        Image(
                            bitmap = a.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(88.dp).clip(CircleShape),
                        )
                    } else {
                        AppIcon(R.drawable.ic_camera, tint = AppColors.TextSecondary, size = 34.dp)
                    }
                }
            }
            Text(
                "برای تغییر تصویر، روی آن ضربه بزنید. فقط همان عکسی که انتخاب می‌کنید فرستاده می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextMuted,
                modifier = Modifier.fillMaxWidth(),
            )

            // Identity
            AppCard {
                ReadRow("نام کاربری", account?.username ?: "—")
                Spacer(Modifier.height(8.dp))
                ReadRow("ایمیل", account?.email ?: "—")
                Spacer(Modifier.height(12.dp))
                Text("نام نمایشی", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = display,
                    onValueChange = { display = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                )
                Spacer(Modifier.height(10.dp))
                GradientButton("ذخیره نام نمایشی", onClick = {
                    if (display.isBlank()) {
                        say("نام نمایشی را وارد کنید.", true)
                    } else scope.launch {
                        busy = true
                        val r = AccountManager.setDisplayName(activity, display)
                        busy = false
                        r.onSuccess { say("نام نمایشی ذخیره شد.", false) }
                        r.onFailure { say(it.message ?: "خطا رخ داد.", true) }
                    }
                }, enabled = !busy)
            }

            // Password
            AppCard {
                Text("تغییر رمز عبور", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(10.dp))
                PassField("رمز فعلی", oldPass) { oldPass = it }
                Spacer(Modifier.height(10.dp))
                PassField("رمز جدید (حداقل ۶ حرف)", newPass) { newPass = it }
                Spacer(Modifier.height(10.dp))
                GradientButton("تغییر رمز", onClick = {
                    if (oldPass.isBlank() || newPass.length < 6) {
                        say("رمز فعلی و رمز جدید (حداقل ۶ حرف) را وارد کنید.", true)
                    } else scope.launch {
                        busy = true
                        val r = AccountManager.changePassword(activity, oldPass, newPass)
                        busy = false
                        r.onSuccess { say("رمز عبور تغییر کرد.", false); oldPass = ""; newPass = "" }
                        r.onFailure { say(it.message ?: "خطا رخ داد.", true) }
                    }
                }, enabled = !busy)
            }

            // Invite code
            AppCard {
                Text("کد دعوت شما", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "این کد را به دوستانتان بدهید؛ با ثبت‌نام آن‌ها امتیاز می‌گیرید.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account?.refCode ?: "—",
                        style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
                        color = AppColors.Primary,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        val code = account?.refCode.orEmpty()
                        if (code.isNotBlank()) {
                            try {
                                val cm = activity.getSystemService(ClipboardManager::class.java)
                                cm?.setPrimaryClip(ClipData.newPlainText("ref", code))
                                activity.toast("کپی شد.")
                            } catch (e: Exception) { }
                        }
                    }) { Text("کپی") }
                }
            }

            // Devices
            val devices = me?.devices ?: emptyList()
            SectionTitle("دستگاه‌های فعال (${devices.size} از ۲)".faDigits())
            AppCard {
                if (devices.isEmpty()) {
                    Text("دستگاهی ثبت نشده است.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextMuted)
                } else {
                    devices.forEachIndexed { i, d ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                            AppIcon(R.drawable.ic_servers, tint = AppColors.TextSecondary, size = 20.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(d.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                scope.launch {
                                    val r = AccountManager.removeDevice(activity, d.id)
                                    r.onSuccess { say("دستگاه حذف شد.", false) }
                                    r.onFailure { say(it.message ?: "خطا رخ داد.", true) }
                                }
                            }) { Text("حذف", color = AppColors.Danger) }
                        }
                        if (i < devices.size - 1) Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Border.copy(alpha = 0.4f)))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "هر حساب فقط روی ۲ دستگاه کار می‌کند. برای ورود دستگاه جدید، یکی را حذف کنید.".faDigits(),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextMuted,
                    )
                }
            }

            val m = msg
            if (m != null) {
                Text(m, color = if (msgError) AppColors.Danger else AppColors.Connected, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ReadRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary, modifier = Modifier.width(90.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PassField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
    )
}
