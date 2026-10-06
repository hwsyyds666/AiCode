package com.aicode.feature.agent.domain.permission

import com.aicode.feature.agent.domain.model.AgentMode
import com.aicode.feature.agent.domain.tool.AgentTool
import com.aicode.feature.agent.domain.tool.ToolCapability
import com.aicode.feature.settings.data.repository.CommandAuthLevel
import com.aicode.feature.settings.data.repository.ToolSafetySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权策略引擎的分支覆盖。规则加载经 mock 注入，聚焦 [ToolPermissionPolicyEngine.evaluate]
 * 的裁决逻辑：PLAN/AUTO 模式特判、DENY 优先、白名单/记忆规则、rm 高危删除防护与不可记忆降级。
 * 底层 [ShellCommandParser] / [BuiltInSafeCommands] 的解析细节由各自独立测试覆盖，不在此重复。
 */
class ToolPermissionPolicyEngineTest {

    /**
     * 构造策略引擎：授权规则与挡位经 mock 注入；高危规则默认使用真实预设集（贴近生产行为），
     * 挡位默认 [CommandAuthLevel.RULE_BASED]（与仓库默认值一致），可按用例覆盖。
     */
    private fun engine(
        vararg rules: PermissionRule,
        safetyDisabled: Boolean = false,
        level: CommandAuthLevel = CommandAuthLevel.RULE_BASED,
        highRiskRules: List<HighRiskRule> = HighRiskRulePresets.RULES
    ): ToolPermissionPolicyEngine {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.loadEffectiveForCurrentProject() } returns rules.toList()
        val safety = mockk<ToolSafetySettingsRepository>(relaxed = true)
        coEvery { safety.isSafetyInterceptionDisabled() } returns safetyDisabled
        coEvery { safety.getCommandAuthLevel() } returns level
        val highRiskRepo = mockk<HighRiskRulesRepository>(relaxed = true)
        coEvery { highRiskRepo.getRulesOnce() } returns highRiskRules
        return ToolPermissionPolicyEngine(repo, safety, highRiskRepo)
    }

    private fun tool(vararg caps: ToolCapability): AgentTool {
        val t = mockk<AgentTool>(relaxed = true)
        every { t.capabilities } returns caps.toSet()
        every { t.effectiveCapabilities(any()) } returns caps.toSet()
        return t
    }

    private fun bash(command: String) = mapOf("command" to JsonPrimitive(command))

    private fun terminal(action: String) = mapOf("action" to JsonPrimitive(action))

    // ── PLAN 模式：写/执行类工具一律拒绝 ─────────────────────────────

    @Test
    fun planMode_deniesWorkspaceWriteTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertNotNull(r.denyReason)
    }

    @Test
    fun planMode_deniesBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_deniesTerminalStart() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "terminal", terminal("start"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_allowsReadOnlyExplorerTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun planMode_allowsTerminalRead() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "terminal", terminal("read"), AgentMode.PLAN)
        // 无任何规则时按整工具 ASK（可记忆），而非 DENY
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── AUTO 模式：放行但保留灾难性 rm 防护 ──────────────────────────

    @Test
    fun autoMode_allowsAnyTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_allowsBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.startsWith("安全防护：禁止执行高危删除操作（根目录删除）") == true)
    }

    @Test
    fun autoMode_stillBlocksWorkspaceRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun autoMode_disabledSafety_allowsCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun disabledSafety_buildMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 只读 Agent 配置自动放行 ─────────────────────────────────────

    @Test
    fun readAgentConfig_capabilityAutoAllow() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_AGENT_CONFIG), "customTool", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── task 只读动作放行，whole DENY 仍生效 ─────────────────────────

    @Test
    fun taskReadAction_allowsWithoutRules() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun taskReadAction_alwaysAllowed() = runTest {
        // task 的只读动作走 evaluate 的快捷放行分支，早于挡位与规则匹配，规则不影响结果
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 记忆规则已下线：shell 授权只由「挡位 + 高危规则」决定 ──────────

    @Test
    fun shellDenyRuleIgnored_authLevelDecides() = runTest {
        // 命令类工具的 ALLOW/DENY 记忆规则已整体下线（会先于挡位生效、绕过高危拦截），
        // ls 命中内置只读白名单 → 放行，DENY 规则不参与。
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 不可静态判定：无破坏特征则放行 ───────────────────────────────

    @Test
    fun unanalyzableBenignCommand_allowed() = runTest {
        // 命令替换使静态判定失效，但对原文做破坏特征扫描无命中 → 放行（AI 排查命令常含 $()）
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 内置安全白名单自动放行 ───────────────────────────────────────

    @Test
    fun safeCommand_autoAllowed() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun mixOfSafeAndNonHighRisk_allowed() = runTest {
        // 高危规则只覆盖递归/通配/批量删除，裸 `rm x` 未命中；整条命令可静态判定 → 放行。
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la && rm x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 已记忆 ALLOW 规则 ───────────────────────────────────────────

    @Test
    fun rememberedPrefix_allows() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun rememberedPrefix_doesNotMatchOtherSubcommand() = runTest {
        // 「规则拦截」挡位语义：git clone 未命中高危规则且可静态判定 → 直接放行（不再逐次询问）；
        // 同时也验证了 "git pull" 记忆规则不会误放行其它 git 子命令后再被高危规则拦下。
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── rm 精细校验：无目标 / 递归 / 通配的规则不得放行 ───────────────

    @Test
    fun bareRmRuleIgnored_nonRecursiveRmAllowed() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        // 规则不参与；`rm file.txt` 非递归/无通配，未命中高危规则 → 放行
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun nonRecursiveRmRule_doesNotAllowRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm file.txt", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /some/dir"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty()) // 递归删除不可记忆
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun recursiveRmRuleIgnored_stillAsks() = runTest {
        // ALLOW 记忆规则已下线，递归删除命中高危规则 → 人工确认且不可记忆
        val e = engine(
            PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    // ── 灾难性 rm 防护在 BUILD 模式同样生效 ─────────────────────────

    @Test
    fun catastrophicRm_deniedEvenWithAllowRule() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun systemDirRm_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 提权：非 AUTO 模式下携带 elevate 将硬拒绝降级为一次性授权 ──────

    @Test
    fun catastrophicRm_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.BUILD
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.askTitle)
    }

    @Test
    fun catastrophicRm_denyReasonHintsElevate() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == true)
    }

    @Test
    fun catastrophicRm_autoMode_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.AUTO
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 路径归一化：多种等价写法均需拦截，工作区子目录放行 ─────────────

    private suspend fun denied(command: String): Boolean =
        engine().evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash(command), AgentMode.BUILD).verdict ==
            ToolPermissionPolicyEngine.Verdict.DENY

    @Test
    fun abnormalPathSpellings_denied() = runTest {
        assertTrue(denied("rm -rf //etc"))
        assertTrue(denied("rm -rf /./etc"))
        assertTrue(denied("rm -rf /etc/../etc"))
        assertTrue(denied("rm -rf /tmp/../etc"))
        assertTrue(denied("rm -rf /foo/../"))
        assertTrue(denied("rm -rf \$HOME"))
        assertTrue(denied("rm -rf \${HOME}/foo"))
        assertTrue(denied("rm -rf /et*"))
        assertTrue(denied("rm -rf /usr*"))
        assertTrue(denied("rm -rf /srv"))
        assertTrue(denied("rm -rf /mnt"))
    }

    @Test
    fun workspaceSubdir_allowed() = runTest {
        assertTrue(!denied("rm -rf ~/workspace/build"))
        assertTrue(!denied("rm -rf ~/workspace/app/src"))
        assertTrue(!denied("rm -rf /tmp/build"))
    }

    @Test
    fun workspaceRoot_stillDenied() = runTest {
        assertTrue(denied("rm -rf ~/workspace"))
        assertTrue(denied("rm -rf ~/workspace/*"))
        assertTrue(denied("rm -rf ~/workspace/.."))
    }

    // ── AUTO：无法静态判定的疑似破坏性命令保守拦截 ─────────────────

    @Test
    fun autoMode_unanalyzableDestructive_denied() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf \$(echo /etc)"), AgentMode.AUTO).verdict
        )
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO).verdict
        )
    }

    @Test
    fun autoMode_unanalyzableBenign_allowed() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo \$(date)"), AgentMode.AUTO).verdict
        )
    }

    @Test
    fun autoMode_safetyDisabled_unanalyzable_allowed() = runTest {
        val e = engine(safetyDisabled = true)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO).verdict
        )
    }

    // ── Shizuku 高危工具：一律弹窗、不可记忆，AUTO 不豁免 ─────────────

    private fun shizuku(command: String) = mapOf("command" to JsonPrimitive(command))

    @Test
    fun shizuku_buildMode_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safeCommandStillAsks() = runTest {
        val e = engine()
        // 内置安全白名单（ls）对 Shizuku 不适用，仍需弹窗
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("ls -la /sdcard"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_rememberedAllowRuleIgnored() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.ALLOW))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_denyRuleStillAsks() = runTest {
        // Shizuku 一律走 forceAsk 分支（弹窗且不可记忆），DENY 规则不参与 → 结果为 ASK
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.DENY))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_autoMode_asks() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_autoMode_safetyDisabled_allows() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun shizuku_buildMode_safetyDisabled_stillAsksWithoutRememberable() = runTest {
        val e = engine(safetyDisabled = true)
        // 开关仅解除 AUTO 豁免，不可记忆在 BUILD 下始终生效
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_planMode_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.PLAN)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 非 shell 工具 ───────────────────────────────────────────────

    @Test
    fun genericTool_wholeRuleAllows() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun genericTool_unrememberableCapability_asksOnce() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.MODIFY_AGENT_CONFIG), "editFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun genericTool_wholeDenyRule_asks() = runTest {
        // 非 shell 工具只认「整工具 ALLOW」记忆，DENY 规则不产生硬拒绝 → 落到默认的整工具 ASK
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertEquals(listOf(PermissionRule.WHOLE_TOOL), r.rememberablePatterns)
    }

    // ── remember：去重后逐条落库 ────────────────────────────────────

    @Test
    fun remember_dedupesAndAddsEach() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.add(any(), any()) } just runs
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true), mockk(relaxed = true))

        e.remember("Bash", listOf("git pull", "git pull", "ls"), PermissionScope.PROJECT)

        coVerify(exactly = 2) { repo.add(PermissionScope.PROJECT, any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "ls", PermissionDecision.ALLOW)) }
    }

    // ── 「规则拦截」挡位：命中高危规则 → 人工确认；普通命令直接放行 ─────────────

    @Test
    fun ruleBased_highRiskHit_asksWithRuleTitle() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.askTitle?.startsWith("命中高危规则") == true)
        // 递归删除不可记忆
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun ruleBased_nonRecursiveRmWithoutRuleHit_allows() = runTest {
        // 裸 rm 单文件不在高危规则覆盖范围内（高危只管递归/通配/批量），可静态判定 → 放行
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_allRulesDisabled_allows() = runTest {
        // 用户停用全部高危规则后，可静态判定的普通命令直接放行
        val e = engine(highRiskRules = HighRiskRulePresets.RULES.map { it.copy(enabled = false) })
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableBenign_allowed() = runTest {
        // 未命中高危规则且不可静态判定（含命令替换）→ 对原文做破坏特征扫描，无命中则放行
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun ruleBased_unanalyzableDestructive_asks() = runTest {
        // 不可静态判定且原文含破坏特征 → 人工确认，且不可记忆
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf $(echo /tmp/x)"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── 「完全权限」挡位：直接放行，仅保留灾难防护与可选兜底 ──────────────────

    @Test
    fun fullAccess_allowsHighRiskCommand() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git reset --hard origin/main"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_unanalyzableDestructive_stillDenied() = runTest {
        // 未开「禁用安全拦截」时，不可静态判定且疑似破坏性的命令仍被拦（可提权）
        val e = engine(level = CommandAuthLevel.FULL_ACCESS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == true)
    }

    @Test
    fun fullAccess_safetyDisabled_allowsUnanalyzable() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS, safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun fullAccess_catastrophicRm_stillDenied() = runTest {
        val e = engine(level = CommandAuthLevel.FULL_ACCESS, safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 「自主执行」挡位：统一标记 aiReviewable 交工作流层 AI 审查 ─────────────

    @Test
    fun autonomous_marksAiReviewable() = runTest {
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.aiReviewable)
        // 命令的「始终允许」记忆已下线，一律只支持单次放行
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun autonomous_safeCommand_stillAutoAllowed() = runTest {
        // 内置白名单优先于挡位分流：ls 仍直接放行，不进入 AI 审查
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autonomous_recursiveRm_reviewableButNotRememberable() = runTest {
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.aiReviewable)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun autonomous_catastrophicRm_stillDenied() = runTest {
        // 灾难防护在挡位分流之前，自主执行也不能硬闯
        val e = engine(level = CommandAuthLevel.AUTONOMOUS)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }
}