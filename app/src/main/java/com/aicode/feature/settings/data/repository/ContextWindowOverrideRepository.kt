package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.contextWindowOverrideDataStore by preferencesDataStore(
    name = "context_window_override_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 全局「上下文窗口统一值」的持久化。
 *
 * 开启后，忽略模型自身（models.dev / 内置 / 自定义）的上下文窗口，全部模型统一使用这里配置的
 * 数值来决定「可用输入预算」与「输出预留」，进而决定自动压缩的触发时机；关闭则回到各模型自己的值。
 *
 * 必填约定：总上下文窗口与输出窗口为必填（业务侧保证）——缺少任一项都视为配置不完整，
 * 上层会据此自动关闭开关，避免留下「开关开着但没有生效数值」的歧义状态。输入窗口可选，
 * 留空表示输入上限 = 总窗口 - 输出预留（由 [com.aicode.feature.settings.domain.model.ModelContextPolicy] 计算）。
 */
@Singleton
class ContextWindowOverrideRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("override_enabled")
        val CONTEXT_TOKENS = intPreferencesKey("context_tokens")
        val INPUT_TOKENS = intPreferencesKey("input_tokens")
        val OUTPUT_TOKENS = intPreferencesKey("output_tokens")
    }

    /**
     * 当前快照流。为 UI 与策略层统一出口：
     * 开关关闭时 [ContextWindowOverride.enabled] 为 false（数值仍保留着，便于再次开启时回填）。
     */
    val overrideFlow: Flow<ContextWindowOverride> =
        context.contextWindowOverrideDataStore.data.map { prefs -> prefs.toOverride() }

    /** 一次性读取当前快照（策略层冷读用）。 */
    suspend fun getOverride(): ContextWindowOverride =
        context.contextWindowOverrideDataStore.data.first().toOverride()

    /**
     * 写入配置并决定是否真的置为启用。
     *
     * @return 实际落库后的启用状态：必填项（总上下文、输出）任一缺失/非正数时会被强制关闭。
     *   这样调用方（UI）可以直接拿返回值刷新开关状态，不需要自己再判定一遍。
     */
    suspend fun save(contextTokens: Int, inputTokens: Int?, outputTokens: Int?): Boolean {
        val valid = contextTokens > 0 && (outputTokens ?: 0) > 0
        context.contextWindowOverrideDataStore.edit { prefs ->
            prefs[Keys.CONTEXT_TOKENS] = contextTokens.coerceAtLeast(0)
            if (inputTokens != null && inputTokens > 0) {
                prefs[Keys.INPUT_TOKENS] = inputTokens
            } else {
                prefs.remove(Keys.INPUT_TOKENS)
            }
            if (outputTokens != null && outputTokens > 0) {
                prefs[Keys.OUTPUT_TOKENS] = outputTokens
            } else {
                prefs.remove(Keys.OUTPUT_TOKENS)
            }
            prefs[Keys.ENABLED] = valid
        }
        return valid
    }

    /** 关闭开关，回到各模型自己的上下文窗口；已填数值保留以便重新开启。 */
    suspend fun setEnabled(enabled: Boolean) {
        if (enabled) {
            // 重新开启前先自检：必填仍齐备才允许打开
            val current = getOverride()
            if (current.contextTokens <= 0 || (current.outputTokens ?: 0) <= 0) return
        }
        context.contextWindowOverrideDataStore.edit { prefs ->
            prefs[Keys.ENABLED] = enabled
        }
    }

    private fun Preferences.toOverride(): ContextWindowOverride {
        val enabled = this[Keys.ENABLED] ?: false
        val contextTokens = this[Keys.CONTEXT_TOKENS] ?: 0
        val inputTokens = this[Keys.INPUT_TOKENS]?.takeIf { it > 0 }
        val outputTokens = this[Keys.OUTPUT_TOKENS]?.takeIf { it > 0 }
        // 存储层兜底：必填不齐时即便 ENABLED 被写成 true 也不生效，避免出现「开着但没值」的状态。
        val effective = enabled && contextTokens > 0 && (outputTokens ?: 0) > 0
        return ContextWindowOverride(effective, contextTokens, inputTokens, outputTokens)
    }
}

/**
 * 上下文窗口统一值的快照。
 *
 * @param enabled 是否生效（已满足必填项才为 true）。
 * @param contextTokens 总上下文窗口。
 * @param inputTokens 输入上限，null 表示由策略层按「总窗口 - 输出预留」推导。
 * @param outputTokens 输出上限。
 */
data class ContextWindowOverride(
    val enabled: Boolean,
    val contextTokens: Int,
    val inputTokens: Int?,
    val outputTokens: Int?
)
