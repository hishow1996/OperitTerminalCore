package com.ai.assistance.operit.terminal.ui

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalOutput
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalView
import android.view.MotionEvent
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import kotlin.math.max
import kotlin.math.min
import kotlin.math.abs
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.view.SyntaxColors
import com.ai.assistance.operit.terminal.view.SyntaxHighlightingVisualTransformation
import com.ai.assistance.operit.terminal.view.highlight
import androidx.compose.material.icons.filled.Settings
import com.ai.assistance.operit.terminal.view.canvas.CanvasTerminalScreen
import com.ai.assistance.operit.terminal.view.canvas.RenderConfig
import com.ai.assistance.operit.terminal.utils.TerminalFontConfigManager
import android.graphics.Typeface
import android.view.inputmethod.InputMethodManager
import java.io.File

@RequiresApi(Build.VERSION_CODES.O)
@Composable
fun TerminalHome(
    env: TerminalEnv,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    val fontConfigManager = remember { TerminalFontConfigManager.getInstance(context) }
    
    // 字体配置状态
    var fontConfig by remember { 
        mutableStateOf(fontConfigManager.loadRenderConfig())
    }
    
    // 监听字体配置变化（当从设置界面返回时）
    LaunchedEffect(Unit) {
        // 每次进入时重新读取配置
        fontConfig = fontConfigManager.loadRenderConfig()
    }
    
    // 当组件重新组合时，检查配置是否变化并更新
    DisposableEffect(Unit) {
        val newConfig = fontConfigManager.loadRenderConfig()
        
        if (fontConfig != newConfig) {
            fontConfig = newConfig
        }
        
        onDispose { }
    }
    
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 命令输入框焦点控制
    val inputFocusRequester = remember { FocusRequester() }
    var pendingShowIme by remember { mutableStateOf(false) }
    var imeShown by remember { mutableStateOf(false) }
    var terminalViewRef by remember { mutableStateOf<CanvasTerminalView?>(null) }

    LaunchedEffect(pendingShowIme) {
        if (pendingShowIme) {
            pendingShowIme = false
            delay(200)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(rootView, InputMethodManager.SHOW_FORCED)
            imeShown = true
        }
    }

    // 语法高亮
    val visualTransformation = remember { SyntaxHighlightingVisualTransformation() }

    // 缩放状态
    var scaleFactor by remember { mutableStateOf(1f) }

    // 删除确认弹窗状态
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<String?>(null) }
    
    // 非全屏模式下虚拟键盘显示状态
    var showVirtualKeyboard by remember { mutableStateOf(false) }
    var isDirectInputMode by remember { mutableStateOf(false) }

    // 计算基于缩放因子的字体大小和间距
    val baseFontSize = 14.sp
    val fontSize = with(LocalDensity.current) {
        (baseFontSize.toPx() * scaleFactor).toSp()
    }
    val baseLineHeight = 1.2f
    val lineHeight = baseLineHeight * scaleFactor
    val basePadding = 8.dp
    val padding = basePadding * scaleFactor

    // 获取当前 session 的 PTY
    val currentPty = remember(env.currentSessionId, env.sessions) {
        env.sessions.find { it.id == env.currentSessionId }?.pty
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // 会话标签页
        SessionTabBar(
            sessions = env.sessions,
            currentSessionId = env.currentSessionId,
            onSessionClick = env::onSwitchSession,
            onNewSession = env::onNewSession,
            onCloseSession = { sessionId ->
                sessionToDelete = sessionId
                showDeleteConfirmDialog = true
            }
        )

        if (env.isFullscreen) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .imePadding() // 让整个列随软键盘上移
            ) {
                // 终端输出区域
                CanvasTerminalScreen(
                    emulator = env.terminalEmulator,
                    modifier = Modifier.weight(1f),
                    config = fontConfig,
                    pty = currentPty,
                    onInput = { env.onSendInput(it, false) },
                    sessionId = env.currentSessionId,
                    onScrollOffsetChanged = { id, offset -> env.saveScrollOffset(id, offset) },
                    getScrollOffset = { id -> env.getScrollOffset(id) }
                )
                
                // 虚拟键盘 - 会随 imePadding 一起上移
                VirtualKeyboard(
                    onKeyPress = { key -> env.onSendInput(key, false) },
                    fontSize = fontSize * 0.85f,
                    padding = padding * 0.7f
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .imePadding()
            ) {
                AndroidView(
                    factory = { context ->
                        CanvasTerminalView(context).apply {
                            setConfig(fontConfig)
                            setEmulator(env.terminalEmulator)
                            setPty(currentPty)
                            setSessionScrollCallbacks(env.currentSessionId, { id, offset -> env.saveScrollOffset(id, offset) }, { id -> env.getScrollOffset(id) })
                            setFullscreenMode(false)
                            setOnRequestShowKeyboard {
                                imeShown = true
                                inputFocusRequester.requestFocus()
                                pendingShowIme = true
                            }
                            setOnTouchListener { v, event ->
                                when (event.action) {
                                    MotionEvent.ACTION_DOWN -> v.parent?.requestDisallowInterceptTouchEvent(true)
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                false
                            }
                            terminalViewRef = this
                        }
                    },
                    update = { view ->
                        terminalViewRef = view
                        view.setConfig(fontConfig)
                        view.setEmulator(env.terminalEmulator)
                        view.setPty(currentPty)
                        view.setSessionScrollCallbacks(env.currentSessionId, { id, offset -> env.saveScrollOffset(id, offset) }, { id -> env.getScrollOffset(id) })
                        if (isDirectInputMode) {
                            view.setFullscreenMode(true)
                            view.setInputCallback { env.onSendInput(it, false) }
                            view.setOnRequestShowKeyboard(null)
                        } else {
                            view.setFullscreenMode(false)
                            view.setInputCallback { }
                            view.setOnRequestShowKeyboard {
                                imeShown = true
                                inputFocusRequester.requestFocus()
                                pendingShowIme = true
                            }
                        }
                    },
                    onRelease = { view ->
                        view.stopRenderThread()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Black)
                )

                if (isDirectInputMode) {
                    DirectInputCompactBar(
                        onKeyPress = { key -> env.onSendInput(key, false) },
                        onInterrupt = env::onInterrupt,
                        onExitDirectMode = {
                            isDirectInputMode = false
                            showVirtualKeyboard = false
                            imeShown = false
                        },
                        onToggleIme = {
                            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                            if (imeShown) {
                                imeShown = false
                                keyboardController?.hide()
                                imm?.hideSoftInputFromWindow(rootView.windowToken, 0)
                            } else {
                                imeShown = true
                                val tv = terminalViewRef
                                if (tv != null) {
                                    tv.requestFocus()
                                    tv.postDelayed({
                                        imm?.showSoftInput(tv, InputMethodManager.SHOW_FORCED)
                                    }, 200)
                                }
                            }
                        },
                        fontSize = fontSize * 0.85f,
                        padding = padding * 0.7f
                    )
                } else {
                    // 终端工具栏
                    TerminalToolbar(
                        onInterrupt = env::onInterrupt,
                        onEnter = { env.onSendInput("\r", false) },
                        onSendCommand = { env.onSendInput(it, true) },
                        fontSize = fontSize * 0.8f,
                        padding = padding,
                        onNavigateToSetup = onNavigateToSetup,
                        onNavigateToSettings = onNavigateToSettings
                    )

                    // 当前输入行
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.Black)
                            .padding(padding),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.padding(end = padding * 0.5f),
                            color = Color(0xFF006400),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = getTruncatedPrompt(env.currentDirectory.ifEmpty { "$ " }),
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize,
                                modifier = Modifier.padding(horizontal = padding * 0.5f, vertical = padding * 0.1f)
                            )
                        }
                        BasicTextField(
                            value = env.command,
                            onValueChange = env::onCommandChange,
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(inputFocusRequester),
                            enabled = !isDirectInputMode,
                            textStyle = TextStyle(
                                color = SyntaxColors.commandDefault,
                                fontFamily = FontFamily.Monospace,
                                fontSize = fontSize
                            ),
                            cursorBrush = SolidColor(Color.Green),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                env.onSendInput(env.command, true)
                            })
                        )
                        // 系统软键盘切换按钮
                        Surface(
                            modifier = Modifier
                                .padding(start = padding * 0.5f)
                                .clickable {
                                    if (imeShown) {
                                        imeShown = false
                                        keyboardController?.hide()
                                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                                        imm?.hideSoftInputFromWindow(rootView.windowToken, 0)
                                    } else {
                                        imeShown = true
                                        inputFocusRequester.requestFocus()
                                        pendingShowIme = true
                                    }
                                },
                            color = Color(0xFF3A3A3A),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "⌨",
                                color = Color.White,
                                fontFamily = FontFamily.Default,
                                fontSize = fontSize * 1.2f,
                                modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                            )
                        }
                        Surface(
                            modifier = Modifier
                                .padding(start = padding * 0.5f)
                                .clickable {
                                    isDirectInputMode = true
                                    env.onCommandChange("")
                                },
                            color = Color(0xFF3A3A3A),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "⇄",
                                color = Color.White,
                                fontFamily = FontFamily.Default,
                                fontSize = fontSize * 1.1f,
                                modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                            )
                        }
                    }

                    // 虚拟键盘（当显示时）
                    if (showVirtualKeyboard) {
                        VirtualKeyboard(
                            onKeyPress = { key -> env.onSendInput(key, false) },
                            fontSize = fontSize * 0.85f,
                            padding = padding * 0.7f
                        )
                    }
                }
            }
        }
    }

    // 删除确认弹窗
    if (showDeleteConfirmDialog && sessionToDelete != null) {
        val context = LocalContext.current
        val sessionTitle = env.sessions.find { it.id == sessionToDelete }?.title ?: context.getString(com.ai.assistance.operit.terminal.R.string.unknown_session)

        AlertDialog(
            onDismissRequest = {
                showDeleteConfirmDialog = false
                sessionToDelete = null
            },
            title = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.confirm_delete_session),
                    color = Color.White
                )
            },
            text = {
                Text(
                    text = context.getString(com.ai.assistance.operit.terminal.R.string.delete_session_message, sessionTitle),
                    color = Color.Gray
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sessionToDelete?.let { sessionId ->
                            env.onCloseSession(sessionId)
                        }
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.delete),
                        color = Color.Red
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        sessionToDelete = null
                    }
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.cancel),
                        color = Color.White
                    )
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }
}

private fun getTruncatedPrompt(prompt: String, maxLength: Int = 16): String {
    val trimmed = prompt.trimEnd()
    return if (trimmed.length > maxLength) {
        "..." + trimmed.takeLast(maxLength - 3)
    } else {
        trimmed
    }
}

@Composable
private fun SessionTabBar(
    sessions: List<TerminalSessionData>,
    currentSessionId: String?,
    onSessionClick: (String) -> Unit,
    onNewSession: () -> Unit,
    onCloseSession: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF2D2D2D)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 会话标签页列表
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(sessions) { session ->
                    SessionTab(
                        session = session,
                        isActive = session.id == currentSessionId,
                        onClick = { onSessionClick(session.id) },
                        onClose = if (sessions.size > 1) {
                            { onCloseSession(session.id) }
                        } else null
                    )
                }
            }

            // 新建会话按钮
            IconButton(
                onClick = onNewSession,
                modifier = Modifier.size(32.dp)
            ) {
                val context = LocalContext.current
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.new_session),
                    tint = Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionTab(
    session: TerminalSessionData,
    isActive: Boolean,
    onClick: () -> Unit,
    onClose: (() -> Unit)?
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() },
        color = if (isActive) Color(0xFF4A4A4A) else Color(0xFF3A3A3A),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = session.title,
                color = if (isActive) Color.White else Color.Gray,
                fontSize = 12.sp,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // 关闭按钮（只有多个会话时才显示）
            onClose?.let { closeAction ->
                val context = LocalContext.current
                IconButton(
                    onClick = closeAction,
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.close_session),
                        tint = Color.Gray,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalToolbar(
    onInterrupt: () -> Unit,
    onEnter: () -> Unit,
    onSendCommand: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onNavigateToSetup: () -> Unit,
    onNavigateToSettings: () -> Unit
) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A),
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f),
            horizontalArrangement = Arrangement.spacedBy(padding * 0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Ctrl+C 中断按钮
            Surface(
                modifier = Modifier.clickable { onInterrupt() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
                ) {
                    Text(
                        text = "Ctrl+C",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.interrupt),
                        color = Color.Gray,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize * 0.9f
                    )
                }
            }

            // Enter 按钮
            Surface(
                modifier = Modifier.clickable { onEnter() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "Enter",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f)
                )
            }

            // 分隔线
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(padding * 1.5f)
                    .background(Color(0xFF3A3A3A))
            )

            Spacer(Modifier.weight(1f))

            // 环境配置按钮
            Surface(
                modifier = Modifier.clickable { onNavigateToSetup() },
                color = Color(0xFF4A4A4A),
                shape = RoundedCornerShape(6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = padding * 0.75f, vertical = padding * 0.4f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(com.ai.assistance.operit.terminal.R.string.environment_setup),
                        color = Color.White,
                        fontFamily = FontFamily.Default,
                        fontSize = fontSize,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // 设置按钮
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = context.getString(com.ai.assistance.operit.terminal.R.string.settings),
                tint = Color.Gray,
                modifier = Modifier
                    .clickable { onNavigateToSettings() }
                    .padding(start = padding)
                    .size(padding * 2.5f)
            )
        }
    }
}

@Composable
private fun VirtualKeyboard(
    onKeyPress: (String) -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    val handleKeyPress: (String) -> Unit = { key ->
        if (ctrlActive) {
            if (key.length == 1 && key[0] in 'a'..'z') {
                onKeyPress((key[0].code - 96).toChar().toString())
            } else if (key.length == 1 && key[0] in 'A'..'Z') {
                onKeyPress((key[0].code - 64).toChar().toString())
            } else {
                onKeyPress(key)
            }
            ctrlActive = false
        } else if (altActive) {
            onKeyPress("\u001b$key")
            altActive = false
        } else {
            onKeyPress(key)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding, vertical = padding * 0.5f)
                .padding(bottom = padding * 0.5f),
            verticalArrangement = Arrangement.spacedBy(padding * 0.5f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
            ) {
                KeyButton("ESC", "\u001b", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("/", "/", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("—", "-", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("HOME", "\u001b[H", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("↑", "\u001b[A", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("END", "\u001b[F", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("PGUP", "\u001b[5~", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.5f)
            ) {
                KeyButton("Tab", "\t", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                ModifierKeyButton("CTRL", fontSize, padding, ctrlActive, { ctrlActive = !ctrlActive }, modifier = Modifier.weight(1f))
                ModifierKeyButton("ALT", fontSize, padding, altActive, { altActive = !altActive }, modifier = Modifier.weight(1f))
                KeyButton("←", "\u001b[D", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("↓", "\u001b[B", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("→", "\u001b[C", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("PGDN", "\u001b[6~", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModifierKeyButton(
    label: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable { onClick() },
        color = if (active) Color(0xFF2A5A2A) else Color(0xFF3A3A3A),
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = padding * 0.5f, vertical = padding * 1.2f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = if (active) Color(0xFF00FF00) else Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun KeyButton(
    label: String,
    key: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp,
    onKeyPress: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .defaultMinSize(minHeight = 44.dp)
            .clickable { onKeyPress(key) },
        color = Color(0xFF3A3A3A),
        shape = RoundedCornerShape(4.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = padding * 0.5f, vertical = padding * 1.2f),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DirectInputCompactBar(
    onKeyPress: (String) -> Unit,
    onInterrupt: () -> Unit,
    onExitDirectMode: () -> Unit,
    onToggleIme: () -> Unit,
    fontSize: androidx.compose.ui.unit.TextUnit,
    padding: androidx.compose.ui.unit.Dp
) {
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    val handleKeyPress: (String) -> Unit = { key ->
        if (ctrlActive) {
            if (key.length == 1 && key[0] in 'a'..'z') {
                onKeyPress((key[0].code - 96).toChar().toString())
            } else if (key.length == 1 && key[0] in 'A'..'Z') {
                onKeyPress((key[0].code - 64).toChar().toString())
            } else {
                onKeyPress(key)
            }
            ctrlActive = false
        } else if (altActive) {
            onKeyPress("\u001b$key")
            altActive = false
        } else {
            onKeyPress(key)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1A1A1A)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = padding * 0.5f, vertical = padding * 0.3f),
            verticalArrangement = Arrangement.spacedBy(padding * 0.3f)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
            ) {
                KeyButton("ESC", "\u001b", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("/", "/", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("HOME", "\u001b[H", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("Tab", "\t", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("↑", "\u001b[A", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("Enter", "\r", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("PGUP", "\u001b[5~", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable { onToggleIme() },
                    color = Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = padding * 0.5f, vertical = padding * 1.2f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⌨",
                            color = Color.White,
                            fontFamily = FontFamily.Default,
                            fontSize = fontSize * 1.2f,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(padding * 0.3f)
            ) {
                ModifierKeyButton("CTRL", fontSize, padding, ctrlActive, { ctrlActive = !ctrlActive }, modifier = Modifier.weight(1f))
                ModifierKeyButton("ALT", fontSize, padding, altActive, { altActive = !altActive }, modifier = Modifier.weight(1f))
                KeyButton("END", "\u001b[F", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("←", "\u001b[D", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("↓", "\u001b[B", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("→", "\u001b[C", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                KeyButton("PGDN", "\u001b[6~", fontSize, padding, handleKeyPress, modifier = Modifier.weight(1f))
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 44.dp)
                        .clickable { onExitDirectMode() },
                    color = Color(0xFF3A3A3A),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = padding * 0.5f, vertical = padding * 1.2f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "⇄",
                            color = Color.White,
                            fontFamily = FontFamily.Default,
                            fontSize = fontSize * 1.1f,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
} 