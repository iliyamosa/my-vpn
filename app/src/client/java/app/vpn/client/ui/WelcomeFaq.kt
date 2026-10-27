package app.vpn.client.ui

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vpn.R
import app.vpn.client.MainActivity
import app.vpn.client.data.AccountManager
import app.vpn.common.faDigits
import app.vpn.ui.AppCard
import app.vpn.ui.AppColors
import app.vpn.ui.AppIcon
import app.vpn.ui.FairosLogo
import app.vpn.ui.GradientButton
import app.vpn.ui.NeonWordmark
import app.vpn.ui.SpaceBackground
import kotlinx.coroutines.launch

/** A one-time welcome shown to a brand-new user: the free-pro gift + an optional invite code. */
@Composable
fun WelcomeOverlay(activity: MainActivity, welcomeDays: Int, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val account by AccountManager.account.collectAsState()
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var msgOk by remember { mutableStateOf(false) }
    val alreadyInvited = (account?.invitedBy ?: "").isNotBlank()

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        SpaceBackground(connected = false)
        Column(
            modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            FairosLogo(size = 120.dp)
            Spacer(Modifier.height(14.dp))
            NeonWordmark(fontSize = 26.sp)
            Spacer(Modifier.height(12.dp))
            Text("به جمع ما خوش آمدید 🎉", style = MaterialTheme.typography.titleMedium, color = AppColors.TextPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                "اینترنت آزاد، امن و پرسرعت؛ بدون ذخیرهٔ IP و فعالیت شما.",
                style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))

            if (welcomeDays > 0) {
                AppCard(color = androidx.compose.ui.graphics.Color(0xFF2A2210), glow = AppColors.Gold) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(R.drawable.ic_crown, tint = AppColors.Gold, size = 30.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("هدیهٔ خوش‌آمدگویی", style = MaterialTheme.typography.titleSmall, color = AppColors.Gold)
                            Text(
                                "$welcomeDays روز اشتراک پرو رایگان برایتان فعال شد!".faDigits(),
                                style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (!alreadyInvited) {
                AppCard {
                    Text("کد دعوت دارید؟", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "اگر دوستی شما را دعوت کرده، کدش را وارد کنید تا هم شما و هم او امتیاز بگیرید. (هر دستگاه فقط یک‌بار می‌تواند دعوت شود)",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = code, onValueChange = { code = it.trim() },
                        label = { Text("کد دعوت") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            busy = true; msg = null
                            scope.launch {
                                val r = AccountManager.redeemInvite(activity, code)
                                busy = false
                                r.onSuccess { msgOk = true; msg = "کد دعوت ثبت شد و امتیاز گرفتید!" }
                                r.onFailure { msgOk = false; msg = it.message ?: "ثبت کد انجام نشد." }
                            }
                        },
                        enabled = !busy && code.isNotBlank(),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text("ثبت کد دعوت") }
                    if (msg != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(msg!!, color = if (msgOk) AppColors.Connected else AppColors.Danger, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            GradientButton("شروع", onClick = onDone, enabled = !busy)
            Spacer(Modifier.height(24.dp))
        }
    }
}

private val FAQS: List<Pair<String, String>> = listOf(
    "FAIROS VPN چیست؟" to
        "یک برنامهٔ VPN امن و پرسرعت برای دسترسی آزاد و بدون محدودیت به اینترنت. ترافیک شما رمزنگاری می‌شود و IP و سایت‌هایی که باز می‌کنید ذخیره نمی‌شود.",
    "آیا رایگان است؟" to
        "بله، سرورهای رایگان در دسترس‌اند. برای سرعت بیشتر، سرورهای اختصاصی و بدون محدودیت زمان و تبلیغات، می‌توانید اشتراک پرو تهیه کنید.",
    "اشتراک پرو را چطور بخرم؟" to
        "در بخش «اشتراک پرو» روی خرید بزنید. پرداخت به‌صورت کارت‌به‌کارت یا ارز دیجیتال است؛ بعد از واریز، کد پیگیری را ثبت می‌کنید و پس از تأیید مدیر، اشتراک فعال می‌شود.",
    "بعد از پرداخت چقدر طول می‌کشد فعال شود؟" to
        "پرداخت‌ها دستی بررسی و تأیید می‌شوند؛ معمولاً در کوتاه‌ترین زمان ممکن. بعد از تأیید، با ورود دوباره، پرو بلافاصله فعال است.",
    "امتیاز و دعوت دوستان چطور کار می‌کند؟" to
        "با دعوت دوستان امتیاز می‌گیرید و با امتیاز می‌توانید اشتراک پرو بگیرید. هم دعوت‌کننده و هم دعوت‌شونده امتیاز می‌گیرند. هر گوشی فقط یک‌بار می‌تواند دعوت شود، ولی شما می‌توانید بی‌نهایت نفر را دعوت کنید.",
    "روی چند دستگاه می‌توانم وارد شوم؟" to
        "هر حساب هم‌زمان روی ۲ دستگاه فعال می‌ماند. برای ورود دستگاه سوم، یکی از دستگاه‌ها را از بخش پروفایل حذف کنید.",
    "چرا گاهی به سرور وصل نمی‌شوم؟" to
        "ممکن است آن سرور شلوغ یا موقتاً در دسترس نباشد. سرور دیگری را انتخاب کنید یا «تست سرعت» را بزنید تا بهترین سرور انتخاب شود.",
    "آیا اطلاعات من ذخیره می‌شود؟" to
        "خیر. این برنامه IP، سایت‌هایی که باز می‌کنید و ترافیک شما را ذخیره نمی‌کند. فقط آمار ناشناس برای بهبود سرویس جمع‌آوری می‌شود.",
    "ورود با گوگل امن است؟" to
        "بله. ورود با گوگل استاندارد و امن است؛ ما به رمز گوگل شما دسترسی نداریم و فقط ایمیلِ تأییدشده برای ساختن حساب استفاده می‌شود.",
    "چطور با پشتیبانی تماس بگیرم؟" to
        "از منوی پروفایل، گزینهٔ «ارتباط با پشتیبانی» را بزنید تا ایمیل پشتیبانی برایتان باز شود.",
)

@Composable
fun FaqScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader("سوالات متداول", onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FAQS.forEach { (q, a) -> FaqItem(q, a) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun FaqItem(question: String, answer: String) {
    var open by remember { mutableStateOf(false) }
    AppCard(modifier = Modifier.clickable { open = !open }) {
        Column(Modifier.animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(question, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                AppIcon(if (open) R.drawable.ic_up else R.drawable.ic_down, tint = AppColors.TextMuted, size = 18.dp)
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                Text(answer, style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSecondary)
            }
        }
    }
}
