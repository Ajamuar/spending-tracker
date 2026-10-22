package com.ajamuar.ledger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import kotlinx.coroutines.*

@Composable fun AiSettingsDialog(repo: Repository, settings: List<Metadata>, dismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var batch by remember { mutableStateOf<AiBatch?>(null) }
    var suggestions by remember { mutableStateOf<AiSuggestions?>(null) }
    fun act(block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true; error = null; message = null
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = if (e is IllegalArgumentException || e is IllegalStateException) e.message ?: "AI action could not finish" else "AI action could not finish. Your ledger is unchanged." }
            finally { busy = false }
        }
    }
    LaunchedEffect(repo) {
        try { hasKey = withContext(Dispatchers.IO) { repo.vault.read("openai-key") != null } }
        catch (_: Exception) { error = "The saved API key could not be unlocked. Save it again." }
    }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("AI learning · OpenAI") }, text = {
            Column(Modifier.heightIn(max = 550.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Optional, separately billed OpenAI API requests. Nothing is sent automatically. Approved formats and merchant rules run locally for future SMS imports.")
                Text("Model: $AI_MODEL · up to 20 message structures and 50 merchant names per request. The key stays encrypted on this phone and is excluded from backups and sync.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(key, { key = it }, label = { Text(if (hasKey) "Replace API key · already saved" else "OpenAI API key") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(enabled = !busy && key.isNotBlank(), onClick = { act {
                        val value = key.trim()
                        require(value.length in 20..512 && value.all { it.isLetterOrDigit() || it in "_-" }) { "Enter a valid OpenAI API key" }
                        withContext(Dispatchers.IO) { repo.vault.write("openai-key", value.toByteArray()) }
                        key = ""; hasKey = true; message = "API key saved. No request has been sent."
                    } }) { Text("Save key") }
                    if (hasKey) TextButton(enabled = !busy, onClick = { act {
                        withContext(Dispatchers.IO) { repo.vault.forget("openai-key") }
                        key = ""; hasKey = false; suggestions = null; message = "API key removed. Approved local rules remain."
                    } }) { Text("Remove key") }
                }
                Text("Recent SMS are read from Android only while preparing this preview. OTPs, personal senders and unselected accounts are excluded. Original bodies are never saved. Message structures hide names and numbers; merchant names listed below are shared for categorisation.")
                OutlinedButton(enabled = !busy, onClick = { act {
                    batch = withContext(Dispatchers.IO) { loadAiBatch(context, repo) }
                    suggestions = null
                    if (batch?.empty == true) message = "No eligible examples. Select SMS accounts and import alerts first. Pattern learning also needs SMS permission."
                } }) { Text("Prepare last 30 days · preview only") }
                batch?.takeUnless { it.empty }?.let { preview ->
                    Text("Review data to send", style = MaterialTheme.typography.titleMedium)
                    preview.samples.forEach { sample -> Text("${sample.header}\n${sample.shape}", style = MaterialTheme.typography.bodySmall) }
                    if (preview.merchants.isNotEmpty()) Text("Merchant names: ${preview.merchants.joinToString { it.sharedName }}")
                    Text("Sending uses your API credits. OpenAI receives exactly these message structures, parsing choices and merchant names, with no amounts, account endings, references, dates or notes. OpenAI's data-retention policies apply.", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && hasKey, onClick = { act {
                        val saved = withContext(Dispatchers.IO) { repo.vault.read("openai-key") } ?: error("Save an OpenAI API key first")
                        try { suggestions = AiClient().analyse(saved.toString(Charsets.UTF_8), preview) }
                        finally { saved.fill(0) }
                    } }) { Text("Send preview to OpenAI · uses credits") }
                    if (!hasKey) Text("Save your API key before sending.")
                }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working…") }
                message?.let { Text(it) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                val patterns = settings.filter { it.key.startsWith("sms-pattern:") }
                if (patterns.isNotEmpty()) Text("Approved SMS formats", style = MaterialTheme.typography.titleMedium)
                patterns.forEach { metadata ->
                    val pattern = readSmsPattern(metadata.value)
                    Text("${pattern.header} · ${pattern.trigger} · ${pattern.kind.label}\nAmount ${pattern.amountIndex + 1} · merchant after “${pattern.marker}” · matching format only", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy, onClick = { act { withContext(Dispatchers.IO) { repo.dao.deleteMetadata(metadata.key) } } }) { Text("Remove format") }
                }
                Text("Existing amounts and transaction types are never rewritten. After approving formats, scan again and inspect past entries for incorrect amounts or duplicates. Merchant rules can be removed in Settings.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Done") } })
    suggestions?.let { result -> AiReviewDialog(result, busy, { suggestions = null }, error) { patterns, merchants -> act {
        val count = withContext(Dispatchers.IO) { repo.applyAiSuggestions(result, patterns, merchants) }
        suggestions = null; batch = null
        message = "Rules saved. $count unreviewed transactions categorised. Scan again to use learned formats on past alerts."
    } } }
}

@Composable fun AiReviewDialog(result: AiSuggestions, busy: Boolean, dismiss: () -> Unit, error: String? = null, apply: (Set<String>, Set<String>) -> Unit) {
    var patterns by remember(result) { mutableStateOf(result.patterns.map { it.pattern.key }.toSet()) }
    var merchants by remember(result) { mutableStateOf(result.categories.map { it.first.id }.toSet()) }
    AlertDialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Review AI suggestions") }, text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No changes have been applied. Saved formats still require selected accounts. Category rules update only uncategorised, unreviewed SMS entries in currently selected accounts; reviewed entries stay unchanged.")
                result.patterns.forEach { choice -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(choice.pattern.key in patterns, { checked -> patterns = if (checked) patterns + choice.pattern.key else patterns - choice.pattern.key }, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Use ${choice.pattern.header} ${choice.pattern.trigger} format" })
                    Column {
                        Text("${choice.pattern.header} · ${choice.pattern.kind.label}")
                        Text("Example on phone: ${choice.candidate.merchant} · ${Money.text(choice.candidate.amount)} · ${choice.candidate.status.name.lowercase()}", style = MaterialTheme.typography.bodySmall)
                        Text("Use amount ${choice.pattern.amountIndex + 1}, merchant after “${choice.pattern.marker}”. Applies to matching structures only.", style = MaterialTheme.typography.bodySmall)
                    }
                } }
                result.categories.forEach { (merchant, category) -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(merchant.id in merchants, { checked -> merchants = if (checked) merchants + merchant.id else merchants - merchant.id }, enabled = !busy,
                        modifier = Modifier.semantics { contentDescription = "Categorise ${merchant.sharedName}" })
                    Text("${merchant.sharedName} → $category")
                } }
                if (result.patterns.isEmpty() && result.categories.isEmpty()) Text("No confident rules were suggested. You can keep using manual rules and entries.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("Request usage: ${result.inputTokens} input tokens · ${result.outputTokens} output tokens", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = !busy && (patterns.isNotEmpty() || merchants.isNotEmpty()), onClick = { apply(patterns, merchants) }) { Text("Apply selected rules") } },
        dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Discard suggestions") } })
}
