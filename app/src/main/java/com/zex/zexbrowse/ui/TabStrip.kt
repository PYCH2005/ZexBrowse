/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.zex.zexbrowse.ui

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zex.zexbrowse.R
import com.zex.zexbrowse.browser.BrowserTab
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.exp

/** 连点判定窗口：两次新建间隔小于它就不播宽度展开，直接全宽出场 */
private const val RAPID_CREATE_MS = 220L

/** 新建标签 0 → 自然宽的展开时长 */
private const val EXPAND_MS = 440

/** 关闭标签宽度收缩时长；收缩完才真正从数据里移除。与展开动画反向对应，但要够快 */
private const val CLOSE_SHRINK_MS = 120

/** 展开动画时间曲线的反向：收缩时先慢后快，正好是展开的倒放 */
private val ReverseFastOutSlowInEasing = object : androidx.compose.animation.core.Easing {
    override fun transform(fraction: Float): Float =
        1f - FastOutSlowInEasing.transform(1f - fraction)
}

/** 居中帧循环的指数收敛时间常数（毫秒）。按 dt 归一化，120Hz / 60Hz 表现一致 */
private const val CENTER_TAU_MS = 50.0

/**
 * 单次删除后的回中时间常数：约 140ms 滑到正中。
 * 比普通切换的 50ms 快，但保留肉眼可见的移动过程；再快就会变成瞬移。
 */
private const val CENTER_TAU_FAST_MS = 32.0

/**
 * 两次关闭小于这个间隔，视为连续删除：直接移除并一步回中，保证下一次能立刻点到叉。
 * 大于这个间隔的删除仍会播放上面约 120ms 的滑动。
 */
private const val RAPID_CLOSE_MS = 400L

/**
 * 标签条滚动与动画的纯逻辑持有者。
 *
 * 这里刻意不使用任何 Compose State：帧循环每帧都会读写这些字段，
 * 一旦走 snapshot 就会让整棵 UI 树跟着重组（旧实现掉帧的主因）。
 * 所有方法都只在主线程调用。
 */
@androidx.compose.runtime.Stable
internal class TabStripController(
    private val now: () -> Long = { SystemClock.uptimeMillis() }
) {
    enum class Appearance { OLD, EXPAND, RAPID }

    /** 已经组合过的标签 id，用于判断「是不是刚诞生的」 */
    private val seenIds = HashSet<String>()
    var seeded = false
        private set

    private var lastCreateMs = 0L

    /** 稳定后的自然宽；只用于目标不可见时的落位估算，非 State */
    private val widths = HashMap<String, Int>()

    /** 用户拥有滚动权：手指拖动中，或松手后的惯性还没停 */
    var userOwnsScroll = false
    var quietFrames = 0

    /** 关闭动画开始时解除跟随：让 chip 原地收缩，别把整排跟着它拖 */
    var cancelFollow = false

    /** 关闭标签后的一次快速回中；滚到正中后自动清除，不影响普通切换的 300ms 手感 */
    var fastCenter = false

    /** 连续删除时直接一步回中（不再滑），让下一次点叉不用等 */
    var snapCenter = false
    /** 关闭开始时先预居中的目标（被删标签的邻居），收缩过程中就把它送到正中 */
    var preCenterId: String? = null
    private var lastCloseMs = 0L

    /**
     * 关闭按钮按下时调用。
     * 返回 true 表示这次属于连续删除：跳过收缩、直接移除并一步回中。
     */
    fun beginClose(): Boolean {
        val t = now()
        val rapid = t - lastCloseMs < RAPID_CLOSE_MS
        lastCloseMs = t
        cancelFollow = true
        fastCenter = true
        // 单次删除播放约 120ms 的滑动；连续删除才一步到位，避免下一次点叉落空
        snapCenter = rapid
        return rapid
    }

    fun seed(tabs: List<BrowserTab>) {
        if (seeded) return
        tabs.forEach { seenIds += it.id }
        seeded = true
    }

    /** 必须在组合期同步调用：同一批 chip 在同一帧共享 lastCreateMs */
    fun classify(id: String): Appearance {
        if (!seenIds.add(id)) return Appearance.OLD
        // 进程生命周期里的第一个标签（冷启动首页标签）直接按老标签处理：
        // 软件刚打开时不播 0→自然宽的创建动画，标签应该已经在最终位置。
        if (seenIds.size == 1) return Appearance.OLD
        val t = now()
        val rapid = t - lastCreateMs < RAPID_CREATE_MS
        lastCreateMs = t
        return if (rapid) Appearance.RAPID else Appearance.EXPAND
    }

    /** 只写稳定后的自然宽；展开中间值由调用方挡住，不会到这里 */
    fun commitWidth(id: String, width: Int) {
        if (width <= 0 || widths[id] == width) return
        widths[id] = width
    }

    fun widthOf(id: String): Int? = widths[id]

    fun averageWidth(fallbackPx: Int): Int =
        if (widths.isEmpty()) fallbackPx else widths.values.average().toInt()
}

/**
 * 顶部标签条。
 *
 * 对外仍然是「标签列表 + 选中 id + 选中/关闭回调」，不依赖 BrowserViewModel。
 * 居中只由「选中项变化」和「标签增删」触发；手动滑动（含松手后的惯性）完全让位，
 * 停在哪就是哪，只有下一次显式选中/新建/关闭才重新回中。
 */
@Composable
internal fun TabStrip(
    tabs: List<BrowserTab>,
    selectedId: String?,
    incognitoActive: Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit
) {
    val controller = remember { TabStripController() }
    // 首次组合把已有标签全部登记为老标签，冷启动不会把首页标签播一遍展开
    controller.seed(tabs)
    // 回调包一层稳定 lambda：chip 的参数全部稳定后，选中一个标签时只会重组新旧两个 chip，
    // 其余可见 chip 直接跳过——连点时这是主线程最大的一笔节省。
    val latestSelect = rememberUpdatedState(onSelect)
    val latestClose = rememberUpdatedState(onClose)
    val latestTabs = rememberUpdatedState(tabs)
    val selectStable: (String) -> Unit = remember { { id -> latestSelect.value(id) } }
    val closeStable: (String) -> Unit = remember { { id -> latestClose.value(id) } }
    // 关闭开始时算出「被删标签的邻居」，让收缩过程中就把它预居中。
    // 包成稳定 lambda，否则每个 chip 的参数每帧都变，连点时的跳过优化会失效。
    val closeStartStable: (String) -> Unit = remember {
        { closingId ->
            val list = latestTabs.value
            val idx = list.indexOfFirst { it.id == closingId }
            controller.preCenterId = when {
                idx > 0 -> list[idx - 1].id
                idx >= 0 -> list.getOrNull(idx + 1)?.id
                else -> null
            }
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(if (incognitoActive) IncognitoTopBarColor else Color.Transparent)
            .padding(horizontal = 8.dp)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // 固定半屏内容内边距：任何一项（含首尾）都能滚到正中，
            // 且不会像旧实现那样因为「选中宽变化」改 padding、再反过来触发居中。
            val halfViewport = maxWidth / 2
            val density = LocalDensity.current
            // 冷启动的初始标签是空白页，chip 宽度约 108dp。
            // 预置一半宽度的滚动偏移，让第一帧就已经在正中，
            // 避免「先偏右半格、下一帧再被帧循环拉回中间」的闪动。
            val initialHalfTabPx = with(density) { 54.dp.roundToPx() }
            val listState = rememberLazyListState(
                initialFirstVisibleItemScrollOffset = initialHalfTabPx
            )
            val draggedState = listState.interactionSource.collectIsDraggedAsState()
            val selectedNow = rememberUpdatedState(selectedId)
            val tabsNow = rememberUpdatedState(tabs)

            LaunchedEffect(Unit) {
                var lastSid: String? = null
                var lastCount = -1
                var target: String? = null
                var lastFrame = 0L
                var missingFrames = 0
                var stuckFrames = 0
                var carry = 0f
                var snapNext = true

                while (true) {
                    val frame = withFrameNanos { it }
                    val dtMs = if (lastFrame == 0L) 8f
                    else ((frame - lastFrame) / 1_000_000f).coerceIn(0f, 100f)
                    lastFrame = frame

                    val sid = selectedNow.value
                    val list = tabsNow.value
                    val dragged = draggedState.value

                    // 关闭动画开始：不再跟随正在收缩的旧标签，改为预先跟随它的邻居。
                    // 这样收缩的 180ms 里邻居就已经滑到正中，移除后新叉子立刻在中间。
                    // 必须放在「选中/增删」触发之前：连续删除时关闭和移除可能落在同一帧，
                    // 放到后面会把刚登记的新目标误清掉。
                    if (controller.cancelFollow) {
                        controller.cancelFollow = false
                        target = controller.preCenterId
                        controller.preCenterId = null
                    }

                    // 触发只有两个来源：选中项变化、标签数量变化（增删）。
                    // 重组、布局、padding 变化都不会改这两个值，因此不会触发居中。
                    if (sid != lastSid || list.size != lastCount) {
                        lastSid = sid
                        lastCount = list.size
                        if (dragged) {
                            // 手指还按着：这次变化不登记，松手也不补
                            target = null
                            controller.userOwnsScroll = true
                        } else {
                            // 显式事件优先：即使惯性还在跑，也把滚动权收回来
                            controller.userOwnsScroll = false
                            controller.quietFrames = 0
                            target = sid
                            missingFrames = 0
                            stuckFrames = 0
                            carry = 0f
                        }
                    }

                    // 拖动期间完全让位：一个滚动 API 都不调
                    if (dragged) {
                        controller.userOwnsScroll = true
                        controller.quietFrames = 0
                        controller.fastCenter = false
                        target = null
                        continue
                    }

                    // 松手后的惯性：isDragged 已经 false，但 isScrollInProgress 还是 true。
                    // 用户拥有滚动权时程序绝不滚，所以此时的 isScrollInProgress 只可能来自用户；
                    // 连续两帧 false 才把滚动权还回去。程序滚动不会把自己锁在这里。
                    if (controller.userOwnsScroll) {
                        if (listState.isScrollInProgress) {
                            controller.quietFrames = 0
                        } else if (++controller.quietFrames >= 2) {
                            controller.userOwnsScroll = false
                        }
                        if (controller.userOwnsScroll) continue
                    }

                    val id = target ?: continue
                    val idx = list.indexOfFirst { it.id == id }
                    if (idx < 0) {
                        // 选中项刚变、列表还没重组到它：等几帧，别立刻放弃
                        if (++missingFrames > 120) {
                            target = null
                            missingFrames = 0
                        }
                        continue
                    }
                    missingFrames = 0

                    val layout = listState.layoutInfo
                    val viewport = layout.viewportEndOffset - layout.viewportStartOffset
                    if (viewport <= 0 || layout.totalItemsCount <= idx) continue

                    val info = layout.visibleItemsInfo.firstOrNull { it.index == idx }
                    if (info == null) {
                        // 目标在屏幕外：一步落位，下一帧再精修
                        val estimate = controller.widthOf(id)
                            ?: controller.averageWidth(with(density) { 140.dp.roundToPx() })
                        listState.scrollToItem(idx, (estimate / 2).coerceAtLeast(0))
                        snapNext = false
                        carry = 0f
                        continue
                    }

                    // LazyListItemInfo.size 是主轴尺寸，横向列表里就是 chip 宽度。
                    // offset 是内容坐标（不含 contentPadding），要先加 beforeContentPadding 才是屏幕坐标。
                    val desired = (viewport - info.size) / 2
                    val screenOffset = info.offset + layout.beforeContentPadding
                    val delta = (screenOffset - desired).toFloat()

                    // 连续删除：不等滑动，一帧把相邻标签送到正中，手指可以立刻再点叉
                    if (controller.snapCenter) {
                        controller.snapCenter = false
                        controller.fastCenter = false
                        if (delta != 0f) listState.scrollBy(delta)
                        continue
                    }

                    // 首次进入（冷启动 / 全屏退出回来）直接落位，不播滑动
                    if (snapNext) {
                        snapNext = false
                        if (delta != 0f) listState.scrollBy(delta)
                        carry = 0f
                        continue
                    }

                    // 选中项是唯一跟随目标：目标只由「选中变化 / 标签增删」登记，
                    // 只有手动拖动或关闭动画才解除。重组、padding、标题变宽都不会重新登记，
                    // 只是同一个目标继续生效，所以选中项能一直保持在正中。
                    if (abs(delta) <= 0.5f) {
                        controller.fastCenter = false
                        continue
                    }

                    // 按时间归一化的指数逼近：不重启、不排队，位置和速度都连续。
                    // 普通切换用 50ms 时间常数（约 300~400ms 收敛）；
                    // 单次删除后用 32ms，约 140ms 滑到正中，保留可见的移动过程。
                    // 连续删除在上面的 snapCenter 分支一步到位。
                    val tau = if (controller.fastCenter) CENTER_TAU_FAST_MS else CENTER_TAU_MS
                    val step = delta *
                        (1f - exp(-dtMs.toDouble() / tau).toFloat()) + carry
                    val consumed = listState.scrollBy(step)
                    carry = step - consumed
                    if (abs(consumed) < 0.01f) {
                        // 滚不动了（顶到边界或还没攒够 1px）：空转一阵就放弃，避免死追
                        if (++stuckFrames > 30) {
                            target = null
                            stuckFrames = 0
                            carry = 0f
                        }
                    } else {
                        stuckFrames = 0
                    }
                }
            }

            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = halfViewport)
            ) {
                items(tabs, key = { it.id }) { tab ->
                    TabChip(
                        tabId = tab.id,
                        title = tab.title,
                        selected = tab.id == selectedId,
                        incognitoActive = incognitoActive,
                        controller = controller,
                        onSelect = selectStable,
                        onClose = closeStable,
                        onCloseStart = closeStartStable
                    )
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.TabChip(
    tabId: String,
    title: String,
    selected: Boolean,
    incognitoActive: Boolean,
    controller: TabStripController,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onCloseStart: (String) -> Unit
) {
    // 首次出现必须在组合期同步判定：同一批 chip 在同一帧读到同一个 lastCreateMs
    val appearance = remember(tabId) { controller.classify(tabId) }
    val isExpand = appearance == TabStripController.Appearance.EXPAND
    var grown by remember(tabId) { mutableStateOf(!isExpand) }
    var expanding by remember(tabId) { mutableStateOf(isExpand) }
    var closing by remember(tabId) { mutableStateOf(false) }
    val widthHolder = remember(tabId) { WidthHolder() }
    // 连续删除时跳过收缩，直接提交关闭。用普通数组而不是 State：
    // 点击里写入、LaunchedEffect 里读取，不需要触发重组。
    val closeDelayMs = remember(tabId) { longArrayOf(CLOSE_SHRINK_MS.toLong()) }

    LaunchedEffect(tabId) {
        if (isExpand) {
            // 先让「0 宽」真正测量一帧，下一帧恢复自然宽，animateContentSize 才有 0 → 自然宽可播
            withFrameNanos { }
            grown = true
            delay(EXPAND_MS + 80L)
            expanding = false
            controller.commitWidth(tabId, widthHolder.value)
        } else {
            withFrameNanos { }
            controller.commitWidth(tabId, widthHolder.value)
        }
    }

    LaunchedEffect(closing) {
        if (closing) {
            if (closeDelayMs[0] > 0L) delay(closeDelayMs[0])
            onClose(tabId)
        }
    }

    // 关闭时同时把整块 chip 的透明度降到 0：宽度收窄 + 淡出同步 120ms，
    // 让「收掉」的过程更明显，也避免收完瞬间直接消失。
    val closeAlpha by animateFloatAsState(
        targetValue = if (closing) 0f else 1f,
        animationSpec = tween(CLOSE_SHRINK_MS, easing = FastOutSlowInEasing),
        label = "tabCloseAlpha"
    )

    // 颜色直接切换，不做 250ms 补间：连点时两个 chip 的补间会让它们每帧重组，
    // 视觉上切换本来也是一瞬间的事，省下来的正是主线程预算。
    val containerColor = when {
        selected && incognitoActive -> IncognitoAccentColor
        incognitoActive -> Color.Transparent
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surface
    }
    val labelColor = when {
        selected -> if (incognitoActive) Color.White else MaterialTheme.colorScheme.onPrimary
        incognitoActive -> IncognitoBarBackground
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    AssistChip(
        onClick = { if (!closing) onSelect(tabId) },
        modifier = Modifier
            // 关闭时整块淡出；放在最外层，连背景带文字一起降透明度
            .graphicsLayer { alpha = closeAlpha }
            // 外框按动画尺寸裁剪，chip 背景才跟着「长出来 / 收回去」
            .clipToBounds()
            // placement 不做补间：删标签后相邻 chip 必须立刻在最终位置，
            // 否则手指下一次点叉时 chip 还在弹簧/补间里移动，叉子对不上。
            // 新建时相邻项被「挤开」的过程仍由宽度展开本身逐帧带动。
            .animateItem(
                fadeInSpec = null,
                placementSpec = snap(),
                fadeOutSpec = tween<Float>(160)
            )
            .animateContentSize(
                animationSpec = when {
                    closing -> tween<IntSize>(CLOSE_SHRINK_MS, easing = ReverseFastOutSlowInEasing)
                    expanding -> tween<IntSize>(EXPAND_MS, easing = FastOutSlowInEasing)
                    else -> snap()
                },
                alignment = Alignment.TopStart
            )
            // 新建首帧 0 宽；关闭时也收 0。其余时候不写死宽度
            .then(if (!grown || closing) Modifier.width(0.dp) else Modifier)
            .onSizeChanged { size ->
                widthHolder.value = size.width
                // 展开中间值绝不入缓存；只在稳定后写，且写前比较旧值
                if (!expanding && !closing && grown) controller.commitWidth(tabId, size.width)
            },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = labelColor,
            trailingIconContentColor = labelColor
        ),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.ifBlank { stringResource(R.string.s107) }.truncateMiddle(10, 8),
                    maxLines = 1
                )
                Spacer(Modifier.width(4.dp))
                // 固定 16dp 尾槽：选中/未选中宽度一致，切换标签不会触发宽度动画
                Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                    if (selected) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.s119),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable {
                                    if (!closing) {
                                        // 先登记「收缩期间要预居中的邻居」，再开始关闭动画
                                        onCloseStart(tabId)
                                        closeDelayMs[0] = if (controller.beginClose()) 0L else CLOSE_SHRINK_MS.toLong()
                                        closing = true
                                    }
                                }
                        )
                    }
                }
            }
        }
    )
}

/** onSizeChanged 只记录自然宽，不参与组合，避免每帧重组 */
private class WidthHolder {
    var value: Int = 0
}
