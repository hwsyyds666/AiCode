package com.aicode.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.toolSafetyDataStore by preferencesDataStore(name = "tool_safety_prefs")

/**
 * 命令执行授权挡位（作用于 BUILD 模式下 shell 类工具的评估；PLAN 物理拦截、AUTO 会话模式全放行均不受影响）。
 * - [AUTONOMOUS]：自主执行——由「AI 命令审查器」独立审核每条命令，审查通过直接放行；
 *   审查拒绝/不确定/失败一律转人工审批（拒绝时附审查器给出的危险说明）。
 * - [RULE_BASED]：规则拦截——命中高危拦截规则的命令弹人工确认，其余命令自动执行（默认）。
 * - [FULL_ACCESS]：完全权限——所有命令直接执行，仅保留灾难性删除防护。
 */
enum class CommandAuthLevel { AUTONOMOUS, RULE_BASED, FULL_ACCESS }

/**
 * 持久化「禁用安全拦截」开关，默认关闭。
 *
 * 开启后，AUTO（自动）模式下连灾难性 `rm`（删除根目录、系统关键目录、工作区根目录等）
 * 也不再拦截，命令一律放行，`Shizuku` 工具也不再逐次弹窗。仅作用于 AUTO 模式：
 * BUILD / PLAN 模式的安全拦截不受影响，Shizuku 在 BUILD 下的「不可记忆」也不受影响。
 */
@Singleton
class ToolSafetySettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val DISABLED_KEY = booleanPreferencesKey("disable_safety_interception")
        val AUTH_LEVEL_KEY = stringPreferencesKey("command_auth_level")
    }

    /** 当前开关流；未设置时回退到 false（默认保留安全拦截）。 */
    val disableSafetyInterceptionFlow: Flow<Boolean> =
        context.toolSafetyDataStore.data.map { it[DISABLED_KEY] ?: false }

    suspend fun setDisableSafetyInterception(disabled: Boolean) {
        context.toolSafetyDataStore.edit { it[DISABLED_KEY] = disabled }
    }

    /** 读取一次当前值（权限评估时用）。 */
    suspend fun isSafetyInterceptionDisabled(): Boolean = disableSafetyInterceptionFlow.first()

    /** 当前命令授权挡位流；未设置时回退 [CommandAuthLevel.RULE_BASED]（规则拦截）。 */
    val commandAuthLevelFlow: Flow<CommandAuthLevel> =
        context.toolSafetyDataStore.data.map { prefs ->
            prefs[AUTH_LEVEL_KEY]?.let { runCatching { CommandAuthLevel.valueOf(it) }.getOrNull() }
                ?: CommandAuthLevel.RULE_BASED
        }

    suspend fun setCommandAuthLevel(level: CommandAuthLevel) {
        context.toolSafetyDataStore.edit { it[AUTH_LEVEL_KEY] = level.name }
    }

    /** 读取一次当前挡位（权限评估时用）。 */
    suspend fun getCommandAuthLevel(): CommandAuthLevel = commandAuthLevelFlow.first()
}