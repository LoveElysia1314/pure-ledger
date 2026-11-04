package io.github.localledger.app.ui
import io.github.localledger.app.appString
import io.github.localledger.app.R


import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Material supplies touch, selection and window layout; the app keeps its task order and density. */
@Composable
internal fun LedgerNavigationFrame(selected: Int, visible: Boolean, onSelect: (Int) -> Unit, content: @Composable () -> Unit) {
    val destinations = listOf(appString(R.string.ui_entries_4bf3c) to LedgerIcons.Statements, appString(R.string.navigation_statistics) to LedgerIcons.Statistics, appString(R.string.ui_settings) to LedgerIcons.Settings)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val type = if (!visible) NavigationSuiteType.None else if (maxWidth >= 600.dp)
            NavigationSuiteType.WideNavigationRailCollapsed else NavigationSuiteType.ShortNavigationBarCompact
        NavigationSuiteScaffold(navigationSuiteType = type, navigationItems = {
            destinations.forEachIndexed { index, (title, icon) ->
                NavigationSuiteItem(selected == index, { onSelect(index) }, icon = { Icon(icon, null) }, label = { Text(title) }, navigationSuiteType = type)
            }
        }, content = content)
    }
}
