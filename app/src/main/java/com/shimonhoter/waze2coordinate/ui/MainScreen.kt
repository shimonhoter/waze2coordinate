package com.shimonhoter.waze2coordinate.ui

import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.shimonhoter.waze2coordinate.R
import com.shimonhoter.waze2coordinate.Source

// ===== State =====
data class MainUiState(
    val urlInput: String       = "",
    val source: Source         = Source.WAZE,
    val isLoading: Boolean     = false,
    val errorMessage: String?  = null,
    val coordinates: String?   = null,
    val description: String    = "",
    val isDarkTheme: Boolean   = false,
    val isMapExpanded: Boolean = false,
    val mapHeightPx: Int       = 0,
    val isGpsFollowActive: Boolean = false,
)

// ===== Callbacks =====
data class MainCallbacks(
    val onUrlChange: (String) -> Unit         = {},
    val onSourceChange: (Source) -> Unit      = {},
    val onConvert: () -> Unit                 = {},
    val onCopyCoords: () -> Unit              = {},
    val onOpenMaps: () -> Unit                = {},
    val onOpenWaze: () -> Unit                = {},
    val onSendMessage: () -> Unit             = {},
    val onDescriptionChange: (String) -> Unit = {},
    val onToggleTheme: () -> Unit             = {},
    val onExpandMap: () -> Unit               = {},
    val onCollapseMap: () -> Unit             = {},
    val onMapHeightDrag: (Int) -> Unit        = {},
    val onGpsCenter: () -> Unit               = {},
    val onGpsFollow: () -> Unit               = {},
    val onMapHeightMeasured: (Int) -> Unit    = {},
)

// ===== Root composable =====
@Composable
fun MainScreen(
    uiState: MainUiState,
    callbacks: MainCallbacks,
    webView: WebView,
) {
    AppTheme(darkTheme = uiState.isDarkTheme) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // תמיד Column — fullscreen מנוהל ב-Kotlin ע"י FrameLayout native מחוץ ל-Compose
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!uiState.isMapExpanded) {
                    HeaderRow(isDark = uiState.isDarkTheme, onToggleTheme = callbacks.onToggleTheme)
                    InputCard(
                        urlInput = uiState.urlInput, source = uiState.source,
                        isLoading = uiState.isLoading, errorMessage = uiState.errorMessage,
                        onUrlChange = callbacks.onUrlChange, onSourceChange = callbacks.onSourceChange,
                        onConvert = callbacks.onConvert,
                    )
                }
                // MapCard קריאה אחת — כשמורחב הWebView עזב ל-FrameLayout native, הBox ריק
                MapCard(
                    webView = if (!uiState.isMapExpanded) webView else null,
                    mapHeightPx = uiState.mapHeightPx, isExpanded = uiState.isMapExpanded,
                    isGpsFollowActive = uiState.isGpsFollowActive,
                    onExpandMap = callbacks.onExpandMap, onCollapseMap = callbacks.onCollapseMap,
                    onHeightDrag = callbacks.onMapHeightDrag, onGpsCenter = callbacks.onGpsCenter,
                    onGpsFollow = callbacks.onGpsFollow, onMapHeightMeasured = callbacks.onMapHeightMeasured,
                )
                if (!uiState.isMapExpanded) {
                    AnimatedVisibility(
                        visible = uiState.coordinates != null,
                        enter = fadeIn(spring(stiffness = Spring.StiffnessMediumLow)) +
                                expandVertically(spring(stiffness = Spring.StiffnessMediumLow)),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        uiState.coordinates?.let { coords ->
                            ResultCard(
                                coordinates = coords, description = uiState.description,
                                onCopyCoords = callbacks.onCopyCoords, onOpenMaps = callbacks.onOpenMaps,
                                onOpenWaze = callbacks.onOpenWaze, onSendMessage = callbacks.onSendMessage,
                                onDescriptionChange = callbacks.onDescriptionChange,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// MapCard — AndroidView בslot אחד קבוע תמיד, גם כשמורחב וגם כשמוטמע.
// הפתרון לקריסה בהרחבה: AndroidView מוקם פעם אחת בלבד, ללא if/else שמזיז אותו
// בין parents. הגובה/Modifier משתנים בלבד — View.parent לא משתנה לעולם.
// ───────────────────────────────────────────────────────────────
@Composable
private fun MapCard(
    webView: WebView?,
    mapHeightPx: Int,
    isExpanded: Boolean,
    isGpsFollowActive: Boolean,
    onExpandMap: () -> Unit,
    onCollapseMap: () -> Unit,
    onHeightDrag: (Int) -> Unit,
    onGpsCenter: () -> Unit,
    onGpsFollow: () -> Unit,
    onMapHeightMeasured: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val mapHeightDp = with(density) { if (mapHeightPx > 0) mapHeightPx.toDp() else 240.dp }

    val outerModifier = if (isExpanded)
        Modifier.fillMaxSize()
    else
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))

    Column(modifier = outerModifier) {
        // הBox הפנימי גם הוא רק משנה Modifier — AndroidView בתוכו לא זזה
        Box(
            modifier = if (isExpanded) Modifier.fillMaxSize()
                       else Modifier.fillMaxWidth().height(mapHeightDp)
        ) {
            // AndroidView — רק כשה-WebView זמין (לא מורחב ב-native FrameLayout)
            if (webView != null) {
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // WebView נמצא ב-FrameLayout native — הBox ריק
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            }

            // כפתורי GPS — תמיד גלויים
            Column(
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SmallIconButton(R.drawable.ic_gps_center, "מרכז מיקום", onGpsCenter)
                SmallIconButton(
                    iconRes = R.drawable.ic_gps_auto, contentDescription = "מעקב אוטומטי",
                    onClick = onGpsFollow,
                    containerColor = if (isGpsFollowActive) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.surface,
                )
            }

            // כפתור expand/collapse — משתנה לפי מצב
            if (!isExpanded) {
                SmallIconButton(
                    iconRes = R.drawable.ic_expand,
                    contentDescription = stringResource(R.string.btn_pick_on_map),
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    onClick = onExpandMap,
                )
            } else {
                SmallIconButton(
                    iconRes = R.drawable.ic_collapse,
                    contentDescription = "כווץ מפה",
                    modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                    onClick = onCollapseMap,
                )
            }
        }

        // ידית גרירה — רק במצב מוטמע
        if (!isExpanded) {
            ResizeHandle(onHeightDrag = onHeightDrag)
        }
    }
}


// ───────────────────────────────────────────────────────────────
// Header
// ───────────────────────────────────────────────────────────────
@Composable
private fun HeaderRow(isDark: Boolean, onToggleTheme: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleTheme) {
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.ic_theme_toggle),
                contentDescription = "החלף ערכת נושא",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ───────────────────────────────────────────────────────────────
// Input card
// ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InputCard(
    urlInput: String, source: Source, isLoading: Boolean, errorMessage: String?,
    onUrlChange: (String) -> Unit, onSourceChange: (Source) -> Unit, onConvert: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = source == Source.WAZE, onClick = { onSourceChange(Source.WAZE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text(stringResource(R.string.source_waze), fontSize = 13.sp) },
                )
                SegmentedButton(
                    selected = source == Source.MAPS, onClick = { onSourceChange(Source.MAPS) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text(stringResource(R.string.source_maps), fontSize = 13.sp) },
                )
            }
            val hint = if (source == Source.MAPS) stringResource(R.string.hint_url_maps)
                       else stringResource(R.string.hint_url)
            OutlinedTextField(
                value = urlInput, onValueChange = onUrlChange,
                label = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor      = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor    = Color.Transparent,
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            )
            Button(
                onClick = onConvert, enabled = !isLoading,
                modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(10.dp),
            ) {
                if (isLoading) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.btn_convert), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            AnimatedVisibility(visible = errorMessage != null) {
                errorMessage?.let { msg ->
                    Surface(shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                        Text(msg, color = MaterialTheme.colorScheme.onErrorContainer,
                            fontSize = 13.sp, modifier = Modifier.padding(10.dp))
                    }
                }
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────
// Result card
// ───────────────────────────────────────────────────────────────
@Composable
private fun ResultCard(
    coordinates: String, description: String,
    onCopyCoords: () -> Unit, onOpenMaps: () -> Unit, onOpenWaze: () -> Unit,
    onSendMessage: () -> Unit, onDescriptionChange: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(onClick = onCopyCoords, shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center) {
                    Text(coordinates, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = 16.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    Icon(ImageVector.vectorResource(R.drawable.ic_copy), "העתק",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                        modifier = Modifier.size(18.dp))
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ActionChip(stringResource(R.string.label_google_maps), R.drawable.ic_pin, Modifier.weight(1f), onOpenMaps)
                ActionChip(stringResource(R.string.label_waze), R.drawable.ic_navigate, Modifier.weight(1f), onOpenWaze)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            OutlinedTextField(
                value = description, onValueChange = onDescriptionChange,
                label = { Text(stringResource(R.string.hint_description)) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor   = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedBorderColor      = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor    = Color.Transparent,
                ),
                textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
            )
            Button(onClick = onSendMessage, modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor   = MaterialTheme.colorScheme.onSecondary)) {
                Icon(ImageVector.vectorResource(R.drawable.ic_send), null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_send_message), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ───────────────────────────────────────────────────────────────
// Atoms
// ───────────────────────────────────────────────────────────────

@Composable
private fun ActionChip(label: String, iconRes: Int, modifier: Modifier, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.height(42.dp)) {
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(ImageVector.vectorResource(iconRes), null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SmallIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    FilledIconButton(onClick = onClick, modifier = modifier.size(34.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor   = if (containerColor == MaterialTheme.colorScheme.surface)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onPrimary,
        )) {
        Icon(ImageVector.vectorResource(iconRes), contentDescription, modifier = Modifier.size(16.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ResizeHandle(onHeightDrag: (Int) -> Unit, modifier: Modifier = Modifier) {
    var startY by remember { mutableFloatStateOf(0f) }
    Box(modifier = modifier.fillMaxWidth().height(18.dp)
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .pointerInteropFilter { event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> { startY = event.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val delta = (event.rawY - startY).toInt()
                    startY = event.rawY; onHeightDrag(delta); true
                }
                else -> true
            }
        }, contentAlignment = Alignment.Center) {
        Box(modifier = Modifier.width(32.dp).height(4.dp)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                RoundedCornerShape(2.dp)))
    }
}
