package net.yexe.anicome

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*

fun normalizeNgWords(raw: String): List<String> = raw.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()

@Composable fun NgWordsEditor(raw: String, change: (String) -> Unit) {
    val words = remember(raw) { normalizeNgWords(raw) }
    val context = LocalContext.current
    var adding by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val currentWords by rememberUpdatedState(words)
    val currentChange by rememberUpdatedState(change)
    var transferring by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            transferring = true
            val contents = currentWords.joinToString("\n")
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(contents) } ?: error("出力先を開けません")
                }
                Session.message.value = "NGワードをエクスポートしました"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { Session.message.value = "エクスポートできませんでした"
            } finally { transferring = false }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            transferring = true
            try {
                val imported = withContext(Dispatchers.IO) {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: error("ファイルを開けません")
                    normalizeNgWords(text.removePrefix("\uFEFF")).also { require(it.isNotEmpty()) }
                }
                currentChange((currentWords + imported).distinct().joinToString("\n"))
                Session.message.value = "NGワードをインポートしました"
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { Session.message.value = "インポートできませんでした。1行に1語のテキストを選択してください。"
            } finally { transferring = false }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        AppText("登録した言葉を含むコメントを非表示にします。", style = MaterialTheme.typography.bodyLarge)
        StudioButton({ input = ""; adding = true }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { AppIcon(Icons.Filled.Add, null); Spacer(Modifier.width(8.dp)); AppText("NGワードを追加") }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StudioTonalButton({ import.launch(arrayOf("text/*")) }, Modifier.weight(1f), enabled = !transferring) { AppText("インポート") }
            StudioTonalButton({ export.launch("AniCome-NG-words.txt") }, Modifier.weight(1f), enabled = words.isNotEmpty() && !transferring) { AppText("エクスポート") }
        }
        AppText("${words.size}件", style = MaterialTheme.typography.bodyMedium)
        if (words.isEmpty()) StatusCard { AppText("NGワードはありません", style = MaterialTheme.typography.titleMedium); AppText("追加ボタンから1語ずつ登録できます。テキストファイルの読み込みにも対応しています。") }
        ReferenceGroup {
            words.forEach { word -> StudioRow(headlineContent = { AppText(word, localize = false) }, trailingContent = { StudioIconButton({ change(words.filterNot { it == word }.joinToString("\n")) }) { AppIcon(Icons.Filled.Delete, "$word を削除") } }) }
        }
        AppText("インポートは登録済みの言葉に追加します。重複する言葉はまとめます。", style = MaterialTheme.typography.bodyMedium)
    }
    if (adding) StudioDialog(onDismissRequest = { adding = false }, title = { AppText("NGワードを追加") }, text = {
        NgWordInputContent(input, input.trim() in words) { input = it }
    }, confirmButton = { StudioTextButton({ change((words + input.trim()).distinct().joinToString("\n")); adding = false }, enabled = input.isNotBlank() && input.trim() !in words) { AppText("追加") } }, dismissButton = { StudioTextButton({ adding = false }) { AppText("キャンセル") } })
}

@Composable internal fun NgWordInputContent(input: String, duplicate: Boolean, change: (String) -> Unit) {
    OutlinedTextField(input, change, Modifier.fillMaxWidth(), visualTransformation = rememberRoundedInput(), label = { AppText("非表示にする言葉") }, singleLine = true,
        isError = duplicate, supportingText = if (duplicate) { { AppText("この言葉は登録済みです") } } else null)
}
