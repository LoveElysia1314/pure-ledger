package io.github.localledger.app.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.contentDescription
import kotlinx.coroutines.awaitCancellation
import androidx.compose.ui.text.rememberTextMeasurer

/** Material input behavior with smaller padding, retaining labels and a 48dp minimum target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompactEntryField(value: String, onChange: (String) -> Unit, label: String,
                               modifier: Modifier = Modifier, enabled: Boolean = true,
                               amount: Boolean = false, singleLine: Boolean = true, keyboardType: KeyboardType? = null,
                               showKeyboard: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val style = if (amount) MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontFeatureSettings = "tnum")
        else MaterialTheme.typography.bodyLarge
    BasicTextField(value, onChange, modifier.fillMaxWidth().heightIn(min = if (amount) 56.dp else 48.dp).semantics(mergeDescendants = true) {},
        enabled = enabled, singleLine = singleLine, maxLines = if (singleLine) 1 else 3,
        textStyle = style.copy(color = MaterialTheme.colorScheme.onSurface),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType ?: if (amount) KeyboardType.Decimal else KeyboardType.Text, showKeyboardOnFocus = showKeyboard),
        interactionSource = interaction, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { input -> TextFieldDefaults.DecorationBox(value, input, enabled, singleLine,
            VisualTransformation.None, interaction, label = { Text(label, style = MaterialTheme.typography.labelMedium) },
            shape=LedgerShapes.field,contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                unfocusedIndicatorColor = Color.Transparent,disabledIndicatorColor=Color.Transparent)) })
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun EntryAmountField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, systemKeyboard: Boolean=false) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val imeVisible=WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)>0
    LaunchedEffect(focused,imeVisible) { if(focused && !systemKeyboard && imeVisible)keyboard?.hide() }
    val measurer=rememberTextMeasurer()
    val amountStyle=MaterialTheme.typography.titleLarge.copy(fontSize=24.sp,lineHeight=30.sp,
        fontFeatureSettings="tnum",textAlign=TextAlign.End,color=MaterialTheme.colorScheme.onSurface)
    val labelStyle=MaterialTheme.typography.labelMedium
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelSize=measurer.measure(label,labelStyle,softWrap=false).size.width
        val amountSize=measurer.measure(value.ifEmpty{"0.00"},amountStyle,softWrap=false).size.width
        val gap=with(androidx.compose.ui.platform.LocalDensity.current){12.dp.roundToPx()}
        val inline=labelSize+amountSize+gap<=constraints.maxWidth && '\n' !in value
        val field: @Composable () -> Unit = {
            BasicTextField(value,onChange,Modifier.fillMaxWidth().heightIn(min=48.dp)
                .onFocusChanged { focused=it.isFocused;if(it.isFocused && !systemKeyboard)keyboard?.hide() }.semantics(mergeDescendants=true){},
                enabled=enabled,textStyle=amountStyle,
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal,showKeyboardOnFocus=systemKeyboard),
                cursorBrush=SolidColor(MaterialTheme.colorScheme.primary),decorationBox={ input ->
                    val number: @Composable (Modifier) -> Unit = { modifier ->
                        Box(modifier,contentAlignment=Alignment.CenterEnd) {
                            if(value.isEmpty())Text("0.00",style=amountStyle,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            input()
                        }
                    }
                    if(inline)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Text(label,Modifier.alignByBaseline(),style=labelStyle,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        number(Modifier.weight(1f).alignByBaseline())
                    } else Column(horizontalAlignment=Alignment.End) {
                        Text(label,style=labelStyle,color=MaterialTheme.colorScheme.onSurfaceVariant)
                        number(Modifier.fillMaxWidth())
                    }
                })
        }
        // Manual entry uses its own keypad; recognized entries use the platform decimal keyboard.
        if(systemKeyboard)field() else InterceptPlatformTextInput(interceptor={_,_->awaitCancellation()}){field()}
    }
}

@Composable
internal fun EntryNoteField(value: String, onChange: (String) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val label = io.github.localledger.app.appString(io.github.localledger.app.R.string.ui_note)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    BasicTextField(value, onChange, modifier.fillMaxWidth().heightIn(min = 48.dp)
        .onFocusChanged{focused=it.isFocused}.background(MaterialTheme.colorScheme.surfaceContainer,LedgerShapes.field)
        .border(2.dp,if(focused)MaterialTheme.colorScheme.primary else Color.Transparent,LedgerShapes.field)
        .semantics { contentDescription = label }, enabled = enabled, maxLines = 3,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus(); keyboard?.hide() }),
        decorationBox = { input -> Box(Modifier.padding(horizontal=10.dp,vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            input()
        } })
}
