package com.ajamuar.ledger

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable fun ReceiptPanel(id: String, vm: LedgerViewModel) {
    val context = LocalContext.current
    val receipts = remember { Receipts(context, vm.repo.vault) }
    var available by remember(id) { mutableStateOf(receipts.exists(id)) }
    var bitmap by remember(id) { mutableStateOf<Bitmap?>(null) }
    var removing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.action(success = { available = true }) {
            val bytes = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readLimited(8_000_000) }
            receipts.save(id, bytes)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (available) "Replace receipt" else "Attach receipt") }
        if (available) TextButton(onClick = {
            var loaded: Bitmap? = null
            vm.action(success = { bitmap = loaded }) { loaded = receipts.load(id) }
        }) { Text("View receipt") }
        if (available) TextButton(onClick = { removing = true }) { Text("Remove") }
    }
    Text("Receipt images are encrypted on this phone. They are excluded from CSV, repo sync and encrypted ledger backups.", style = MaterialTheme.typography.bodySmall)
    bitmap?.let { image -> Dialog(onDismissRequest = { bitmap = null }) {
        Surface(shape = MaterialTheme.shapes.large) { Column(Modifier.padding(12.dp)) {
            Image(image.asImageBitmap(), "Attached receipt", modifier = Modifier.fillMaxWidth().heightIn(max = 550.dp))
            TextButton(onClick = { bitmap = null }) { Text("Close") }
        } }
    } }
    if (removing) AlertDialog(onDismissRequest = { removing = false }, title = { Text("Remove receipt image?") }, text = { Text("This deletes the local encrypted image. The transaction is kept.") },
        confirmButton = { TextButton(onClick = { vm.action(success = { available = false; removing = false }) { receipts.remove(id) } }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { removing = false }) { Text("Keep") } })
}
