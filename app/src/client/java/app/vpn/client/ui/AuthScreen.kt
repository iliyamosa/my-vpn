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
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import app.vpn.client.MainActivity
import app.vpn.client.data.AccountManager
import app.vpn.common.AccountException
import app.vpn.ui.AppColors
import app.vpn.ui.FairosLogo
import app.vpn.ui.GradientButton
import app.vpn.ui.SpaceBackground
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(activity: MainActivity) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf(0) } // 0 = login, 1 = register
    var login by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var display by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var googleClientId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { googleClientId = AccountManager.googleClientId() }

    fun googleSignIn() {
        error = null
        busy = true
        scope.launch {
            try {
                val clientId = googleClientId
                if (clientId.isBlank()) { busy = false; error = "ورود با گوگل فعال نیست."; return@launch }
                val option = GetGoogleIdOption.Builder()
                    .setServerClientId(clientId)
                    .setFilterByAuthorizedAccounts(false)
                    .setAutoSelectEnabled(false)
                    .build()
                val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
                val response = CredentialManager.create(activity).getCredential(activity, request)
                val cred = response.credential
                val idToken = if (cred is GoogleIdTokenCredential) {
                    cred.idToken
                } else if (cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    GoogleIdTokenCredential.createFrom(cred.data).idToken
                } else {
                    null
                }
                if (idToken.isNullOrBlank()) { busy = false; error = "توکن گوگل دریافت نشد."; return@launch }
                val r = AccountManager.googleLogin(activity, idToken)
                busy = false
                r.onFailure { e -> error = (e as? AccountException)?.message ?: e.message ?: "ورود با گوگل ناموفق بود." }
            } catch (e: Exception) {
                busy = false
                // A user cancelling the Google sheet throws; show a soft message, not an error dump.
                error = "ورود با گوگل انجام نشد. دوباره تلاش کنید یا با ایمیل وارد شوید."
            }
        }
    }

    fun submit() {
        error = null
        busy = true
        scope.launch {
            val result = if (tab == 0) {
                if (login.isBlank() || password.isBlank()) { busy = false; error = "نام کاربری و رمز را وارد کنید."; return@launch }
                val r = AccountManager.login(activity, login, password)
                // The admin signs in from the very same form: if a normal login fails,
                // try the admin credentials before showing an error.
                if (r.isFailure) {
                    val adminR = AccountManager.adminLogin(activity, login, password)
                    if (adminR.isSuccess) adminR else r
                } else r
            } else {
                if (email.isBlank() || username.isBlank() || password.isBlank()) { busy = false; error = "همه‌ی فیلدها را کامل کنید."; return@launch }
                AccountManager.register(activity, email, username, password, display.ifBlank { username }, "")
            }
            busy = false
            result.onFailure { e ->
                error = (e as? AccountException)?.message ?: e.message ?: "خطا رخ داد."
            }
        }
    }

    Box(Modifier.fillMaxSize().background(AppColors.Background)) {
        SpaceBackground(connected = false)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            FairosLogo(size = 160.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                "برای استفاده از برنامه، وارد شوید یا ثبت‌نام کنید",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(26.dp))

            // segmented control
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AppColors.Surface.copy(alpha = 0.7f))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf("ورود", "ثبت‌نام").forEachIndexed { i, label ->
                    val sel = i == tab
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(11.dp))
                            .background(if (sel) AppColors.Violet else Color.Transparent)
                            .clickable { tab = i; error = null }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = if (sel) Color.White else AppColors.TextSecondary, style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            if (tab == 0) {
                AuthField("نام کاربری یا ایمیل", login, { login = it.trim() }, ltr = true)
                Spacer(Modifier.height(10.dp))
                AuthField("رمز عبور", password, { password = it }, password = true, ltr = true)
            } else {
                AuthField("ایمیل", email, { email = it.trim() }, ltr = true, keyboard = KeyboardType.Email)
                Spacer(Modifier.height(10.dp))
                AuthField("نام کاربری (انگلیسی)", username, { username = it.trim() }, ltr = true)
                Spacer(Modifier.height(10.dp))
                AuthField("نام نمایشی", display, { display = it })
                Spacer(Modifier.height(10.dp))
                AuthField("رمز عبور (حداقل ۶ حرف)", password, { password = it }, password = true, ltr = true)
            }

            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error!!, color = AppColors.Danger, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(20.dp))
            GradientButton(if (tab == 0) "ورود" else "ثبت‌نام و شروع", onClick = { submit() }, enabled = !busy, loading = busy)

            if (googleClientId.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text("یا", color = AppColors.TextMuted, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                GoogleButton(enabled = !busy, onClick = { googleSignIn() })
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "ما IP، سایت‌هایی که باز می‌کنید و ترافیک شما را ذخیره نمی‌کنیم.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextMuted,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GoogleButton(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.95f))
            .border(1.dp, Color.White.copy(alpha = 0.6f), shape)
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("G", color = Color(0xFF4285F4), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(10.dp))
        Text("ورود با گوگل", color = Color(0xFF1F1F1F), style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun AuthField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    ltr: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        textStyle = if (ltr) LocalTextStyle.current.copy(textDirection = TextDirection.Ltr) else LocalTextStyle.current,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
    )
}
