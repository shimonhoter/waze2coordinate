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
import com.shimonhoter.waze2coordinate.R
import com.shimonhoter.waze2coordinate.Source

// ===== Tabs =====
enum class AppTab { MAP, POINTS, ROUTES, SETTINGS }

// ===== State =====
data class MainUiState(
    val urlInput: String              = "",
    val source: Source                = Source.WAZE,
    val isLoading: Boolean            = false,
    val errorMessage: String?         = null,
    val coordinates: String?          = null,
    val description: String           = "",
    val isDarkTheme: Boolean          = false,
    val isMapExpanded: Boolean        = false,
    val mapHeightPx: Int              = 0,
    val isGpsFollowActive: Boolean    = false,
    val activeTab: AppTab             = AppTab.MAP,
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
    val onTabChange: (AppTab) -> Unit         = {},
)

// ===== Tab Bar Height =====
val TAB_BAR_HEIGHT = 56.dp
val PANEL_PEEK_HEIGHT = 120.dp   // גובה Panel מינימלי (peek)
val PANEL_DEFAULT_HEIGHT = 220.dp // גובה ברירת מחדל

// ===== Root composable =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    uiState: MainUiState,
    callbacks: MainCallbacks,
    webView: WebView,
) {
    AppTheme(darkTheme = uiState.isDarkTheme) {
        val density = LocalDensity.current

        // גובה ה-panel לפי טאב — MAP: peek, שאר: full
        val sheetPeekHeight = if (uiState.activeTab == AppTab.MAP)
            PANEL_PEEK_HEIGHT else PANEL_DEFAULT_HEIGHT

        val sheetState = rememberBottomSheetScaffoldState(
            bottomSheetState = rememberStandardBottomSheetState(
                initialValue = SheetValue.PartiallyExpanded,
                skipHiddenState = true,
            )
        )

        BottomSheetScaffold(
            scaffoldState = sheetState,
            sheetPeekHeight = sheetPeekHeight + TAB_BAR_HEIGHT,
            sheetDragHandle = {
                // ידית גרירה + Tab Bar יחד בראש ה-sheet
                Column {
                    // ידית גרירה
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(modifier = Modifier
                            .width(36.dp).height(4.dp)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(2.dp)))
                    }
                    // Tab Bar
                    AppTabBar(
                        activeTab = uiState.activeTab,
                        onTabChange = callbacks.onTabChange,
                    )
                }
            },
            sheetContent = {
                // תוכן ה-panel לפי טאב
                Box(modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = PANEL_DEFAULT_HEIGHT)
                ) {
                    when (uiState.activeTab) {
                        AppTab.MAP -> MapTabPanel(
                            uiState = uiState,
                            callbacks = callbacks,
                        )
                        AppTab.POINTS -> PointsTabPanel()
                        AppTab.ROUTES -> RoutesTabPanel()
                        AppTab.SETTINGS -> SettingsTabPanel(
                            isDark = uiState.isDarkTheme,
                            onToggleTheme = callbacks.onToggleTheme,
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
            sheetContainerColor = MaterialTheme.colorScheme.surface,
            sheetTonalElevation = 2.dp,
        ) { innerPadding ->
            // מפה — ממלאת את כל השטח מעל ה-sheet
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
            ) {
                if (uiState.isMapExpanded) {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    SmallIconButton(
                        iconRes = R.drawable.ic_collapse,
                        contentDescription = "כווץ מפה",
                        modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
                        onClick = callbacks.onCollapseMap,
                    )
                } else {
                    AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                    // כפתורי GPS
                    Column(
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SmallIconButton(R.drawable.ic_gps_center, "מרכז מיקום", callbacks.onGpsCenter)
                        SmallIconButton(
                            iconRes = R.drawable.ic_gps_auto,
                            contentDescription = "מעקב GPS",
                            onClick = callbacks.onGpsFollow,
                            containerColor = if (uiState.isGpsFollowActive)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface,
                        )
                    }
                    SmallIconButton(
                        iconRes = R.drawable.ic_expand,
                        contentDescription = "הרחב מפה",
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        onClick = callbacks.onExpandMap,
                    )
                }
            }
        }
    }
}

// ===== Tab Bar =====
@Composable
private fun AppTabBar(activeTab: AppTab, onTabChange: (AppTab) -> Unit) {
    NavigationBar(
        modifier = Modifier.height(TAB_BAR_HEIGHT),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) {
        NavigationBarItem(
            selected = activeTab == AppTab.MAP,
            onClick = { onTabChange(AppTab.MAP) },
            icon = { Text("🗺", fontSize = 18.sp) },
            label = { Text("מפה", fontSize = 11.sp) },
        )
        NavigationBarItem(
            selected = activeTab == AppTab.POINTS,
            onClick = { onTabChange(AppTab.POINTS) },
            icon = { Text("📍", fontSize = 18.sp) },
            label = { Text("נקודות", fontSize = 11.sp) },
        )
        NavigationBarItem(
            selected = activeTab == AppTab.ROUTES,
            onClick = { onTabChange(AppTab.ROUTES) },
            icon = { Text("🛤", fontSize = 18.sp) },
            label = { Text("מסלולים", fontSize = 11.sp) },
        )
        NavigationBarItem(
            selected = activeTab == AppTab.SETTINGS,
            onClick = { onTabChange(AppTab.SETTINGS) },
            icon = { Text("⚙️", fontSize = 18.sp) },
            label = { Text("הגדרות", fontSize = 11.sp) },
        )
    }
}

// ===== Panel: Map Tab =====
@Composable
private fun MapTabPanel(uiState: MainUiState, callbacks: MainCallbacks) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // כרטיס המרה (URL → קואורדינטות)
        ConvertCard(
            urlInput = uiState.urlInput,
            source = uiState.source,
            isLoading = uiState.isLoading,
            errorMessage = uiState.errorMessage,
            onUrlChange = callbacks.onUrlChange,
            onSourceChange = callbacks.onSourceChange,
            onConvert = callbacks.onConvert,
        )
        // כרטיס תוצאה
        AnimatedVisibility(
            visible = uiState.coordinates != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            uiState.coordinates?.let { coords ->
                ResultCard(
                    coordinates = coords,
                    description = uiState.description,
                    onCopyCoords = callbacks.onCopyCoords,
                    onOpenMaps = callbacks.onOpenMaps,
                    onOpenWaze = callbacks.onOpenWaze,
                    onSendMessage = callbacks.onSendMessage,
                    onDescriptionChange = callbacks.onDescriptionChange,
                )
            }
        }
    }
}

// ===== Panel: Points Tab =====
@Composable
private fun PointsTabPanel() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("📍", fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text("נקודות שמורות", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("בקרוב — ייצוא/ייבוא GPX, חיפוש נקודות",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

// ===== Panel: Routes Tab =====
@Composable
private fun RoutesTabPanel() {
    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🛤", fontSize = 40.sp)
            Spacer(Modifier.height(8.dp))
            Text("מסלולים", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text("בקרוב — הקלטת מסלול, מסלולים שמורים, ניווט",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
    }
}

// ===== Panel: Settings Tab =====
@Composable
private fun SettingsTabPanel(isDark: Boolean, onToggleTheme: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("הגדרות", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("מצב כהה", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = isDark, onCheckedChange = { onToggleTheme() })
        }
        HorizontalDivider()
        Text("בקרוב — מפות offline, ייצוא PDF, יחידות מידה",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ===== Convert Card =====
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConvertCard(
    urlInput: String, source: Source, isLoading: Boolean, errorMessage: String?,
    onUrlChange: (String) -> Unit, onSourceChange: (Source) -> Unit, onConvert: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(selected = source == Source.WAZE, onClick = { onSourceChange(Source.WAZE) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text(stringResource(R.string.source_waze), fontSize = 12.sp) })
                SegmentedButton(selected = source == Source.MAPS, onClick = { onSourceChange(Source.MAPS) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text(stringResource(R.string.source_maps), fontSize = 12.sp) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = urlInput, onValueChange = onUrlChange,
                    placeholder = { Text(if (source == Source.MAPS) stringResource(R.string.hint_url_maps)
                                        else stringResource(R.string.hint_url),
                        fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true, modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedBorderColor = Color.Transparent,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Button(
                    onClick = onConvert, enabled = !isLoading,
                    modifier = Modifier.height(56.dp), shape = RoundedCornerShape(8.dp),
                ) {
                    if (isLoading) CircularProgressIndicator(Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    else Text(stringResource(R.string.btn_convert), fontSize = 12.sp)
                }
            }
            AnimatedVisibility(visible = errorMessage != null) {
                errorMessage?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
        }
    }
}

// ===== Result Card =====
@Composable
private fun ResultCard(
    coordinates: String, description: String,
    onCopyCoords: () -> Unit, onOpenMaps: () -> Unit, onOpenWaze: () -> Unit,
    onSendMessage: () -> Unit, onDescriptionChange: (String) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        elevation = CardDefaults.cardElevation(0.dp),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(onClick = onCopyCoords, shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(coordinates, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                        fontSize = 15.sp, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    Icon(ImageVector.vectorResource(R.drawable.ic_copy), null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.size(16.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onOpenMaps, modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.label_google_maps), fontSize = 11.sp, maxLines = 1)
                }
                OutlinedButton(onClick = onOpenWaze, modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.label_waze), fontSize = 11.sp, maxLines = 1)
                }
                Button(onClick = onSendMessage, modifier = Modifier.weight(1f).height(36.dp),
                    shape = RoundedCornerShape(8.dp), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(stringResource(R.string.btn_send_message), fontSize = 11.sp, maxLines = 1)
                }
            }
        }
    }
}

// ===== Atoms =====
@Composable
fun SmallIconButton(
    iconRes: Int, contentDescription: String, onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
) {
    FilledIconButton(onClick = onClick, modifier = modifier.size(38.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor,
            contentColor = if (containerColor == MaterialTheme.colorScheme.surface)
                MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimary,
        )) {
        Icon(ImageVector.vectorResource(iconRes), contentDescription, modifier = Modifier.size(18.dp))
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
