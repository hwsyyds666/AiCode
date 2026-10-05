package com.aicode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.effects.PrismalAdaptiveLuminanceState
import com.styropyr0.prismal.effects.rememberPrismalAdaptiveLuminance
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer

/**
 * Liquid Glass 脚手架（基于 PrismalAGSL）。
 *
 * 三条使用纪律，改动时请保持：
 *
 * 1. **只有一个 backdrop**：一个界面里只允许创建一层 [rememberChatGlassLayer]，并用
 *    [Modifier.glassBackdrop] 挂在「玻璃背后的内容」上（聊天页是消息列表）。
 *    多个全屏 backdrop capture 会显著抬高 GPU 与内存开销——这正是需求里点名要避免的。
 *    所有玻璃元素（标题栏 / 输入区 / 待办浮层）共用这一层，而不是各自采样。
 *
 * 2. **只做局部 surface**：玻璃只出现在上述几处悬浮层上，页面主体仍是普通内容。
 *
 * 3. **不自己判断 API 级别**：PrismalAGSL 自带三档降级
 *    （API 25-30 磨砂 → 31-32 RenderEffect 模糊 → 33+ 完整 AGSL 折射），
 *    我们不在业务代码里调用 RuntimeShader，也不写 Build.VERSION 分支，
 *    否则会破坏 Android 8.0（minSdk 26）的兼容范围。
 */

/** 界面唯一的背景采样层；创建后需用 [Modifier.glassBackdrop] 挂到背景内容上。 */
@Composable
fun rememberChatGlassLayer(): PrismalGlassLayer = rememberPrismalGlassLayer()

/** 把某个子树登记为玻璃的背景源（每个界面只应挂一处）。 */
fun Modifier.glassBackdrop(layer: PrismalGlassLayer): Modifier = this.prismalGlassLayer(layer)

/**
 * 采样背景亮度，用于让玻璃与前景文字随背景明暗自适应。
 *
 * [PrismalAdaptiveLuminanceState.luminance] 交给玻璃，[PrismalAdaptiveLuminanceState.contentColor]
 * 作为玻璃上文字的前景色——这是保证「标题 / 模型名 / 按钮文字不因透明而消失」的关键。
 */
@Composable
fun rememberGlassLuminance(
    source: PrismalGlassLayer,
    enabled: Boolean = true
): PrismalAdaptiveLuminanceState = rememberPrismalAdaptiveLuminance(
    enabled = enabled,
    source = source,
    isLightTheme = MaterialTheme.colorScheme.surface.luminance() > 0.5f
)

/** 玻璃前景色：由背景亮度推导，保证在明暗背景上都读得清。 */
val PrismalAdaptiveLuminanceState.contentColorOrOnSurface: Color
    @Composable get() = contentColor

/**
 * 统一的玻璃容器。
 *
 * - [cornerRadius]：大圆角，液态玻璃的基本形态；
 * - [tintAlpha]：极低，只做很轻的 surface tint，避免变成「大面积半透明白块」；
 * - [adaptiveLuminance] + [luminance]：让模糊与色彩随背景亮度自适应，并保住对比度下限。
 */
@Composable
fun GlassSurface(
    /**
     * 背景采样层；为 null 时降级为「半透明 surface + 极轻描边」的普通容器。
     * 这样调用方不必写两套布局，玻璃开关只需传 null / 非 null。
     */
    backdrop: PrismalBackdrop?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    blurRadius: Dp = 12.dp,
    tintAlpha: Float = 0.10f,
    adaptiveLuminance: Boolean = true,
    luminance: () -> Float = { 0.5f },
    content: @Composable BoxScope.() -> Unit
) {
    if (backdrop == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(cornerRadius)
                ),
            content = content
        )
        return
    }
    PrismalGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = { PrismalRoundedRectangle(cornerRadius) },
        tint = MaterialTheme.colorScheme.surface,
        tintAlpha = tintAlpha,
        blurRadius = blurRadius,
        adaptiveLuminance = adaptiveLuminance,
        luminance = luminance
    ) {
        content()
    }
}
