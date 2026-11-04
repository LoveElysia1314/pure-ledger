package io.github.localledger.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
private fun billPaneDirective(): PaneScaffoldDirective {
    val info=currentWindowAdaptiveInfo()
    val directive=calculatePaneScaffoldDirective(info,HingePolicy.AvoidSeparating)
    val hinged=directive.excludedBounds.isNotEmpty()
    val candidate=if(hinged)calculatePaneScaffoldDirectiveWithTwoPanesOnMediumWidth(info,HingePolicy.AvoidSeparating) else directive
    return if(LocalConfiguration.current.screenHeightDp<480)candidate.copy(maxHorizontalPartitions=1) else candidate
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ledgerShowsTwoPanes()=billPaneDirective().maxHorizontalPartitions>1

/** The root owns selection and back navigation, so resize never creates a second history. */
@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun LedgerBillPanes(list: @Composable ()->Unit,detail: @Composable ()->Unit) {
    LedgerBillPaneLayout(billPaneDirective(),list,detail)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun LedgerBillPaneLayout(directive: PaneScaffoldDirective,list: @Composable ()->Unit,detail: @Composable ()->Unit) {
    ListDetailPaneScaffold(directive,ThreePaneScaffoldValue(PaneAdaptedValue.Expanded,PaneAdaptedValue.Expanded,PaneAdaptedValue.Hidden),
        listPane={Box(Modifier.preferredWidth(440.dp).fillMaxSize().testTag("ledger-list-pane")){list()}},
        detailPane={Box(Modifier.fillMaxSize().testTag("ledger-detail-pane")){detail()}},modifier=Modifier.fillMaxSize())
}
