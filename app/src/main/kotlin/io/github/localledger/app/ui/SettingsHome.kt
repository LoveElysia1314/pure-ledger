package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsHome(status: String, appearance: String, busy: Boolean, onManager: (String) -> Unit,
                          onAppearance: () -> Unit, onImport: () -> Unit, onExport: () -> Unit, onAbout: () -> Unit, onLanguage: () -> Unit = {}, onCategoryDisplay: () -> Unit = {}) {
    val automatic: @Composable () -> Unit = { Section { SettingLine(appString(R.string.ui_automatic_entries), status, { onManager("automatic") }, stackValue = true) } }
    val catalogs: @Composable () -> Unit = { Section {
        listOf("books" to appString(R.string.ui_ledger), "accounts" to appString(R.string.ui_account), "categories" to appString(R.string.ui_category)).forEachIndexed { index,(key, label) ->
            if(index>0)SettingsDivider()
            SettingLine(label, "", { onManager(key) })
        }
    } }
    val theme: @Composable () -> Unit = { Section {
        SettingLine(appString(R.string.ui_appearance), appearance, onAppearance)
        SettingsDivider()
        SettingLine(appString(R.string.ui_category_layout),LocalCategoryDisplay.current.title,onCategoryDisplay)
        SettingsDivider()
        SettingLine(appString(R.string.language), io.github.localledger.app.AppLanguages.selected(androidx.compose.ui.platform.LocalContext.current)?.nativeName ?: appString(R.string.ui_system_default), onLanguage, stackValue = true)
    } }
    val data: @Composable () -> Unit = { Section {
        SettingLine(appString(R.string.ui_import_ledger), "", onImport, !busy); SettingsDivider(); SettingLine(appString(R.string.ui_export_data), "", onExport, !busy)
    } }
    val about: @Composable () -> Unit = { Section { SettingLine(appString(R.string.ui_about), "", onAbout) } }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (maxWidth >= 560.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { automatic() }; Column(Modifier.weight(1f)) { theme() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) { catalogs() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { data(); about() }
            }
        } else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            automatic(); catalogs(); theme(); data(); about()
        }
    }
}
