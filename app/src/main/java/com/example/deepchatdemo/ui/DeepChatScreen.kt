package com.example.deepchatdemo.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.deepchatdemo.R
import com.example.deepchatdemo.chat.ChatMessage
import com.example.deepchatdemo.chat.ChatRole
import com.example.deepchatdemo.chat.ChatViewModel
import com.example.deepchatdemo.config.ReasoningEffort
import com.example.deepchatdemo.price.PriceLookupViewModel
import com.example.deepchatdemo.ui.light.LightFindingScreen
import com.example.deepchatdemo.ui.light.LightFindingViewModel
import com.example.deepchatdemo.ui.price.PriceLookupScreen
import com.example.deepchatdemo.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private enum class AppMode {
    Advisor,
    PriceLookup,
    LightFinding
}

@Composable
fun DeepChatScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(
        factory = ChatViewModel.factory(context)
    )
    val priceViewModel: PriceLookupViewModel = viewModel(
        factory = PriceLookupViewModel.factory(context)
    )
    val lightFindingViewModel: LightFindingViewModel = viewModel(
        factory = LightFindingViewModel.factory(context)
    )
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val modeSwipeThreshold = with(density) { 72.dp.toPx() }
    var selectedMode by remember { mutableStateOf(AppMode.Advisor) }
    var reasoningEdgeGlowTarget by remember { mutableStateOf(0f) }
    var showImageMenu by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val cachedUri = runCatching {
                    withContext(Dispatchers.IO) {
                        ImageUtils.copyImageUriToCache(context, uri)
                    }
                }.getOrNull()

                if (cachedUri == null) {
                    viewModel.addErrorMessage(context.getString(R.string.image_processing_failed))
                } else {
                    viewModel.onImageSelected(cachedUri)
                }
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val capturedUri = pendingCameraUri
        if (success && capturedUri != null) {
            viewModel.onImageSelected(capturedUri)
        }
        pendingCameraUri = null
    }

    LaunchedEffect(viewModel.messages.size, selectedMode) {
        if (selectedMode == AppMode.Advisor && viewModel.messages.isNotEmpty()) {
            listState.animateScrollToItem(viewModel.messages.lastIndex)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(LiquidColors.backgroundBrush())
    ) {
        LiquidAmbientBackground()
        ReasoningEdgeGlow(
            intensity = reasoningEdgeGlowTarget,
            modifier = Modifier.fillMaxSize()
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(selectedMode, modeSwipeThreshold) {
                    var modeDragAmount = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { modeDragAmount = 0f },
                        onDragCancel = { modeDragAmount = 0f },
                        onDragEnd = {
                            val modes = AppMode.entries
                            val currentIndex = selectedMode.ordinal
                            selectedMode = when {
                                modeDragAmount <= -modeSwipeThreshold -> {
                                    modes.getOrElse(currentIndex + 1) { selectedMode }
                                }
                                modeDragAmount >= modeSwipeThreshold -> {
                                    modes.getOrElse(currentIndex - 1) { selectedMode }
                                }
                                else -> selectedMode
                            }
                            modeDragAmount = 0f
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            modeDragAmount += dragAmount
                            change.consume()
                        }
                    )
                }
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(top = 18.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            HeaderBar(
                selectedMode = selectedMode,
                selectedReasoningEffort = viewModel.selectedReasoningEffort,
                hasPlatformAccess = viewModel.hasPlatformAccess,
                onModeSelected = { selectedMode = it },
                onReasoningGlowIntensityChange = { reasoningEdgeGlowTarget = it },
                onReasoningEffortSelected = viewModel::updateReasoningEffort
            )
            Spacer(Modifier.height(16.dp))

            AnimatedContent(
                targetState = selectedMode,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                transitionSpec = {
                    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    slideInHorizontally(
                        animationSpec = tween(durationMillis = 260),
                        initialOffsetX = { width -> direction * width }
                    ) togetherWith slideOutHorizontally(
                        animationSpec = tween(durationMillis = 260),
                        targetOffsetX = { width -> -direction * width }
                    )
                },
                label = "mode content"
            ) { mode ->
                when (mode) {
                    AppMode.Advisor -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                items(viewModel.messages, key = { it.id }) { message ->
                                    MessageRow(message = message)
                                }
                            }

                            Spacer(Modifier.height(14.dp))
                            ComposerBar(
                                text = viewModel.inputText,
                                selectedImageUri = viewModel.selectedImageUri,
                                isSending = viewModel.isSending,
                                onTextChange = viewModel::onInputTextChange,
                                onImageClick = { showImageMenu = true },
                                onClearImage = viewModel::clearSelectedImage,
                                onSend = { viewModel.sendMessage(context) }
                            )
                        }
                    }
                    AppMode.PriceLookup -> {
                        PriceLookupScreen(
                            uiState = priceViewModel.uiState,
                            modifier = Modifier.fillMaxSize(),
                            onSelectColumn = priceViewModel::selectColumn,
                            onFilterValueChange = priceViewModel::onFilterValueChange,
                            onAddFilter = priceViewModel::addFilter,
                            onRemoveFilter = priceViewModel::removeFilter,
                            onStartSearch = priceViewModel::startSearch,
                            onRefreshSearch = priceViewModel::refreshSearch,
                            onRetrySearch = priceViewModel::retrySearch,
                            resolveImageUrl = priceViewModel::resolveImageUrl,
                            lightBindingForCode = lightFindingViewModel::bindingForCode,
                            onBindLight = { item ->
                                lightFindingViewModel.prefillFromPrice(
                                    itemCode = item.code,
                                    itemName = item.nameCn.ifBlank { item.nameEn }
                                )
                                selectedMode = AppMode.LightFinding
                            },
                            onTurnOnLight = { item ->
                                lightFindingViewModel.lightByItemCode(item.code)
                            },
                            onTurnOffLight = { item ->
                                lightFindingViewModel.turnOffByItemCode(item.code)
                            }
                        )
                    }
                    AppMode.LightFinding -> {
                        LightFindingScreen(
                            uiState = lightFindingViewModel.uiState,
                            modifier = Modifier.fillMaxSize(),
                            onServerUrlChange = lightFindingViewModel::onServerUrlChange,
                            onStationIdChange = lightFindingViewModel::onStationIdChange,
                            onItemCodeChange = lightFindingViewModel::onItemCodeChange,
                            onTagIdChange = lightFindingViewModel::onTagIdChange,
                            onSaveAndConnect = lightFindingViewModel::saveAndConnect,
                            onDisconnect = lightFindingViewModel::disconnect,
                            onToggleSettings = lightFindingViewModel::toggleSettings,
                            onBindCurrent = lightFindingViewModel::bindCurrent,
                            onLightCurrentItem = lightFindingViewModel::lightByCurrentItem,
                            onTurnOffCurrentItem = lightFindingViewModel::turnOffByCurrentItem,
                            onTurnOffAll = lightFindingViewModel::turnOffAllBound,
                            onUnbind = lightFindingViewModel::unbind,
                            onSelectColor = lightFindingViewModel::selectColor,
                            onSelectLegacyCandidate =
                                lightFindingViewModel::selectLegacyMigrationCandidate,
                            onConfirmLegacyMigration =
                                lightFindingViewModel::confirmLegacyBindingMigration,
                            onAcknowledgeLegacyConflicts =
                                lightFindingViewModel::acknowledgeLegacyMigrationConflicts,
                            onDismissLegacyMigration =
                                lightFindingViewModel::dismissLegacyMigrationPreview,
                            onScreenActiveChanged =
                                lightFindingViewModel::onScreenActiveChanged
                        )
                    }
                }
            }
        }

        if (showImageMenu) {
            ImageSourceDialog(
                onTakePhoto = {
                    showImageMenu = false
                    val uri = runCatching {
                        ImageUtils.createTempImageUri(context)
                    }.getOrNull()

                    if (uri == null) {
                        viewModel.addErrorMessage(context.getString(R.string.image_processing_failed))
                    } else {
                        pendingCameraUri = uri
                        cameraLauncher.launch(uri)
                    }
                },
                onChooseGallery = {
                    showImageMenu = false
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onDismiss = { showImageMenu = false }
            )
        }

    }
}

@Composable
private fun MessageRow(message: ChatMessage) {
    if (message.role == ChatRole.USER) {
        UserBubble(
            text = message.content,
            imageUri = message.imageUri,
            modifier = Modifier.fillMaxWidth()
        )
    } else if (message.isLoading) {
        TypingBubble(modifier = Modifier.fillMaxWidth())
    } else {
        AssistantBubble(
            text = message.content,
            isError = message.isError,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun HeaderBar(
    selectedMode: AppMode,
    selectedReasoningEffort: ReasoningEffort,
    hasPlatformAccess: Boolean,
    onModeSelected: (AppMode) -> Unit,
    onReasoningGlowIntensityChange: (Float) -> Unit,
    onReasoningEffortSelected: (ReasoningEffort) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
        ) {
            ReasoningDepthButton(
                selectedEffort = selectedReasoningEffort,
                onEffortSelected = onReasoningEffortSelected,
                onEdgeGlowIntensityChange = onReasoningGlowIntensityChange,
                modifier = Modifier
                    .align(Alignment.CenterStart)
            )
            Text(
                text = "HighTac AI",
                color = LiquidColors.Ink,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
                modifier = Modifier.align(Alignment.Center)
            )
            Spacer(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(84.dp)
                    .height(44.dp)
            )
        }
        Spacer(Modifier.height(10.dp))
        ModeSwitch(
            selectedMode = selectedMode,
            hasPlatformAccess = hasPlatformAccess,
            onModeSelected = onModeSelected
        )
    }
}

@Composable
private fun ReasoningDepthButton(
    selectedEffort: ReasoningEffort,
    onEffortSelected: (ReasoningEffort) -> Unit,
    onEdgeGlowIntensityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var popupMounted by remember { mutableStateOf(false) }
    var morphExpanded by remember { mutableStateOf(false) }
    var popupOffset by remember { mutableStateOf(IntOffset.Zero) }

    fun closeControl() {
        if (!popupMounted) return
        onEdgeGlowIntensityChange(0f)
        morphExpanded = false
        scope.launch {
            delay(220)
            popupMounted = false
        }
    }

    LaunchedEffect(popupMounted) {
        if (popupMounted) {
            morphExpanded = false
            delay(16)
            morphExpanded = true
        }
    }

    LaunchedEffect(popupMounted, selectedEffort) {
        onEdgeGlowIntensityChange(
            if (popupMounted) selectedEffort.edgeGlowIntensity() else 0f
        )
    }

    Box(
        modifier = modifier
            .width(84.dp)
            .height(38.dp)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                popupOffset = IntOffset(
                    x = position.x.roundToInt(),
                    y = position.y.roundToInt()
                )
            }
    ) {
        if (!popupMounted) {
            ReasoningStrengthCapsule(
                selectedEffort = selectedEffort,
                onClick = { popupMounted = true },
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (popupMounted) {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset(
                x = popupOffset.x - with(density) { 22.dp.toPx() }.roundToInt(),
                y = popupOffset.y
            ),
            onDismissRequest = ::closeControl,
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = false
            )
        ) {
            Box(
                modifier = Modifier.width(98.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                ReasoningMorphingSlider(
                    selectedEffort = selectedEffort,
                    expanded = morphExpanded,
                    onEffortSelected = onEffortSelected,
                    density = density
                )
            }
        }
    }
}

@Composable
private fun ReasoningStrengthCapsule(
    selectedEffort: ReasoningEffort,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val glow by animateFloatAsState(
        targetValue = selectedEffort.glowLevel,
        animationSpec = tween(durationMillis = 180),
        label = "reasoning strength capsule glow"
    )
    val shape = RoundedCornerShape(20.dp)
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .shadow(
                elevation = (5f + glow * 9f).dp,
                shape = shape,
                ambientColor = Color(0xFF7B3CFF).copy(alpha = 0.10f + glow * 0.15f),
                spotColor = Color(0xFFB45CFF).copy(alpha = 0.12f + glow * 0.22f)
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.34f),
                        Color.White.copy(alpha = 0.22f),
                        Color(0xFFE9D7FF).copy(alpha = 0.10f + glow * 0.12f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.50f),
                        Color(0xFF7B3CFF).copy(alpha = 0.18f + glow * 0.30f),
                        Color(0xFFB45CFF).copy(alpha = 0.12f + glow * 0.28f)
                    )
                ),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .semantics {
                contentDescription = "强度"
                stateDescription = "当前：强度:${selectedEffort.strengthLevel()}"
            }
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .shadow(
                    elevation = (2f + glow * 8f).dp,
                    shape = CircleShape,
                    ambientColor = Color(0xFF7B3CFF).copy(alpha = 0.18f + glow * 0.32f),
                    spotColor = Color(0xFFC26BFF).copy(alpha = 0.18f + glow * 0.38f)
                )
                .clip(CircleShape)
                .background(Color(0xFF8D39FF).copy(alpha = 0.48f + glow * 0.42f))
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = "强度:${selectedEffort.strengthLevel()}",
            color = LiquidColors.Ink.copy(alpha = 0.92f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            letterSpacing = 0.sp
        )
    }
}

@Composable
private fun ReasoningMorphingSlider(
    selectedEffort: ReasoningEffort,
    expanded: Boolean,
    onEffortSelected: (ReasoningEffort) -> Unit,
    density: androidx.compose.ui.unit.Density,
    modifier: Modifier = Modifier
) {
    val efforts = ReasoningEffort.entries.toList()
    val selectedIndex = efforts.indexOf(selectedEffort).coerceAtLeast(0)
    val lastIndex = (efforts.size - 1).coerceAtLeast(1)
    val selectedFraction = selectedIndex.toFloat() / lastIndex.toFloat()
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val settledFraction by animateFloatAsState(
        targetValue = selectedFraction,
        animationSpec = tween(durationMillis = 150),
        label = "reasoning settled fraction"
    )
    val displayFraction = dragFraction ?: settledFraction
    val settledGlow by animateFloatAsState(
        targetValue = selectedEffort.glowLevel.coerceAtLeast(0.28f),
        animationSpec = tween(durationMillis = 150),
        label = "reasoning settled glow"
    )
    val glow = dragFraction
        ?.let { (0.34f + it * 0.66f).coerceIn(0.34f, 1.0f) }
        ?: settledGlow
    val width by animateDpAsState(
        targetValue = if (expanded) 54.dp else 84.dp,
        animationSpec = tween(durationMillis = 240),
        label = "reasoning morph width"
    )
    val height by animateDpAsState(
        targetValue = if (expanded) 244.dp else 38.dp,
        animationSpec = tween(durationMillis = 240),
        label = "reasoning morph height"
    )
    val corner by animateDpAsState(
        targetValue = if (expanded) 28.dp else 20.dp,
        animationSpec = tween(durationMillis = 240),
        label = "reasoning morph corner"
    )
    val sliderAlpha by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "reasoning slider alpha"
    )
    val labelAlpha by animateFloatAsState(
        targetValue = if (expanded) 0f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "reasoning label alpha"
    )
    val railTop = 27.dp
    val railHeight = 190.dp
    val knobSize = 42.dp
    val knobOffset = railTop + railHeight * displayFraction - knobSize / 2
    val shape = RoundedCornerShape(corner)

    LaunchedEffect(expanded) {
        if (!expanded) {
            dragFraction = null
        }
    }

    fun fractionForY(y: Float): Float {
        val railTopPx = with(density) { railTop.toPx() }
        val railHeightPx = with(density) { railHeight.toPx() }
        return ((y - railTopPx) / railHeightPx).coerceIn(0f, 1f)
    }

    fun selectNearest(y: Float, dragging: Boolean) {
        val fraction = fractionForY(y)
        if (dragging) {
            dragFraction = fraction
        }
        val index = (fraction * lastIndex.toFloat())
            .roundToInt()
            .coerceIn(0, lastIndex)
        onEffortSelected(efforts[index])
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .shadow(
                elevation = (10f + glow * 22f).dp,
                shape = shape,
                ambientColor = Color(0xFF7B3CFF).copy(alpha = 0.12f + glow * 0.20f),
                spotColor = Color(0xFFC26BFF).copy(alpha = 0.15f + glow * 0.42f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.34f),
                        Color(0xFFF4E9FF).copy(alpha = 0.23f + glow * 0.05f),
                        Color(0xFFD7B7FF).copy(alpha = 0.13f + glow * 0.14f),
                        Color(0xFFA45CFF).copy(alpha = 0.09f + glow * 0.20f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.48f),
                        Color(0xFF8D39FF).copy(alpha = 0.18f + glow * 0.28f),
                        Color(0xFFC26BFF).copy(alpha = 0.22f + glow * 0.42f)
                    )
                ),
                shape = shape
            )
            .pointerInput(expanded) {
                if (expanded) {
                    detectTapGestures { offset ->
                        dragFraction = null
                        selectNearest(offset.y, dragging = false)
                    }
                }
            }
            .pointerInput(expanded) {
                if (expanded) {
                    detectVerticalDragGestures(
                        onDragStart = { offset -> selectNearest(offset.y, dragging = true) },
                        onDragEnd = { dragFraction = null },
                        onDragCancel = { dragFraction = null },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            selectNearest(change.position.y, dragging = true)
                        }
                    )
                }
            }
            .semantics {
                contentDescription = "强度滑动按钮"
                stateDescription = "当前：强度:${selectedEffort.strengthLevel()}"
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .alpha(labelAlpha)
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF8D39FF).copy(alpha = 0.48f + glow * 0.42f))
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "强度:${selectedEffort.strengthLevel()}",
                color = LiquidColors.Ink.copy(alpha = 0.92f),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                letterSpacing = 0.sp
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(sliderAlpha)
        ) {
            Canvas(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = railTop)
                    .width(34.dp)
                    .height(railHeight)
            ) {
                val centerX = size.width / 2f
                drawLine(
                    color = Color(0xFFC26BFF).copy(alpha = 0.12f + glow * 0.24f),
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, size.height),
                    strokeWidth = 24.dp.toPx(),
                    cap = StrokeCap.Round
                )
                drawLine(
                    brush = Brush.verticalGradient(
                        listOf(
                            Color(0xFF8D39FF).copy(alpha = 0.24f),
                            Color(0xFFA855F7).copy(alpha = 0.34f + glow * 0.22f),
                            Color(0xFFD07BFF).copy(alpha = 0.42f + glow * 0.48f)
                        ),
                        startY = 0f,
                        endY = size.height
                    ),
                    start = Offset(centerX, 0f),
                    end = Offset(centerX, size.height),
                    strokeWidth = 9.dp.toPx(),
                    cap = StrokeCap.Round
                )
                efforts.forEachIndexed { index, effort ->
                    val y = size.height * index / lastIndex.toFloat()
                    drawCircle(
                        color = Color.White.copy(alpha = 0.30f + effort.glowLevel * 0.22f),
                        radius = 3.4.dp.toPx(),
                        center = Offset(centerX, y)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = knobOffset - 20.dp)
                    .size(knobSize + 40.dp)
                    .alpha((0.30f + glow * 0.62f).coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFFE7C8FF).copy(alpha = 0.78f),
                                Color(0xFFC26BFF).copy(alpha = 0.36f),
                                Color(0xFF8D39FF).copy(alpha = 0.18f),
                                Color.Transparent
                            )
                        )
                    )
            )

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = knobOffset)
                    .size(knobSize)
                    .shadow(
                        elevation = (18f + glow * 30f).dp,
                        shape = CircleShape,
                        ambientColor = Color(0xFF7B3CFF).copy(alpha = 0.28f + glow * 0.34f),
                        spotColor = Color(0xFFD07BFF).copy(alpha = 0.32f + glow * 0.56f)
                    )
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color.White.copy(alpha = 0.96f),
                                Color(0xFFF1D7FF).copy(alpha = 0.82f),
                                Color(0xFFC26BFF).copy(alpha = 0.66f + glow * 0.26f),
                                Color(0xFF8D39FF).copy(alpha = 0.52f + glow * 0.38f),
                                Color(0xFF5E24D9).copy(alpha = 0.36f + glow * 0.34f)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.72f),
                        shape = CircleShape
                    )
            )
        }
    }
}

private fun ReasoningEffort.strengthLevel(): Int {
    return (ReasoningEffort.entries.indexOf(this) + 1).coerceIn(1, 6)
}

@Composable
private fun LegacyReasoningDepthButton(
    selectedEffort: ReasoningEffort,
    onEffortSelected: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var expanded by remember { mutableStateOf(false) }
    var popupMounted by remember { mutableStateOf(false) }
    var popupOffset by remember { mutableStateOf(IntOffset.Zero) }
    val glow by animateFloatAsState(
        targetValue = selectedEffort.glowLevel,
        animationSpec = tween(durationMillis = 180),
        label = "reasoning capsule glow"
    )
    val elevation by animateDpAsState(
        targetValue = (8f + glow * 12f).dp,
        animationSpec = tween(durationMillis = 180),
        label = "reasoning capsule elevation"
    )
    val shape = RoundedCornerShape(24.dp)
    val interactionSource = remember { MutableInteractionSource() }

    LaunchedEffect(expanded) {
        if (!expanded && popupMounted) {
            delay(220)
            popupMounted = false
        }
    }

    Box(
        modifier = modifier
            .width(96.dp)
            .height(42.dp)
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val gapPx = with(density) { 8.dp.toPx() }
                popupOffset = IntOffset(
                    x = position.x.roundToInt(),
                    y = (position.y + coordinates.size.height + gapPx).roundToInt()
                )
            }
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color(0xFF2F8CFF).copy(alpha = 0.10f + glow * 0.18f),
                spotColor = Color(0xFF2ED6E8).copy(alpha = 0.12f + glow * 0.28f)
            )
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.48f + glow * 0.10f),
                        Color.White.copy(alpha = 0.34f + glow * 0.10f),
                        Color(0xFFBFEFFF).copy(alpha = 0.10f + glow * 0.20f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.72f),
                        Color(0xFF2F8CFF).copy(alpha = 0.18f + glow * 0.45f),
                        Color(0xFF2ED6E8).copy(alpha = 0.12f + glow * 0.34f)
                    )
                ),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                if (expanded) {
                    expanded = false
                } else {
                    popupMounted = true
                    expanded = true
                }
            }
            .semantics {
                contentDescription = "思考深度"
                stateDescription = "当前：${selectedEffort.displayName}"
            },
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(
                                Color(0xFF2ED6E8).copy(alpha = 0.55f + glow * 0.40f),
                                Color(0xFF2F8CFF).copy(alpha = 0.18f + glow * 0.30f)
                            )
                        )
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "思考深度",
                color = LiquidColors.Ink,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                letterSpacing = 0.sp
            )
        }
    }

    if (popupMounted) {
        Popup(
            alignment = Alignment.TopStart,
            offset = popupOffset,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(
                focusable = true,
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                clippingEnabled = false
            )
        ) {
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = tween(180)) +
                    slideInVertically(
                        animationSpec = tween(220),
                        initialOffsetY = { -it / 4 }
                    ) +
                    expandVertically(animationSpec = tween(220)),
                exit = fadeOut(animationSpec = tween(160)) +
                    slideOutVertically(
                        animationSpec = tween(180),
                        targetOffsetY = { -it / 5 }
                    ) +
                    shrinkVertically(animationSpec = tween(180))
            ) {
                ReasoningDepthSliderDropdown(
                    selectedEffort = selectedEffort,
                    onEffortClicked = { effort ->
                        onEffortSelected(effort)
                        scope.launch {
                            delay(250)
                            expanded = false
                        }
                    },
                    onEffortDragged = onEffortSelected
                )
            }
        }
    }
}

@Composable
private fun ReasoningDepthSliderDropdown(
    selectedEffort: ReasoningEffort,
    onEffortClicked: (ReasoningEffort) -> Unit,
    onEffortDragged: (ReasoningEffort) -> Unit,
    modifier: Modifier = Modifier
) {
    val efforts = ReasoningEffort.entries.toList()
    val selectedIndex = efforts.indexOf(selectedEffort).coerceAtLeast(0)
    val lastIndex = (efforts.size - 1).coerceAtLeast(1)
    val density = LocalDensity.current
    val glow by animateFloatAsState(
        targetValue = selectedEffort.glowLevel,
        animationSpec = tween(durationMillis = 180),
        label = "reasoning dropdown glow"
    )
    val rowHeight = 36.dp
    val rowGap = 2.dp
    val railTop = rowHeight / 2
    val railHeight = (rowHeight + rowGap) * lastIndex.toFloat()
    val sliderAreaHeight = rowHeight * efforts.size.toFloat() + rowGap * lastIndex.toFloat()
    val knobSize = 23.dp
    val knobTargetOffset = railTop +
        railHeight * (selectedIndex.toFloat() / lastIndex.toFloat()) -
        knobSize / 2
    val knobOffset by animateDpAsState(
        targetValue = knobTargetOffset,
        animationSpec = tween(durationMillis = 190),
        label = "reasoning knob offset"
    )
    val shape = RoundedCornerShape(30.dp)

    Box(
        modifier = modifier
            .width(148.dp)
            .shadow(
                elevation = (18f + glow * 18f).dp,
                shape = shape,
                ambientColor = Color(0xFF2F8CFF).copy(alpha = 0.10f + glow * 0.18f),
                spotColor = Color(0xFF2ED6E8).copy(alpha = 0.16f + glow * 0.30f)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.72f),
                        Color(0xFFF4FAFF).copy(alpha = 0.66f),
                        Color(0xFFD9F8FF).copy(alpha = 0.28f + glow * 0.22f),
                        Color(0xFFB9EFFF).copy(alpha = 0.16f + glow * 0.30f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        Color.White.copy(alpha = 0.82f),
                        Color(0xFF2F8CFF).copy(alpha = 0.18f + glow * 0.26f),
                        Color(0xFF2ED6E8).copy(alpha = 0.20f + glow * 0.43f)
                    )
                ),
                shape = shape
            )
            .padding(horizontal = 13.dp, vertical = 14.dp)
            .semantics {
                contentDescription = "思考深度滑杆"
                stateDescription = "当前：${selectedEffort.displayName}"
            }
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "思考深度",
                    color = LiquidColors.Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.sp,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = selectedEffort.displayName,
                    color = Color(0xFF236FE7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    letterSpacing = 0.sp
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(sliderAreaHeight)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(rowGap)
                ) {
                    efforts.forEach { effort ->
                        ReasoningDepthOptionRow(
                            effort = effort,
                            selected = effort == selectedEffort,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(rowHeight),
                            onClick = { onEffortClicked(effort) }
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .width(42.dp)
                        .height(sliderAreaHeight)
                        .pointerInput(efforts, density) {
                            val railTopPx = with(density) { railTop.toPx() }
                            val railHeightPx = with(density) { railHeight.toPx() }
                            val stepPx = railHeightPx / lastIndex.toFloat()

                            fun selectNearest(y: Float) {
                                val index = ((y - railTopPx)
                                    .coerceIn(0f, railHeightPx) / stepPx)
                                    .roundToInt()
                                    .coerceIn(0, lastIndex)
                                onEffortDragged(efforts[index])
                            }

                            detectVerticalDragGestures(
                                onDragStart = { offset -> selectNearest(offset.y) },
                                onVerticalDrag = { change, _ ->
                                    change.consume()
                                    selectNearest(change.position.y)
                                }
                            )
                        }
                ) {
                    Canvas(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = railTop)
                            .width(26.dp)
                            .height(railHeight)
                    ) {
                        val centerX = size.width / 2f
                        drawLine(
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color(0xFF7EAFFF).copy(alpha = 0.22f),
                                    Color(0xFF2F8CFF).copy(alpha = 0.24f + glow * 0.18f),
                                    Color(0xFF2ED6E8).copy(alpha = 0.30f + glow * 0.46f)
                                ),
                                startY = 0f,
                                endY = size.height
                            ),
                            start = Offset(centerX, 0f),
                            end = Offset(centerX, size.height),
                            strokeWidth = 6.dp.toPx(),
                            cap = StrokeCap.Round
                        )

                        efforts.forEachIndexed { index, effort ->
                            val y = size.height * index / lastIndex.toFloat()
                            val selected = index == selectedIndex
                            drawCircle(
                                color = if (selected) {
                                    Color(0xFF2ED6E8).copy(alpha = 0.82f + effort.glowLevel * 0.18f)
                                } else {
                                    Color.White.copy(alpha = 0.62f + effort.glowLevel * 0.22f)
                                },
                                radius = if (selected) 5.2.dp.toPx() else 3.6.dp.toPx(),
                                center = Offset(centerX, y)
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .offset(y = knobOffset)
                            .size(knobSize)
                            .shadow(
                                elevation = (8f + glow * 14f).dp,
                                shape = CircleShape,
                                ambientColor = Color(0xFF2F8CFF).copy(alpha = 0.18f + glow * 0.20f),
                                spotColor = Color(0xFF2ED6E8).copy(alpha = 0.20f + glow * 0.42f)
                            )
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.96f),
                                        Color(0xFF46E1FF).copy(alpha = 0.72f + glow * 0.22f),
                                        Color(0xFF2F8CFF).copy(alpha = 0.42f + glow * 0.34f)
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,
                                color = Color.White.copy(alpha = 0.88f),
                                shape = CircleShape
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ReasoningDepthOptionRow(
    effort: ReasoningEffort,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val glow by animateFloatAsState(
        targetValue = if (selected) effort.glowLevel else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "reasoning row glow"
    )
    val interactionSource = remember { MutableInteractionSource() }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .background(
                if (selected) {
                    Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.42f),
                            Color(0xFF7DDFFF).copy(alpha = 0.12f + glow * 0.20f)
                        )
                    )
                } else {
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.Transparent)
                    )
                }
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(start = 10.dp, end = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = effort.displayName,
            color = if (selected) Color(0xFF075AD8) else LiquidColors.Ink.copy(alpha = 0.58f),
            fontSize = if (selected) 14.sp else 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            letterSpacing = 0.sp
        )
    }
}

@Composable
private fun ModeSwitch(
    selectedMode: AppMode,
    hasPlatformAccess: Boolean,
    onModeSelected: (AppMode) -> Unit
) {
    GlassPanel(
        modifier = Modifier
            .width(282.dp)
            .height(46.dp),
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(4.dp),
        alpha = 0.52f,
        elevation = 10.dp
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModeSwitchOption(
                text = stringResource(R.string.mode_advisor),
                selected = selectedMode == AppMode.Advisor,
                statusColor = if (hasPlatformAccess) Color(0xFF20C657) else Color(0xFFFFB23E),
                modifier = Modifier.weight(1f),
                onClick = { onModeSelected(AppMode.Advisor) }
            )
            ModeSwitchOption(
                text = stringResource(R.string.mode_price),
                selected = selectedMode == AppMode.PriceLookup,
                statusColor = Color(0xFF20C657),
                modifier = Modifier.weight(1f),
                onClick = { onModeSelected(AppMode.PriceLookup) }
            )
            ModeSwitchOption(
                text = "寻物",
                selected = selectedMode == AppMode.LightFinding,
                statusColor = Color(0xFF39C8E6),
                modifier = Modifier.weight(1f),
                onClick = { onModeSelected(AppMode.LightFinding) }
            )
        }
    }
}

@Composable
private fun ModeSwitchOption(
    text: String,
    selected: Boolean,
    statusColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(
                if (selected) {
                    Brush.linearGradient(
                        listOf(Color(0xFF2E8BFF), Color(0xFF39C8E6))
                    )
                } else {
                    Brush.linearGradient(
                        listOf(Color.Transparent, Color.Transparent)
                    )
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(7.dp))
            }
            Text(
                text = text,
                color = if (selected) Color.White else LiquidColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ApiConnectedPill(hasPlatformAccess: Boolean) {
    GlassPanel(
        modifier = Modifier.height(46.dp),
        shape = RoundedCornerShape(28.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        alpha = 0.54f,
        elevation = 11.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(if (hasPlatformAccess) Color(0xFF20C657) else Color(0xFFFFB23E))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (hasPlatformAccess) {
                    stringResource(R.string.hightac_connected)
                } else {
                    stringResource(R.string.hightac_approval_required)
                },
                color = LiquidColors.Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.33f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = if (hasPlatformAccess) Color(0xFF7182B0) else Color(0xFFFFA000),
                    modifier = Modifier.size(21.dp)
                )
            }
        }
    }
}

/*
@Composable
private fun PriceLookupPane(
    query: String,
    uiState: PriceLookupUiState,
    modifier: Modifier = Modifier,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    val priceNoResults = stringResource(R.string.price_no_results)

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            if (uiState.isLoading) {
                item {
                    PriceEmptyState(text = stringResource(R.string.price_loading))
                }
            } else if (uiState.errorMessage.isNotBlank()) {
                item {
                    PriceEmptyState(text = uiState.errorMessage)
                }
            } else if (uiState.items.isEmpty()) {
                val emptyText = when {
                    uiState.notice.isNotBlank() -> uiState.notice
                    uiState.sourceLabel.isNotBlank() -> priceNoResults
                    else -> ""
                }
                if (emptyText.isNotBlank()) {
                    item {
                        PriceEmptyState(text = emptyText)
                    }
                }
            } else {
                items(uiState.items, key = { it.id }) { item ->
                    PriceResultCard(item = item)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        PriceSearchComposerBar(
            query = query,
            isSearching = uiState.isLoading,
            onQueryChange = onQueryChange,
            onSearch = onSearch
        )
    }
}

@Composable
private fun PriceSearchComposerBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    onSearch: () -> Unit
) {
    val canSearch = query.isNotBlank() && !isSearching

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        shape = RoundedCornerShape(30.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        alpha = 0.54f,
        elevation = 20.dp
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp)
                    .padding(horizontal = 6.dp),
                textStyle = TextStyle(
                    color = LiquidColors.Ink,
                    fontSize = 18.sp,
                    lineHeight = 24.sp
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (canSearch) onSearch() }),
                maxLines = 1,
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.price_search_hint),
                                color = Color(0xFF8993B1),
                                fontSize = 17.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        innerTextField()
                    }
                }
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .shadow(
                        12.dp,
                        CircleShape,
                        ambientColor = Color(0xFF4B8DFF).copy(alpha = 0.22f),
                        spotColor = Color(0xFF7A48FF).copy(alpha = 0.30f)
                    )
                    .clip(CircleShape)
                    .background(
                        if (canSearch) {
                            LiquidColors.sendBrush()
                        } else {
                            Brush.linearGradient(
                                listOf(Color(0xFFB9C5DD), Color(0xFFAEB7D0))
                            )
                        }
                    )
                    .clickable(enabled = canSearch, onClick = onSearch),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = "Search price",
                    tint = Color.White,
                    modifier = Modifier.size(27.dp)
                )
            }
        }
    }
}

@Composable
private fun PriceLookupStatus(uiState: PriceLookupUiState) {
    val text = when {
        uiState.isLoading -> stringResource(R.string.price_status_loading)
        uiState.sourceLabel.isNotBlank() && uiState.items.isNotEmpty() -> {
            "${uiState.sourceLabel} · ${uiState.items.size} 条"
        }
        uiState.sourceLabel.isNotBlank() -> uiState.sourceLabel
        else -> stringResource(R.string.price_system)
    }
    val dotColor = when {
        uiState.errorMessage.isNotBlank() -> Color(0xFFFF8A65)
        uiState.sourceLabel.contains("简道云") -> Color(0xFF20C657)
        uiState.sourceLabel.isNotBlank() -> Color(0xFFFFB23E)
        else -> Color(0xFF8FA4C8)
    }

    GlassPanel(
        modifier = Modifier.heightIn(min = 44.dp),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        alpha = 0.50f,
        elevation = 9.dp
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                color = LiquidColors.Ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (uiState.notice.isNotBlank() && !uiState.isLoading) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = uiState.notice,
            color = LiquidColors.Muted,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun PriceEmptyState(text: String) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
        alpha = 0.54f,
        elevation = 12.dp
    ) {
        Text(
            text = text,
            color = LiquidColors.Ink,
            fontSize = 16.sp,
            lineHeight = 24.sp
        )
    }
}

@Composable
private fun PriceResultCard(item: PriceLookupItem) {
    GlassPanel(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 15.dp),
        alpha = 0.58f,
        elevation = 14.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.primaryTitle,
                        color = LiquidColors.Ink,
                        fontSize = 18.sp,
                        lineHeight = 23.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (item.secondaryTitle.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = item.secondaryTitle,
                            color = LiquidColors.Muted,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(Modifier.width(12.dp))
                PriceBadge(price = item.price)
            }

            Spacer(Modifier.height(12.dp))
            PriceDetailRow("来源", item.source)
            PriceDetailRow("单位", item.unit)
            PriceDetailRow("品牌", item.brand)
            PriceDetailRow("车型", item.models)
            PriceDetailRow("规格", item.spec)
            PriceDetailRow("库存/数量", item.stock)
            PriceDetailRow("价格日期", item.priceDate)
            PriceDetailRow("备注", item.remark)

            val extraDetails = item.rawDetails
                .filter { (label, value) ->
                    value.isNotBlank() && label !in setOf("来源", "单位", "品牌", "车型", "规格", "库存", "数量", "价格日期", "备注")
                }
                .take(4)
            extraDetails.forEach { (label, value) ->
                PriceDetailRow(label, value)
            }
        }
    }
}

@Composable
private fun PriceBadge(price: String) {
    Box(
        modifier = Modifier
            .widthIn(min = 88.dp, max = 132.dp)
            .heightIn(min = 42.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF083B68), Color(0xFF1F8EC2))
                )
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = price.ifBlank { "未填价" },
            color = Color.White,
            fontSize = if (price.length > 9) 15.sp else 18.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PriceDetailRow(label: String, value: String) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = LiquidColors.Muted,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(74.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = LiquidColors.Ink,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

*/
@Composable
private fun UserBubble(
    text: String,
    imageUri: Uri?,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .widthIn(min = if (imageUri != null) 220.dp else 0.dp, max = 292.dp)
                .shadow(
                    elevation = 14.dp,
                    shape = RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = 24.dp,
                        bottomEnd = 6.dp
                    ),
                    ambientColor = Color(0xFF326FFF).copy(alpha = 0.20f),
                    spotColor = Color(0xFF774CFF).copy(alpha = 0.24f)
                )
                .clip(
                    RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = 24.dp,
                        bottomEnd = 6.dp
                    )
                )
                .background(LiquidColors.userBubbleBrush())
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        listOf(
                            Color.White.copy(alpha = 0.62f),
                            Color.White.copy(alpha = 0.12f)
                        )
                    ),
                    shape = RoundedCornerShape(
                        topStart = 24.dp,
                        topEnd = 24.dp,
                        bottomStart = 24.dp,
                        bottomEnd = 6.dp
                    )
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Column {
                if (imageUri != null) {
                    UriImage(
                        uri = imageUri,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(152.dp)
                            .clip(RoundedCornerShape(18.dp)),
                        contentDescription = "Selected image"
                    )
                    if (text.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 17.sp,
                        lineHeight = 25.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Now",
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
private fun AssistantBubble(
    text: String,
    isError: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top
    ) {
        AiAvatar(Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(12.dp))
        GlassPanel(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(26.dp),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 17.dp),
            alpha = 0.62f,
            elevation = 18.dp
        ) {
            MarkdownText(
                text = text,
                color = if (isError) Color(0xFF9A3240) else LiquidColors.Ink,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val lines = text.trim().lines()
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        lines.forEach { rawLine ->
            val line = rawLine.trimEnd()
            val trimmed = line.trimStart()

            when {
                trimmed.isBlank() -> Spacer(Modifier.height(2.dp))
                trimmed.isMarkdownHeading() -> {
                    val level = trimmed.takeWhile { it == '#' }.length
                    val content = trimmed.drop(level).trim()
                    Text(
                        text = parseInlineMarkdown(content),
                        color = color,
                        fontSize = if (level <= 2) 17.sp else 16.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                trimmed.isMarkdownBullet() -> {
                    MarkdownListRow(
                        marker = "•",
                        body = trimmed.drop(1).trim(),
                        color = color
                    )
                }
                trimmed.isMarkdownNumberedItem() -> {
                    val marker = trimmed.substringBefore(' ').trim()
                    val body = trimmed.drop(marker.length).trim()
                    MarkdownListRow(
                        marker = marker,
                        body = body,
                        color = color
                    )
                }
                else -> {
                    Text(
                        text = parseInlineMarkdown(trimmed),
                        color = color,
                        fontSize = 16.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownListRow(
    marker: String,
    body: String,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = marker,
            color = color,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.width(24.dp)
        )
        Text(
            text = parseInlineMarkdown(body),
            color = color,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun String.isMarkdownHeading(): Boolean {
    val count = takeWhile { it == '#' }.length
    return count in 1..6 && drop(count).startsWith(" ")
}

private fun String.isMarkdownBullet(): Boolean {
    return startsWith("- ") || startsWith("* ") || startsWith("• ")
}

private fun String.isMarkdownNumberedItem(): Boolean {
    val marker = substringBefore(' ', missingDelimiterValue = "")
    if (marker.length < 2) return false
    val last = marker.last()
    return (last == '.' || last == ')') && marker.dropLast(1).all { it.isDigit() }
}

private fun parseInlineMarkdown(value: String) = buildAnnotatedString {
    var index = 0
    while (index < value.length) {
        when {
            value.startsWith("**", index) -> {
                val end = value.indexOf("**", startIndex = index + 2)
                if (end > index) {
                    pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    append(value.substring(index + 2, end))
                    pop()
                    index = end + 2
                } else {
                    append(value[index])
                    index++
                }
            }
            value[index] == '`' -> {
                val end = value.indexOf('`', startIndex = index + 1)
                if (end > index) {
                    pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = Color.White.copy(alpha = 0.45f)
                        )
                    )
                    append(value.substring(index + 1, end))
                    pop()
                    index = end + 1
                } else {
                    append(value[index])
                    index++
                }
            }
            else -> {
                append(value[index])
                index++
            }
        }
    }
}

@Composable
private fun TypingBubble(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        AiAvatar()
        Spacer(Modifier.width(10.dp))
        GlassPanel(
            modifier = Modifier.height(48.dp),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp),
            alpha = 0.56f,
            elevation = 12.dp
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "HighTac AI 正在分析...",
                    color = LiquidColors.Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.width(18.dp))
                TypingDots()
            }
        }
    }
}

@Composable
private fun AiAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(46.dp)
            .shadow(
                10.dp,
                CircleShape,
                ambientColor = Color(0xFF5975FF).copy(alpha = 0.15f),
                spotColor = Color(0xFF5975FF).copy(alpha = 0.20f)
            )
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.70f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = Color(0xFF5569FF),
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun TypingDots() {
    Row(
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Dot(Color(0xFF6EA7FF))
        Dot(Color(0xFF9DABDB).copy(alpha = 0.55f))
        Dot(Color(0xFF6D57FF))
    }
}

@Composable
private fun Dot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun ComposerBar(
    text: String,
    selectedImageUri: Uri?,
    isSending: Boolean,
    onTextChange: (String) -> Unit,
    onImageClick: () -> Unit,
    onClearImage: () -> Unit,
    onSend: () -> Unit
) {
    val canSend = (text.isNotBlank() || selectedImageUri != null) && !isSending

    GlassPanel(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp),
        shape = RoundedCornerShape(30.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
        alpha = 0.54f,
        elevation = 20.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            if (selectedImageUri != null) {
                SelectedImagePreview(
                    uri = selectedImageUri,
                    onClear = onClearImage
                )
                Spacer(Modifier.height(10.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassIconButton(
                    icon = Icons.Rounded.PhotoLibrary,
                    contentDescription = "添加图片",
                    enabled = !isSending,
                    onClick = onImageClick
                )
                Spacer(Modifier.width(12.dp))
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 46.dp, max = 116.dp)
                        .padding(horizontal = 2.dp),
                    textStyle = TextStyle(
                        color = LiquidColors.Ink,
                        fontSize = 18.sp,
                        lineHeight = 24.sp
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (canSend) onSend() }),
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = "输入问题，或添加图片咨询...",
                                    color = Color(0xFF8993B1),
                                    fontSize = 18.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(
                            12.dp,
                            CircleShape,
                            ambientColor = Color(0xFF4B8DFF).copy(alpha = 0.22f),
                            spotColor = Color(0xFF7A48FF).copy(alpha = 0.30f)
                        )
                        .clip(CircleShape)
                        .background(
                            if (canSend) {
                                LiquidColors.sendBrush()
                            } else {
                                Brush.linearGradient(
                                    listOf(
                                        Color(0xFFB9C5DD),
                                        Color(0xFFAEB7D0)
                                    )
                                )
                            }
                        )
                        .clickable(enabled = canSend, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = "发送",
                        tint = Color.White,
                        modifier = Modifier.size(27.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(54.dp)
            .shadow(
                10.dp,
                CircleShape,
                ambientColor = Color(0xFF5F84FF).copy(alpha = 0.10f),
                spotColor = Color(0xFF5F84FF).copy(alpha = 0.18f)
            )
            .clip(CircleShape)
            .background(LiquidColors.glassBrush(0.50f))
            .border(1.dp, Color.White.copy(alpha = 0.72f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) LiquidColors.Ink else LiquidColors.Muted.copy(alpha = 0.55f),
            modifier = Modifier.size(27.dp)
        )
    }
}

@Composable
private fun SelectedImagePreview(
    uri: Uri,
    onClear: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(88.dp)
    ) {
        UriImage(
            uri = uri,
            modifier = Modifier
                .width(104.dp)
                .height(88.dp)
                .clip(RoundedCornerShape(20.dp)),
            contentDescription = "Selected image preview"
        )
        IconButton(
            onClick = onClear,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 74.dp)
                .size(30.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.86f))
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove image",
                tint = LiquidColors.Ink,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun ImageSourceDialog(
    onTakePhoto: () -> Unit,
    onChooseGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onTakePhoto, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Rounded.AddAPhoto,
                        contentDescription = null,
                        tint = LiquidColors.Ink
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Take Photo", color = LiquidColors.Ink)
                }
                TextButton(onClick = onChooseGallery, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        imageVector = Icons.Rounded.PhotoLibrary,
                        contentDescription = null,
                        tint = LiquidColors.Ink
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Choose from Gallery", color = LiquidColors.Ink)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = LiquidColors.Ink)
            }
        },
        containerColor = Color(0xFFF5FAFF),
        shape = RoundedCornerShape(28.dp)
    )
}

@Composable
private fun UriImage(
    uri: Uri,
    modifier: Modifier = Modifier,
    contentDescription: String?
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(uri) { mutableStateOf(false) }

    LaunchedEffect(uri) {
        failed = false
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                ImageUtils.loadBitmapFromUri(context, uri, 720)
            }.getOrNull()
        }
        failed = bitmap == null
    }

    Box(
        modifier = modifier.background(Color.White.copy(alpha = 0.34f)),
        contentAlignment = Alignment.Center
    ) {
        val currentBitmap = bitmap
        if (currentBitmap != null) {
            Image(
                bitmap = currentBitmap.asImageBitmap(),
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else if (failed) {
            Text(
                text = "Image unavailable",
                color = LiquidColors.Ink,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    alpha: Float = 0.56f,
    elevation: Dp = 14.dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .shadow(
                elevation = elevation,
                shape = shape,
                ambientColor = Color(0xFF6884FF).copy(alpha = 0.08f),
                spotColor = Color(0xFF5E73FF).copy(alpha = 0.14f)
            )
            .clip(shape)
            .background(LiquidColors.glassBrush(alpha))
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.88f),
                        Color.White.copy(alpha = 0.23f),
                        Color(0xFF96B4FF).copy(alpha = 0.16f)
                    )
                ),
                shape = shape
            )
            .padding(contentPadding),
        content = content
    )
}

@Composable
private fun ReasoningEdgeGlow(
    intensity: Float,
    modifier: Modifier = Modifier
) {
    val animatedIntensity by animateFloatAsState(
        targetValue = intensity.coerceIn(0f, 1f),
        animationSpec = tween(
            durationMillis = if (intensity > 0f) 220 else 950
        ),
        label = "reasoning edge glow"
    )

    if (animatedIntensity <= 0.01f) return

    Canvas(modifier = modifier.alpha(animatedIntensity)) {
        val purple = Color(0xFF8D39FF)
        val violet = Color(0xFFC26BFF)
        val edgeWidth = min(size.width, size.height) * 0.28f
        val edgeHeight = min(size.width, size.height) * 0.24f

        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    purple.copy(alpha = 0.30f),
                    violet.copy(alpha = 0.13f),
                    Color.Transparent
                ),
                startX = 0f,
                endX = edgeWidth
            ),
            topLeft = Offset.Zero,
            size = Size(edgeWidth, size.height)
        )
        drawRect(
            brush = Brush.horizontalGradient(
                listOf(
                    Color.Transparent,
                    violet.copy(alpha = 0.13f),
                    purple.copy(alpha = 0.30f)
                ),
                startX = size.width - edgeWidth,
                endX = size.width
            ),
            topLeft = Offset(size.width - edgeWidth, 0f),
            size = Size(edgeWidth, size.height)
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    purple.copy(alpha = 0.24f),
                    violet.copy(alpha = 0.11f),
                    Color.Transparent
                ),
                startY = 0f,
                endY = edgeHeight
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, edgeHeight)
        )
        drawRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color.Transparent,
                    violet.copy(alpha = 0.11f),
                    purple.copy(alpha = 0.24f)
                ),
                startY = size.height - edgeHeight,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - edgeHeight),
            size = Size(size.width, edgeHeight)
        )
    }
}

private fun ReasoningEffort.edgeGlowIntensity(): Float {
    return (0.16f + glowLevel * 0.84f).coerceIn(0f, 1f)
}

@Composable
private fun LiquidAmbientBackground() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF79DFFF).copy(alpha = 0.34f), Color.Transparent),
                center = Offset(size.width * 0.08f, size.height * 0.16f),
                radius = size.width * 0.60f
            ),
            radius = size.width * 0.60f,
            center = Offset(size.width * 0.08f, size.height * 0.16f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF7F57FF).copy(alpha = 0.20f), Color.Transparent),
                center = Offset(size.width * 0.88f, size.height * 0.42f),
                radius = size.width * 0.66f
            ),
            radius = size.width * 0.66f,
            center = Offset(size.width * 0.88f, size.height * 0.42f)
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.58f), Color.Transparent),
                center = Offset(size.width * 0.74f, size.height * 0.83f),
                radius = size.width * 0.44f
            ),
            radius = size.width * 0.44f,
            center = Offset(size.width * 0.74f, size.height * 0.83f)
        )
        drawFluidRibbon()
    }
}

private fun DrawScope.drawFluidRibbon() {
    val path = Path().apply {
        moveTo(size.width * 0.78f, 0f)
        cubicTo(
            size.width * 1.05f,
            size.height * 0.13f,
            size.width * 0.69f,
            size.height * 0.33f,
            size.width * 0.98f,
            size.height * 0.53f
        )
        cubicTo(
            size.width * 1.18f,
            size.height * 0.67f,
            size.width * 0.70f,
            size.height * 0.83f,
            size.width * 1.04f,
            size.height
        )
        lineTo(size.width, size.height)
        lineTo(size.width, 0f)
        close()
    }
    drawPath(
        path = path,
        brush = Brush.linearGradient(
            listOf(
                Color.White.copy(alpha = 0.0f),
                Color(0xFF66D9FF).copy(alpha = 0.14f),
                Color.White.copy(alpha = 0.44f),
                Color(0xFF7F57FF).copy(alpha = 0.14f)
            ),
            start = Offset(size.width * 0.58f, 0f),
            end = Offset(size.width, size.height)
        )
    )
    drawOval(
        brush = Brush.radialGradient(
            listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
            center = Offset(size.width * 0.92f, size.height * 0.56f),
            radius = size.width * 0.28f
        ),
        topLeft = Offset(size.width * 0.75f, size.height * 0.45f),
        size = Size(size.width * 0.44f, size.height * 0.23f)
    )
}

private object LiquidColors {
    val Ink = Color(0xFF07143A)
    val Muted = Color(0xFF6F7897)

    fun backgroundBrush() = Brush.linearGradient(
        listOf(
            Color(0xFFF7FAFF),
            Color(0xFFEFF5FF),
            Color(0xFFF4F0FF),
            Color(0xFFE7F7FF)
        ),
        start = Offset.Zero,
        end = Offset(1000f, 1000f)
    )

    fun userBubbleBrush() = Brush.linearGradient(
        listOf(
            Color(0xFF3FE1FF),
            Color(0xFF3F86FF),
            Color(0xFF6E49FF)
        ),
        start = Offset(0f, 0f),
        end = Offset(1000f, 1000f)
    )

    fun sendBrush() = Brush.linearGradient(
        listOf(
            Color(0xFF46E1FF),
            Color(0xFF5B7DFF),
            Color(0xFF8D39FF)
        )
    )

    fun glassBrush(alpha: Float) = Brush.linearGradient(
        listOf(
            Color.White.copy(alpha = alpha + 0.08f),
            Color.White.copy(alpha = alpha),
            Color(0xFFE8F4FF).copy(alpha = alpha * 0.80f),
            Color(0xFFE9E4FF).copy(alpha = alpha * 0.62f)
        ),
        start = Offset(0f, 0f),
        end = Offset(1000f, 1000f)
    )
}
