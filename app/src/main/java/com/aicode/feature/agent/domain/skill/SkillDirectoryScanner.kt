package com.aicode.feature.agent.domain.skill

import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * 目录型技能源的共享扫描逻辑：递归查找 SKILL.md / CLAUDE.md，
 * 每个含指令文件的目录解析为一个 Skill。
 *
 * 目录经 [FileAccessProvider] 以容器路径访问，本地与远程（SSH）同一套逻辑。
 */
object SkillDirectoryScanner {
    private const val TAG = "SkillDirectoryScanner"

    /** 允许一定的嵌套深度（比如 repo/skills/my-skill/SKILL.md）。 */
    private const val MAX_DEPTH = 4
    private const val SKILL_FILE = "SKILL.md"
    private const val CLAUDE_FILE = "CLAUDE.md"

    /**
     * 扫描 [root] 目录下所有合法技能，按名称排序。
     * 目录不存在时返回空列表；扫描过程异常（IO 失败、远程工作区未连接等）时降级为空列表并记日志，
     * 不向上抛——技能扫描失败不该让调用方（工作区切换、AI 请求）崩溃。
     */
    fun scan(provider: FileAccessProvider, root: String): List<Skill> = runCatching {
        val base = root.trimEnd('/')
        // 递归结果里已带文件名（形如 "repo/skills/my-skill/SKILL.md"），直接据此挑出技能文件，
        // 不再对每个目录回查一次 listFiles——真机 ROOT 通道下那等于每个 skill 多 fork 一个 su 进程。
        val picked: List<Pair<String, String>> = provider.listFilesRecursive(root, MAX_DEPTH)
            .map { it.removePrefix("./") }
            .filter { relative ->
                val name = relative.substringAfterLast('/')
                name.equals(SKILL_FILE, ignoreCase = true) || name.equals(CLAUDE_FILE, ignoreCase = true)
            }
            .groupBy { it.substringBeforeLast('/', "") }
            .values.mapNotNull { files ->
                val chosen = files.firstOrNull { it.substringAfterLast('/').equals(SKILL_FILE, ignoreCase = true) }
                    ?: files.firstOrNull { it.substringAfterLast('/').equals(CLAUDE_FILE, ignoreCase = true) }
                    ?: return@mapNotNull null
                val dirRel = chosen.substringBeforeLast('/', "")
                val dirPath = if (dirRel.isEmpty()) base else "$base/$dirRel"
                "$base/$chosen" to dirPath
            }
        if (picked.isEmpty()) return@runCatching emptyList()

        // 一次批量读取（ROOT 通道一次 su 往返取回整批），替代逐个 readFile 的上百次子进程。
        val contents = provider.readFiles(picked.map { it.first })
        picked.mapNotNull { (filePath, dirPath) ->
            val text = contents[filePath] ?: return@mapNotNull null
            SkillParser.parseText(text, dirPath.substringAfterLast('/').ifBlank { dirPath })
                .copy(dirPath = dirPath)
        }.sortedBy { it.name.lowercase() }
    }.getOrElse { e ->
        FileLogger.w(TAG, "扫描技能目录失败: $root", e)
        emptyList()
    }
}
