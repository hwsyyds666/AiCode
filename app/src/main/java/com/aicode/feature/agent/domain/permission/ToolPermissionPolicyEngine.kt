package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具授权策略引擎：在弹窗之前评估一次工具调用应「自动放行 / 自动拒绝 / 询问」，并在用户选择
 * 「始终允许」后把规则记忆下来。
 *
 * 评估顺序（Bash，BUILD 模式）：
 *   0) 灾难性 rm 防护：删除根目录/系统关键目录/工作区根目录等 → 硬拒绝（可凭 elevate 参数降级为一次性授权）；
 *   1) Shizuku 高危工具：一律弹窗且不可记忆；
 *   2) 可静态判定且每段都命中内置只读白名单（ls/git status 等）→ 自动放行；
 *   3) 按「命令授权挡位」（[CommandAuthLevel]）分流：
 *      - 规则拦截（默认）：命中任一启用的高危拦截规则（见 [HighRiskRuleMatcher]）→ ASK 人工确认；
 *        命令不可静态判定（含命令替换/分组/绝对路径重定向等）→ ASK 兜底；其余 → ALLOW；
 *      - 自主执行：一律 ASK 并标记 [EvalResult.aiReviewable]，由工作流层先交「AI 命令审查器」评审，
 *        通过直接放行，拒绝/不确定/失败回退人工弹窗；
 *      - 完全权限：直接 ALLOW（仅保留灾难防护；未开「禁用安全拦截」时，
 *        不可静态判定且疑似破坏性的命令仍按 elevate 流程拦截）。
 *
 * 命令不再支持「始终允许」记忆：旧的项目/全局 ALLOW 规则会先于挡位生效，等于绕过高危拦截。
 * 非 shell 工具（文件读写/编辑等）按整工具（pattern=`*`）匹配，保留「始终允许此工具」记忆
 * （这类工具无高危规则可匹配，去掉记忆会导致每次写文件都弹窗）。
 *
 * [SHIZUKU_TOOL]（Shizuku）按高危处理：一律 ASK 且不可记忆；
 * AUTO 模式也不自动放行（除非已开启「禁用安全拦截」）。
 */
@Singleton
class ToolPermissionPolicyEngine @Inject constructor(
    private val rulesRepo: PermissionRulesRepository,
    private val toolSafetySettings: ToolSafetySettingsRepository,
    private val highRiskRulesRepo: HighRiskRulesRepository
) {
    private companion object {
        /** 以 `command` 参数承载 shell 命令、按命令前缀做指令级匹配的工具。 */
        val SHELL_TOOLS = setOf("Bash", SHIZUKU_TOOL)

        /** Shizuku 工具：以 adb shell 身份直接操作宿主 Android 系统，按高危处理（一律弹窗、不可记忆）。 */
        const val SHIZUKU_TOOL = "Shizuku"

        const val REASON_SHIZUKU =
            "Shizuku 直接以 adb shell 身份操作宿主 Android 系统，权限高危，仅支持单次放行，不可记忆"

        /**
         * 合并后的终端会话工具：其 `start` 动作承载 shell 命令，需走指令级前缀匹配；
         * `send`/`read` 动作不承载命令，按整工具匹配。故在 [evaluate] 中按 action 路由。
         */
        const val TERMINAL_TOOL = "terminal"
        const val TERMINAL_SHELL_ACTION = "start"
        val NON_REMEMBERABLE_CAPABILITIES = setOf(
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV
        )

        /**
         * 合并后的子代理工具：只读与发消息操作（read/list/send）自动放行，无需弹窗；
         * 写操作（create/stop）走正常规则评估。
         */
        const val TASK_TOOL = "task"
        private val TASK_AUTO_ACTIONS = setOf("read", "list", "send")

        /**
         * 浏览器工具：navigate/click/fill/select/evaluate/dialog/back/forward/reload/newTab/closeTab/selectTab 为写操作（PLAN 拦截），
         * getText/getHtml/getBackbone/screenshot/console/wait/listTabs 为只读（PLAN 放行）。
         */
        const val BROWSER_TOOL = "browser"
        private val BROWSER_READ_ONLY_ACTIONS = setOf("getText", "getHtml", "getBackbone", "screenshot", "console", "wait", "listTabs")

        /**
         * 提权参数：非 AUTO 模式下，命令因内置安全防护（灾难性 rm）被拒时，
         * 可在调用时置为 true 重试，把硬拒绝降级为一次性用户授权。
         */
        const val ELEVATE_ARG = "elevate"

        /** 容器内 `~` 展开目标（PRoot 以 root 运行）。 */
        const val HOME_DIR = "/root"
        const val HOME_TOKEN = "\$HOME"
        const val HOME_BRACED = "\${HOME}"

        const val REASON_RM_ROOT = "安全防护：禁止执行高危删除操作（根目录删除）"
        const val REASON_RM_RELATIVE = "安全防护：禁止执行高危删除操作（全局或相对路径通配删除）"
        const val REASON_RM_HOME = "安全防护：禁止执行高危删除操作（用户目录删除）"
        const val REASON_RM_WORKSPACE = "安全防护：禁止执行高危删除操作（工作区根目录删除）"
        const val REASON_RM_TMP = "安全防护：禁止执行高危删除操作（系统临时目录整体删除）"
        const val REASON_RM_SYSTEM_DIR = "安全防护：禁止执行高危删除操作（系统关键目录删除）"

        /** 相对/通配类删除目标（归一化后判定）：整体删除当前目录或任意内容。 */
        val RELATIVE_WILDCARD_TARGETS = setOf("*", ".*", ".", "..", "../*", "../.*")

        /** 根级通配（`/et*`、`/usr*` 等）可能展开为受保护的系统目录，直接拦截。 */
        val ROOT_LEVEL_GLOB = Regex("^/[^/*?]*[*?]")

        /** 工作区根目录的删除目标（归一化后判定）；工作区的子目录不在此列。 */
        val WORKSPACE_ROOT_TARGETS = setOf("workspace", "workspace/*", "$HOME_DIR/workspace", "$HOME_DIR/workspace/*")

        /** 受保护的系统关键目录：命中其本身或其任意子路径即拦截（`/tmp`、工作区子树单独处理）。 */
        val PROTECTED_SYSTEM_DIRS = setOf(
            "/bin", "/boot", "/dev", "/etc", "/home", "/lib", "/lib64", "/lost+found",
            "/media", "/mnt", "/opt", "/proc", "/root", "/run", "/sbin", "/srv", "/sys", "/usr", "/var"
        )

        const val REASON_UNANALYZABLE_DESTRUCTIVE =
            "安全防护：命令含命令替换、子 shell 或绝对路径重定向等无法静态判定的构造，且疑似破坏性操作，无法确认安全"

        /** 疑似破坏性程序（仅在命令不可静态判定时用于保守拦截）。 */
        val DESTRUCTIVE_PROGRAM = Regex(
            "(^|[\\s;&|()`<>])(rm|dd|shred|truncate|mkfs(?:\\.[a-z0-9]+)?|wipefs|fdisk|sfdisk|parted|mkswap|blkdiscard)([\\s;&|()`<>]|$)"
        )
    }

    enum class Verdict { ALLOW, DENY, ASK }

    /**
     * @param verdict 评估结论。
     * @param rememberablePatterns 当 [verdict] 为 ASK 时，「始终允许」会记忆的模式；为空表示不可记忆
     *   （命令不可静态判定，只能单次放行）。
     * @param aiReviewable true 表示该 ASK 来自「自主执行」挡位，工作流层应先交 AI 命令审查器评审，
     *   仅在审查拒绝/不确定/失败时才回退人工弹窗。
     */
    data class EvalResult(
        val verdict: Verdict,
        val rememberablePatterns: List<String>,
        val denyReason: String? = null,
        val rememberDisabledReason: String? = null,
        /** ASK 时的弹窗标题覆盖；null 表示用工具默认标题。 */
        val askTitle: String? = null,
        val aiReviewable: Boolean = false
    )

    suspend fun evaluate(tool: AgentTool?, toolName: String, args: Map<String, JsonElement>, mode: com.aicode.feature.agent.domain.model.AgentMode): EvalResult {
        val capabilities = tool?.effectiveCapabilities(args).orEmpty()
        if (mode == com.aicode.feature.agent.domain.model.AgentMode.PLAN && isDangerousTool(toolName, args, capabilities)) {
            return EvalResult(Verdict.DENY, emptyList(), denyReason = "当前处于 PLAN（计划）模式，系统物理沙盒已禁止修改系统状态或执行写操作。请在计划模式下仅调用只读工具探索代码，不要尝试修改文件或执行命令。")
        }

        if (mode == com.aicode.feature.agent.domain.model.AgentMode.AUTO) {
            // AUTO 模式放行所有权限；灾难性 rm 防护（根目录/系统目录删除）默认保留，
            // 可在「工具授权」设置中关闭（禁用安全拦截）。遭遇拦截时仍可凭 elevate 参数提权重试。
            val safetyDisabled = toolSafetySettings.isSafetyInterceptionDisabled()
            if (!safetyDisabled && isShellTool(toolName, args)) {
                val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
                if (command != null) {
                    val analysis = ShellCommandParser.analyze(command)
                    checkCatastrophicRm(analysis.segments)?.let { return elevationOrDeny(it, args) }
                    // 命令替换/子 shell/绝对路径重定向等无法静态判定的构造，删除目标不可知；
                    // 若同时疑似破坏性，宁可拦下（可提权），避免绕过安全防护。
                    if (!analysis.analyzable && looksDestructive(command)) {
                        return elevationOrDeny(REASON_UNANALYZABLE_DESTRUCTIVE, args)
                    }
                }
            }
            // Shizuku 直接操作宿主系统，AUTO 下也不自动放行，仍需逐次确认（除非已开启「禁用安全拦截」）。
            if (toolName == SHIZUKU_TOOL && !safetyDisabled) {
                return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
            }
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        if (capabilities == setOf(ToolCapability.READ_AGENT_CONFIG)) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // task 只读动作（read/list）：直接自动放行（不弹窗）。
        if (toolName == TASK_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase() ?: "create"
            if (action in TASK_AUTO_ACTIONS) return EvalResult(Verdict.ALLOW, emptyList())
        }

        val level = toolSafetySettings.getCommandAuthLevel()
        return if (isShellTool(toolName, args)) {
            // 仅「规则拦截」挡位需要高危规则集；「完全权限」需要安全开关决定是否保留兜底拦截。
            val highRiskRules = if (level == CommandAuthLevel.RULE_BASED) highRiskRulesRepo.getRulesOnce() else emptyList()
            val safetyDisabled = level == CommandAuthLevel.FULL_ACCESS && toolSafetySettings.isSafetyInterceptionDisabled()
            evaluateShell(
                args,
                forceAsk = toolName == SHIZUKU_TOOL,
                level = level,
                highRiskRules = highRiskRules,
                safetyDisabled = safetyDisabled
            )
        } else {
            evaluateGeneric(toolName, capabilities, level)
        }
    }

    private fun isDangerousTool(toolName: String, args: Map<String, JsonElement>, capabilities: Set<ToolCapability>): Boolean {
        val dangerousCapabilities = setOf(
            ToolCapability.WRITE_WORKSPACE,
            ToolCapability.EXECUTE_COMMANDS,
            ToolCapability.NETWORK_WRITE,
            ToolCapability.MODIFY_AGENT_CONFIG,
            ToolCapability.MODIFY_CONTAINER_ENV,
            ToolCapability.EXTERNAL_TOOL
        )
        if (capabilities.any { it in dangerousCapabilities }) return true

        // 只读探索工具在 PLAN 模式下永远安全
        val safePlanModeTools = setOf("list", "search")
        if (toolName in safePlanModeTools) return false

        val dangerousTools = setOf(
            "writeFile",
            "editFile",
            "Bash"
        )
        if (toolName in dangerousTools) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action != "read"
        }
        if (toolName == BROWSER_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()
            return action !in BROWSER_READ_ONLY_ACTIONS
        }
        return false
    }

    /** 是否按 shell 命令前缀匹配：[SHELL_TOOLS] 中的工具，或终端工具的 start/send 动作。 */
    private fun isShellTool(toolName: String, args: Map<String, JsonElement>): Boolean {
        if (toolName in SHELL_TOOLS) return true
        if (toolName == TERMINAL_TOOL) {
            val action = (args["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
            return action == TERMINAL_SHELL_ACTION || action == "send"
        }
        return false
    }

    /** 调用是否携带提权参数（`elevate: true`）。 */
    private fun isElevationRequested(args: Map<String, JsonElement>): Boolean =
        (args[ELEVATE_ARG] as? JsonPrimitive)?.content?.trim()?.lowercase() == "true"

    /**
     * 该命令是否疑似破坏性（仅在命令不可静态判定时使用）：含输出重定向、`-delete`
     * 或常见破坏性程序名。宁可多问，不误放。
     */
    private fun looksDestructive(command: String): Boolean =
        command.contains('>') || command.contains("-delete") || DESTRUCTIVE_PROGRAM.containsMatchIn(command)

    /**
     * 宽松版破坏性判定：用于「不可静态分析」命令的兜底分流。
     *
     * 与 [looksDestructive] 的区别：裸 `>` 一律视为破坏性过于粗糙——AI 排查类命令
     * 几乎条条带 `2>/dev/null`，会把无伤大雅的读取命令全部判成破坏。这里只认：
     * 1) [DESTRUCTIVE_PROGRAM] 命中（rm/dd/mkfs…，正则边界含 `()` 与反引号，
     *    覆盖 `$(rm -rf x)` 这类藏在命令替换内部的破坏）；
     * 2) `find … -delete`；
     * 3) 覆盖写重定向 `> 目标`——目标不是 /dev/null、不是 fd 复制（2>&1）、
     *    且不是 `>>` 追加。
     */
    private fun looksDestructiveLoose(command: String): Boolean =
        command.contains("-delete") ||
            DESTRUCTIVE_PROGRAM.containsMatchIn(command) ||
            hasOverwriteRedirect(command)

    /** 是否存在覆盖写重定向（`> file`）。`>>` 追加、`2>/dev/null`、`2>&1`、`<<<` 不算。 */
    private fun hasOverwriteRedirect(command: String): Boolean {
        var i = 0
        val n = command.length
        while (i < n) {
            if (command[i] == '>') {
                val double = i + 1 < n && command[i + 1] == '>'
                // herestring（<<<）里的 > 不算重定向
                if (i > 0 && command[i - 1] == '<') { i++; continue }
                if (!double) {
                    val rest = command.substring(i + 1).trimStart()
                    // 空 target / fd 复制（&1）/ 丢弃输出（/dev/null）都无害
                    if (rest.isNotEmpty() && !rest.startsWith("&") && !rest.startsWith("/dev/null")) {
                        return true
                    }
                }
                i += if (double) 2 else 1
            } else {
                i++
            }
        }
        return false
    }

    /**
     * 灾难性删除的裁决：携带提权参数时降级为一次性用户授权（ASK），否则硬拒绝（DENY）
     * 并在原因里提示可提权重试。AUTO 与非 AUTO 模式共用。提权仅对本次调用生效，不可记忆。
     */
    private fun elevationOrDeny(catastrophicReason: String, args: Map<String, JsonElement>): EvalResult =
        if (isElevationRequested(args)) {
            EvalResult(
                verdict = Verdict.ASK,
                rememberablePatterns = emptyList(),
                askTitle = "高危操作提权确认",
                rememberDisabledReason = "命令命中内置安全防护，提权仅支持单次放行，不可记忆"
            )
        } else {
            EvalResult(
                Verdict.DENY,
                emptyList(),
                denyReason = "$catastrophicReason。如确需执行，可在调用时加 `$ELEVATE_ARG: true` 重试，系统将向用户请求授权。"
            )
        }

    /** 把「始终允许」的选择落库为 ALLOW 规则（去重交给仓库）。 */
    suspend fun remember(toolName: String, patterns: List<String>, scope: PermissionScope) {
        patterns.distinct().forEach { pattern ->
            rulesRepo.add(scope, PermissionRule(toolName, pattern, PermissionDecision.ALLOW))
        }
    }

    /**
     * 非 shell 工具（文件读写/编辑等）的裁决。
     *
     * 命令类工具的授权口径已统一收敛到三挡位 + 高危规则，旧的「项目/全局」记忆规则不再参与；
     * 但对非命令类工具仍然保留「始终允许」记忆——这类工具没有高危规则可匹配，
     * 若去掉记忆，每次写文件都要弹窗，无法正常使用。
     */
    private suspend fun evaluateGeneric(
        toolName: String,
        capabilities: Set<ToolCapability>,
        level: CommandAuthLevel
    ): EvalResult {
        if (capabilities.any { it in NON_REMEMBERABLE_CAPABILITIES }) {
            return EvalResult(
                Verdict.ASK,
                rememberablePatterns = emptyList(),
                rememberDisabledReason = "该工具会修改 Agent 配置、容器环境或调用外部动态工具，为降低误授权风险，仅支持单次放行"
            )
        }
        // 完全权限：命令与非命令工具一律放行。
        if (level == CommandAuthLevel.FULL_ACCESS) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        // 已记忆的整工具 ALLOW（「始终允许此工具」）→ 直接放行。
        val rules = rulesRepo.loadEffectiveForCurrentProject()
        if (rules.any { it.toolName == toolName && it.decision == PermissionDecision.ALLOW && it.pattern == PermissionRule.WHOLE_TOOL }) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }
        return EvalResult(Verdict.ASK, listOf(PermissionRule.WHOLE_TOOL))
    }

    private fun checkCatastrophicRm(segments: List<List<String>>): String? {
        for (seg in segments) {
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            if (!rmInfo.isRm) continue
            for (rawPath in rmInfo.targetPaths) {
                catastrophicReasonFor(rawPath)?.let { return it }
            }
        }
        return null
    }

    /** 单个删除目标的裁决：命中受保护范围返回对应原因，否则 null。 */
    private fun catastrophicReasonFor(rawPath: String): String? {
        val raw = rawPath.trim()
        if (raw.isEmpty()) return null
        val path = normalizePath(raw)
        return when {
            path == "/" || path == "/*" -> REASON_RM_ROOT
            path in RELATIVE_WILDCARD_TARGETS -> REASON_RM_RELATIVE
            ROOT_LEVEL_GLOB.matches(path) -> REASON_RM_SYSTEM_DIR
            path == HOME_DIR || path == "$HOME_DIR/*" -> REASON_RM_HOME
            path in WORKSPACE_ROOT_TARGETS -> REASON_RM_WORKSPACE
            // 工作区子目录（构建产物等）属正常操作，置于系统目录判定之前放行
            path.startsWith("$HOME_DIR/workspace/") || path.startsWith("workspace/") -> null
            path == "/tmp" || path == "/tmp/*" -> REASON_RM_TMP
            PROTECTED_SYSTEM_DIRS.any { path == it || path.startsWith("$it/") } -> REASON_RM_SYSTEM_DIR
            else -> null
        }
    }

    /**
     * 词法路径归一化：把开头的 `~` / `$HOME` / `${HOME}` 展开为容器家目录，折叠重复 `/`，
     * 解析 `.` 与 `..`。纯字符串处理、不访问文件系统，故无法覆盖 `$(...)` 等运行时才可知的路径。
     */
    private fun normalizePath(raw: String): String {
        val expanded = when {
            raw == "~" -> HOME_DIR
            raw.startsWith("~/") -> HOME_DIR + raw.drop(1)
            raw == HOME_TOKEN || raw == HOME_BRACED -> HOME_DIR
            raw.startsWith("$HOME_TOKEN/") -> HOME_DIR + raw.drop(HOME_TOKEN.length)
            raw.startsWith("$HOME_BRACED/") -> HOME_DIR + raw.drop(HOME_BRACED.length)
            else -> raw
        }
        val absolute = expanded.startsWith("/")
        val parts = ArrayDeque<String>()
        for (part in expanded.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty() && parts.last() != "..") parts.removeLast() else if (!absolute) parts.addLast("..")
                else -> parts.addLast(part)
            }
        }
        val joined = parts.joinToString("/")
        return when {
            absolute -> "/$joined"
            joined.isEmpty() -> "."
            else -> joined
        }
    }

    private fun evaluateShell(
        args: Map<String, JsonElement>,
        forceAsk: Boolean = false,
        level: CommandAuthLevel,
        highRiskRules: List<HighRiskRule>,
        safetyDisabled: Boolean
    ): EvalResult {
        val command = ((args["command"] ?: args["input"]) as? JsonPrimitive)?.content
            ?: return EvalResult(Verdict.ASK, emptyList())

        val analysis = ShellCommandParser.analyze(command)

        // 0) rm 高危操作防护：禁止直接删除系统根目录、工作区根目录或系统关键目录。
        //    携带提权参数时降级为一次性用户授权。所有挡位共用。
        val catastrophicReason = checkCatastrophicRm(analysis.segments)
        if (catastrophicReason != null) {
            return elevationOrDeny(catastrophicReason, args)
        }

        // 1) Shizuku 高危工具：无论内置白名单，一律弹窗且不可记忆。
        if (forceAsk) {
            return EvalResult(Verdict.ASK, emptyList(), rememberDisabledReason = REASON_SHIZUKU)
        }

        // 2) 可静态判定且每段都命中内置只读白名单（ls/git status 等）→ 自动放行。
        //    必须要求可判定：`cat $(rm -rf /)` 之类命令替换攻击的段首仍是 cat，不可凭白名单放行。
        if (analysis.analyzable && analysis.segments.isNotEmpty() &&
            analysis.segments.all { BuiltInSafeCommands.isSafe(it) }
        ) {
            return EvalResult(Verdict.ALLOW, emptyList())
        }

        // 3) 挡位分流。
        //    注：旧的「项目/全局」记忆规则（ALLOW/DENY）已整体下线——它会先于挡位生效，
        //    等于绕过高危拦截规则；现在授权口径统一由三挡位 + 高危规则决定。
        return when (level) {
            // 完全权限：灾难防护之上直接放行；未开「禁用安全拦截」时，
            // 不可静态判定且疑似破坏性的命令仍按提权流程拦一次。
            CommandAuthLevel.FULL_ACCESS -> {
                if (!safetyDisabled && !analysis.analyzable && looksDestructive(command)) {
                    elevationOrDeny(REASON_UNANALYZABLE_DESTRUCTIVE, args)
                } else {
                    EvalResult(Verdict.ALLOW, emptyList())
                }
            }

            // 规则拦截：命中高危规则 → 人工确认；不可静态判定 → 破坏特征扫描；其余自动放行。
            CommandAuthLevel.RULE_BASED -> {
                HighRiskRuleMatcher.matchAny(highRiskRules, command)?.let { hit ->
                    return askWithRememberable(
                        analysis,
                        askTitle = "命中高危规则：${hit.description.ifBlank { hit.pattern }}"
                    )
                }
                if (!analysis.analyzable) {
                    // 不可静态判定（命令替换/子shell/循环等）。此前一律弹窗——AI 排查类
                    // 命令充满 $() 与反引号，实测条条命中兜底分支，高危规则反而一次没
                    // 触发过，授权弹窗形同骚扰。改为对**原文**做破坏特征扫描：
                    // DESTRUCTIVE_PROGRAM 正则的边界符含 `()` 与反引号，能覆盖 $()
                    // 内部；覆盖写重定向（> file）单独判定（2>/dev/null、>>、2>&1 无害）。
                    // 无破坏特征 → 放行；有 → 人工确认。
                    if (looksDestructiveLoose(command)) {
                        return askWithRememberable(
                            analysis,
                            askTitle = "命令含复杂构造（命令替换/子shell）且检测到破坏性特征，需人工确认"
                        )
                    }
                    return EvalResult(Verdict.ALLOW, emptyList())
                }
                EvalResult(Verdict.ALLOW, emptyList())
            }

            // 自主执行：统一转交 AI 命令审查器（工作流层处理），审查不过再回退人工。
            CommandAuthLevel.AUTONOMOUS -> askWithRememberable(analysis, aiReviewable = true)
        }
    }

    /**
     * 构造命令类 ASK 裁决。命令的「始终允许」记忆已下线——它会落到项目/全局规则里，
     * 先于挡位与高危拦截规则生效，等于绕过授权挡位；命令一律只支持单次放行。
     */
    private fun askWithRememberable(
        analysis: ShellCommandParser.Analysis,
        askTitle: String? = null,
        aiReviewable: Boolean = false
    ): EvalResult {
        val hasRmUnrememberable = analysis.segments.any { seg ->
            val rmInfo = ShellCommandParser.parseRmInfo(seg)
            rmInfo.isRm && (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard)
        }
        val reason = if (hasRmUnrememberable) {
            "高风险删除操作（递归/通配/强制批量删除），为确保数据安全不可记忆，仅可单次放行"
        } else {
            "命令授权由「授权挡位 + 高危拦截规则」统一管理，仅支持单次放行"
        }
        return EvalResult(
            verdict = Verdict.ASK,
            rememberablePatterns = emptyList(),
            rememberDisabledReason = reason,
            askTitle = askTitle,
            aiReviewable = aiReviewable
        )
    }
}
