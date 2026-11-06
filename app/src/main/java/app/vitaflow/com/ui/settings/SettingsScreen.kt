package app.vitaflow.com.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.vitaflow.com.AppContainer
import app.vitaflow.com.ai.Diagnostics
import app.vitaflow.com.ai.ModelManager
import app.vitaflow.com.ai.WebResult
import app.vitaflow.com.data.ThemeMode
import app.vitaflow.com.data.auth.GoogleResult
import app.vitaflow.com.data.auth.requestGoogleIdentity
import app.vitaflow.com.data.db.AccountEntity
import app.vitaflow.com.ui.components.ConfirmDialog
import app.vitaflow.com.ui.components.GlassDialog
import app.vitaflow.com.ui.me.MemoryScreen
import app.vitaflow.com.ui.theme.GlassButton
import app.vitaflow.com.ui.theme.GlassChip
import app.vitaflow.com.ui.theme.GlassSurface
import app.vitaflow.com.ui.theme.GlassTextField
import app.vitaflow.com.ui.theme.LocalGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = g.accent, checkedThumbColor = g.onAccent))
    }
}

@Composable
private fun Section(title: String, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    val g = LocalGlass.current
    GlassSurface(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = g.textPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null, tint = g.textSecondary)
            }
            if (open) Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@Composable
fun SettingsScreen(account: AccountEntity, container: AppContainer, onBack: () -> Unit, onLogout: () -> Unit, onClearChat: () -> Unit) {
    val g = LocalGlass.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = container.settingsStore

    val themeMode by s.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
    val reduce by s.reduceEffects.collectAsStateWithLifecycle(initialValue = false)
    val style by s.replyStyle.collectAsStateWithLifecycle(initialValue = "SHORT")
    val fallback by s.localFallback.collectAsStateWithLifecycle(initialValue = true)
    val savedUrl by s.backendUrl.collectAsStateWithLifecycle(initialValue = "")
    val savedModel by s.directModel.collectAsStateWithLifecycle(initialValue = "gemini-2.5-flash")
    val hasCode by container.secrets.has("backend_code").collectAsStateWithLifecycle(initialValue = false)
    val hasGemini by container.secrets.has("gemini_key").collectAsStateWithLifecycle(initialValue = false)
    val webOn by s.webSearchEnabled.collectAsStateWithLifecycle(initialValue = true)
    val lastExport by s.lastExportAt.collectAsStateWithLifecycle(initialValue = 0L)
    val hasKey by container.secrets.hasTavilyKey.collectAsStateWithLifecycle(initialValue = false)
    val installed by container.models.installed.collectAsStateWithLifecycle()

    var open by rememberSaveable { mutableIntStateOf(-1) }
    var message by remember { mutableStateOf<String?>(null) }
    var llmProgress by remember { mutableStateOf<Int?>(null) }
    var urlText by remember(savedUrl) { mutableStateOf(savedUrl) }
    var modelText by remember(savedModel) { mutableStateOf(savedModel) }
    var codeText by remember { mutableStateOf("") }
    var geminiText by remember { mutableStateOf("") }
    var keyText by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var gpu by remember { mutableStateOf(container.llm.gpuEnabled()) }
    var keep by remember { mutableIntStateOf(container.llm.keepSeconds()) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<String?>(null) }
    var aiStatus by remember { mutableStateOf("") }
    fun toggle(i: Int) { open = if (open == i) -1 else i }

    LaunchedEffect(savedUrl, hasCode, hasGemini) {
        container.brain.invalidate()
        aiStatus = when (container.online.mode()) {
            "backend" -> "চালু: Max backend"
            "direct" -> "চালু: সরাসরি Gemini"
            else -> "সেট করা নেই"
        }
    }

    val llmPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            message = "Gemma কপি হচ্ছে, কিছুক্ষণ লাগবে..."
            llmProgress = 0
            container.models.importLlm(uri) { done, total -> if (total > 0) llmProgress = (done * 100 / total).toInt() }
                .onSuccess { message = "Gemma যোগ হয়েছে ✓" }
                .onFailure { message = it.message ?: "যোগ করা যায়নি" }
            llmProgress = null
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch {
            val bytes = container.backup.export(passphrase)
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            if (ok) s.markExported()
            message = if (ok) "ব্যাকআপ সংরক্ষিত ✓" else "ফাইল লেখা যায়নি"
        }
    }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            message = if (bytes == null) "ফাইল পড়া যায়নি" else container.backup.import(bytes, passphrase) ?: "সব ফিরে এসেছে ✓ আবার প্রবেশ করুন"
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "পিছনে", tint = g.textPrimary) }
            Text("সেটিংস", style = MaterialTheme.typography.titleLarge, color = g.textPrimary)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            message?.let { Text(it, color = g.accent, style = MaterialTheme.typography.bodyMedium) }
            llmProgress?.let { Text("কপি: $it%", color = g.accent) }

            Section("চেহারা", open == 0, { toggle(0) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("ডার্ক", themeMode == ThemeMode.DARK, { scope.launch { s.setThemeMode(ThemeMode.DARK) } })
                    GlassChip("লাইট", themeMode == ThemeMode.LIGHT, { scope.launch { s.setThemeMode(ThemeMode.LIGHT) } })
                    GlassChip("ফোনের মতো", themeMode == ThemeMode.SYSTEM, { scope.launch { s.setThemeMode(ThemeMode.SYSTEM) } })
                }
                SwitchRow("সহজ চেহারা (ঝাপসা কম)", reduce) { scope.launch { s.setReduceEffects(it) } }
            }

            Section("Max AI", open == 1, { toggle(1) }) {
                Text(aiStatus, color = if (aiStatus.startsWith("চালু")) g.success else g.danger, style = MaterialTheme.typography.bodyMedium)
                GlassTextField(urlText, { urlText = it }, "Max backend-এর address (https://...)")
                GlassTextField(codeText, { codeText = it }, if (hasCode) "access code (সংরক্ষিত, নতুন দিলে বদলাবে)" else "access code", password = true)
                Text("backend না থাকলে সরাসরি Gemini key দিন:", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                GlassTextField(geminiText, { geminiText = it }, if (hasGemini) "Gemini key (সংরক্ষিত)" else "Gemini key", password = true)
                GlassTextField(modelText, { modelText = it }, "Gemini model (সরাসরি ব্যবহারে)")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("সংরক্ষণ", {
                        scope.launch {
                            s.setBackendUrl(urlText)
                            s.setDirectModel(modelText)
                            if (codeText.isNotBlank()) container.secrets.save("backend_code", codeText)
                            if (geminiText.isNotBlank()) container.secrets.save("gemini_key", geminiText)
                            codeText = ""
                            geminiText = ""
                            container.brain.invalidate()
                            message = "সংরক্ষিত ✓"
                        }
                    }, Modifier.weight(1f))
                    GlassButton("পরীক্ষা", { scope.launch { message = "চেষ্টা করছি..."; message = container.brain.test() } }, Modifier.weight(1f))
                }
                Text("উত্তর", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("ছোট", style == "SHORT", { scope.launch { s.setReplyStyle("SHORT") } })
                    GlassChip("বিস্তারিত", style == "DETAILED", { scope.launch { s.setReplyStyle("DETAILED") } })
                }
                SwitchRow("ইন্টারনেট না থাকলে ফোনের AI", fallback) { scope.launch { s.setLocalFallback(it) } }
            }

            Section("ওয়েব সার্চ", open == 2, { toggle(2) }) {
                SwitchRow("Max ওয়েবে খুঁজবে", webOn) { scope.launch { s.setWebSearchEnabled(it) } }
                Text(
                    when {
                        !webOn -> "এখন বন্ধ"
                        !hasKey -> "key নেই, নিচে বসান"
                        else -> "চালু। বলুন: \"সার্চ করো আজকের ডলারের রেট\""
                    },
                    color = if (webOn && hasKey) g.success else g.textSecondary, style = MaterialTheme.typography.bodyMedium
                )
                GlassTextField(keyText, { keyText = it }, "Tavily key", password = true)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlassButton("সংরক্ষণ", {
                        scope.launch { container.secrets.saveTavilyKey(keyText); keyText = ""; message = "key সংরক্ষিত ✓" }
                    }, Modifier.weight(1f), enabled = keyText.isNotBlank())
                    GlassButton("পরীক্ষা", {
                        scope.launch {
                            message = "খুঁজছি..."
                            message = when (val r = container.web.search("Dhaka weather today")) {
                                is WebResult.Ok -> "ওয়েব সার্চ কাজ করছে (${r.hits.size}টি ফল)"
                                is WebResult.Err -> r.message
                            }
                        }
                    }, Modifier.weight(1f))
                }
                if (hasKey) GlassButton("key মুছুন", { scope.launch { container.secrets.saveTavilyKey(""); message = "key মুছে ফেলা হয়েছে" } }, Modifier.fillMaxWidth(), danger = true)
            }

            Section("স্মৃতি", open == 3, { toggle(3) }) { MemoryScreen(account, container) }

            Section("ডেটা", open == 4, { toggle(4) }) {
                val days = if (lastExport == 0L) null else (System.currentTimeMillis() - lastExport) / (24L * 60 * 60 * 1000)
                Text(
                    if (days == null) "এখনো ব্যাকআপ নেওয়া হয়নি" else "শেষ ব্যাকআপ: $days দিন আগে",
                    color = if (days == null || days > 7) g.danger else g.success, style = MaterialTheme.typography.bodyMedium
                )
                GlassTextField(passphrase, { passphrase = it }, "ফাইলের পাসওয়ার্ড (ইচ্ছা হলে)", password = true)
                GlassButton("Export", { exportPicker.launch("vitaflow-backup-${LocalDate.now()}.vfbak") }, Modifier.fillMaxWidth())
                GlassButton("Import", { importPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth())
                GlassButton("সব chat মুছুন", { confirmClear = true }, Modifier.fillMaxWidth(), danger = true)
            }

            Section("অ্যাকাউন্ট", open == 5, { toggle(5) }) {
                Text(account.email ?: account.phone ?: account.name, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                if (account.googleSub == null) {
                    GlassButton("Google যুক্ত করুন", {
                        scope.launch {
                            when (val r = requestGoogleIdentity(context)) {
                                is GoogleResult.Ok -> message = container.authRepo.linkGoogle(account.id, r.identity) ?: "Google যুক্ত হয়েছে ✓"
                                is GoogleResult.Err -> message = r.message
                            }
                        }
                    }, Modifier.fillMaxWidth())
                } else {
                    Text("Google যুক্ত আছে", color = g.success, style = MaterialTheme.typography.bodyMedium)
                }
                GlassButton("লগ আউট", onLogout, Modifier.fillMaxWidth(), danger = true)
            }

            Section("অফলাইন AI (পরীক্ষামূলক)", open == 6, { toggle(6) }) {
                val has = ModelManager.LLM in installed
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gemma", color = g.textPrimary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text(if (has) "আছে" else "নেই", color = if (has) g.success else g.textSecondary, style = MaterialTheme.typography.labelLarge)
                    if (has) {
                        Text(
                            "মুছুন", color = g.danger, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.clickable { confirmDelete = ModelManager.LLM }.padding(start = 14.dp, top = 6.dp, bottom = 6.dp)
                        )
                    }
                }
                GlassButton("Gemma যোগ করুন (.litertlm)", { llmPicker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = llmProgress == null)
                Text("মেমোরিতে থাকবে", color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip("৩০ সেকেন্ড", keep == 30, { keep = 30; container.llm.setKeepSeconds(30) })
                    GlassChip("২ মিনিট", keep == 120, { keep = 120; container.llm.setKeepSeconds(120) })
                    GlassChip("১০ মিনিট", keep == 600, { keep = 600; container.llm.setKeepSeconds(600) })
                }
                SwitchRow("GPU ব্যবহার", gpu) { on ->
                    gpu = on
                    scope.launch { container.llm.setGpu(on) }
                }
            }

            Section("সমস্যা নির্ণয়", open == 7, { toggle(7) }) {
                GlassButton("সমস্যার রিপোর্ট", { report = Diagnostics.report(context, installed) }, Modifier.fillMaxWidth())
            }
        }
    }

    confirmDelete?.let { key ->
        ConfirmDialog("এই model মুছে ফেলবেন?", onConfirm = { container.models.delete(key) }, onDismiss = { confirmDelete = null })
    }
    if (confirmClear) {
        ConfirmDialog("Max-এর সাথে সব chat মুছে ফেলবেন?", "মুছুন", onConfirm = onClearChat, onDismiss = { confirmClear = false })
    }
    report?.let { text ->
        GlassDialog("রিপোর্ট", { report = null }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                Text(text, color = g.textSecondary, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton("পাঠান", {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                    context.startActivity(Intent.createChooser(send, "রিপোর্ট পাঠান"))
                }, Modifier.weight(1f))
                GlassButton("বন্ধ", { report = null }, Modifier.weight(1f))
            }
        }
    }
}
