package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun LicenseScreen() {
    val licenses = listOf(Triple(appString(R.string.ui_application), "GPL-3.0-only", "GPL-3.0-only.txt"),
        Triple("AndroidX · Kotlin · Coroutines", "Apache-2.0", "Apache-2.0.txt"),
        Triple(appString(R.string.ui_lucide_icons), "ISC / MIT", "Lucide.txt"))
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var retry by rememberSaveable { mutableIntStateOf(0) }
    val license = licenses.find { it.third == selected }
    if (license == null) Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(12.dp)) {
        Section { licenses.forEach { item -> SettingLine(item.first, item.second, { selected = item.third }, stackValue = true) } }
    } else {
        BackHandler { selected = null }
        val context = LocalContext.current
        val text by produceState<Result<String>?>(null, license.third, retry) {
            value = withContext(Dispatchers.IO) { runCatching { context.assets.open("licenses/${license.third}").bufferedReader().use { it.readText() } } }
        }
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(license.first, style = MaterialTheme.typography.titleMedium)
            if (text == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (text!!.isFailure) { Text(appString(R.string.ui_could_not_read_the_license)); TextButton({ retry++ }) { Text(appString(R.string.ui_retry)) } }
            else Text(text!!.getOrThrow(), style = MaterialTheme.typography.bodySmall)
        }
    }
}
