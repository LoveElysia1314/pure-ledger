package io.github.localledger.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** One stroke vocabulary for navigation and actions. */
internal object LedgerIcons {
    private fun icon(name: String, content: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = content)
    }.build()
    val Back = icon("Back") { moveTo(20f,12f); lineTo(4f,12f); moveTo(11f,5f); lineTo(4f,12f); lineTo(11f,19f) }
    val Previous = icon("Previous") { moveTo(15f,5f); lineTo(8f,12f); lineTo(15f,19f) }
    val Next = icon("Next") { moveTo(9f,5f); lineTo(16f,12f); lineTo(9f,19f) }
    val Down = icon("Down") { moveTo(5f,9f); lineTo(12f,16f); lineTo(19f,9f) }
    val MoveUp = icon("MoveUp") { moveTo(12f,20f); lineTo(12f,4f); moveTo(5f,11f); lineTo(12f,4f); lineTo(19f,11f) }
    val MoveDown = icon("MoveDown") { moveTo(12f,4f); lineTo(12f,20f); moveTo(5f,13f); lineTo(12f,20f); lineTo(19f,13f) }
    val Statements = icon("Statements") { moveTo(5f,3f); lineTo(19f,3f); lineTo(19f,21f); lineTo(5f,21f); close(); moveTo(8f,8f); lineTo(16f,8f); moveTo(8f,12f); lineTo(16f,12f); moveTo(8f,16f); lineTo(13f,16f) }
    val Statistics = icon("Statistics") { moveTo(4f,20f); lineTo(20f,20f); moveTo(7f,17f); lineTo(7f,11f); moveTo(12f,17f); lineTo(12f,4f); moveTo(17f,17f); lineTo(17f,8f) }
    val Settings = icon("Settings") { moveTo(4f,6f); lineTo(20f,6f); moveTo(4f,12f); lineTo(20f,12f); moveTo(4f,18f); lineTo(20f,18f); moveTo(8f,3f); lineTo(8f,9f); moveTo(16f,9f); lineTo(16f,15f); moveTo(10f,15f); lineTo(10f,21f) }
    val Close = icon("Close") { moveTo(6f,6f); lineTo(18f,18f); moveTo(18f,6f); lineTo(6f,18f) }
    val Add = icon("Add") { moveTo(12f,4f); lineTo(12f,20f); moveTo(4f,12f); lineTo(20f,12f) }
    val Swap = icon("Swap") { moveTo(4f,8f);lineTo(20f,8f);lineTo(16f,4f);moveTo(20f,16f);lineTo(4f,16f);lineTo(8f,20f) }
    val Check = icon("Check") { moveTo(4f,12f); lineTo(9f,17f); lineTo(20f,6f) }
    val Search = icon("Search") { moveTo(20f,20f); lineTo(15.5f,15.5f); moveTo(18f,10.5f); arcTo(7.5f,7.5f,0f,true,true,3f,10.5f); arcTo(7.5f,7.5f,0f,true,true,18f,10.5f) }
    val Filter = icon("Filter") { moveTo(3f,4f);lineTo(21f,4f);lineTo(14f,12f);lineTo(14f,20f);lineTo(10f,18f);lineTo(10f,12f);close() }
    val Backspace = icon("Backspace") { moveTo(9f,5f); lineTo(21f,5f); lineTo(21f,19f); lineTo(9f,19f); lineTo(2f,12f); close(); moveTo(12f,9f); lineTo(18f,15f); moveTo(18f,9f); lineTo(12f,15f) }
    val Calendar = icon("Calendar") { moveTo(4f,5f); lineTo(20f,5f); lineTo(20f,21f); lineTo(4f,21f); close(); moveTo(4f,10f); lineTo(20f,10f); moveTo(8f,3f); lineTo(8f,7f); moveTo(16f,3f); lineTo(16f,7f) }
}
