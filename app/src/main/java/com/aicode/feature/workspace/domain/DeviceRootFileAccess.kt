package com.aicode.feature.workspace.domain

import android.content.Context
import android.util.Base64
import com.aicode.core.util.FileLogger
import com.aicode.core.util.boundedLines
import com.aicode.feature.agent.domain.container.ContainerInstaller
import com.aicode.feature.agent.domain.device.DeviceRootEngine
import com.aicode.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [FileAccessProvider] 的真机 ROOT 实现：全部文件操作经 `su -c` 以 root 身份在真机上执行。
 *
 * 不走 java.io.File 直读，避免 uid 错配——root shell 命令创建的文件属 root，app uid 未必可读；
 * 统一经 root shell 后，文件工具与 `execute_command` 看到/操作的完全是同一批文件。
 *
 * 路径映射（对齐 [WorkspacePathMapper] 的容器布局）：
 * - `~/workspace[/…]`（含展开后的 `$HOME/workspace` 别名）→ 当前工作区的真机绝对路径；
 * - `~/.aicode[/…]`（含 `/root/.aicode` 写法）→ 全局配置目录 [ContainerInstaller.aicodeDir]；
 * - 其它绝对路径（`/etc/…`、`/data/…`）→ 原样作为真机路径（真机通道的意义所在）；
 * - 相对路径 → 挂到当前工作区根下。
 *
 * 实现要点：
 * - 每个操作一次 `su` fork（Magisk/KernelSU 记住授权后无感）；内容传输用 base64——
 *   读取经 stdout 回传，写入经 stdin 管道喂给 `base64 -d`（不受 argv 长度上限约束）；
 * - 脚本约定退出码：0 成功 / 2 不存在（[NoSuchFileException]）/ 3 已存在（[FileAlreadyExistsException]）/
 *   4 超过读取上限（[RemoteOutputTooLargeException]）/ 5 写入后长度校验失败 / 其它 [IOException]；
 * - 已知限制：文件名含换行或制表符时目录列举会解析异常（与现有 `ls | xargs` 类代码同级限制）；
 *   读写上限 [MAX_READ_BYTES]/[MAX_WRITE_BYTES]（内容需整体过内存）。
 */
@Singleton
class DeviceRootFileAccess @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
    private val pathHomeResolver: PathHomeResolver,
    private val containerInstaller: ContainerInstaller
) : FileAccessProvider {

    private companion object {
        const val TAG = "DeviceRootFileAccess"

        /** 单文件读取上限（字节），超限抛 [RemoteOutputTooLargeException]。 */
        const val MAX_READ_BYTES = 8L * 1024 * 1024

        /** 单次写入上限（字节）：内容需 base64 后整体过 stdin。 */
        const val MAX_WRITE_BYTES = 8L * 1024 * 1024

        /** 单次 shell 输出累积上限（字节）：8MB 文件的 base64 ≈ 11.2MB，留余量到 12MB。 */
        const val MAX_OUTPUT_BYTES = 12 * 1024 * 1024

        /** 常规操作超时（毫秒）。 */
        const val DEFAULT_TIMEOUT_MS = 60_000L

        /** 目录列举超时（毫秒）：大目录逐项 stat 较慢，放宽。 */
        const val LIST_TIMEOUT_MS = 120_000L

        val STDIN_FROM_DEV_NULL: ProcessBuilder.Redirect =
            ProcessBuilder.Redirect.from(File("/dev/null"))

        fun q(s: String): String = DeviceRootEngine.shellQuote(s)
    }

    // ---------- 路径映射 ----------

    /** 把 AI 提供的路径解析为真机绝对路径。 */
    private fun resolve(path: String): String {
        val ws = workspaceRepository.currentPath().trim().trimEnd('/')
        val home = pathHomeResolver.home().trimEnd('/')
        val wsAlias = "$home/workspace"
        val aicodeAlias = "$home/.aicode"
        val aicodeReal = containerInstaller.aicodeDir.absolutePath
        val p0 = path.trim()
        val p = pathHomeResolver.expandHome(p0)
        return when {
            p0 == "~/workspace" || p0 == "~/workspace/" || p == wsAlias || p == ws -> ws
            p0.startsWith("~/workspace/") -> joinPath(ws, p0.removePrefix("~/workspace/"))
            p.startsWith("$wsAlias/") -> joinPath(ws, p.removePrefix("$wsAlias/"))
            p0 == "~/.aicode" || p == aicodeAlias ||
                p == WorkspacePathMapper.AICODE_ROOT -> aicodeReal
            p0.startsWith("~/.aicode/") -> File(aicodeReal, p0.removePrefix("~/.aicode/")).path
            p.startsWith("$aicodeAlias/") -> File(aicodeReal, p.removePrefix("$aicodeAlias/")).path
            p.startsWith("${WorkspacePathMapper.AICODE_ROOT}/") ->
                File(aicodeReal, p.removePrefix("${WorkspacePathMapper.AICODE_ROOT}/")).path
            p.startsWith("/") -> p
            ws.isNotEmpty() -> File(ws, p).path
            else -> p
        }
    }

    private fun joinPath(root: String, suffix: String): String =
        if (root.isEmpty()) "/$suffix" else "$root/$suffix"

    private fun parentDir(real: String): String {
        val idx = real.trimEnd('/').lastIndexOf('/')
        return if (idx <= 0) "/" else real.substring(0, idx)
    }

    // ---------- shell 执行 ----------

    private data class ShResult(val output: String, val exitCode: Int, val truncated: Boolean)

    /**
     * 经 `su -c` 同步执行脚本并捕获全部输出。顺序：写 stdin（可选）→ 读 stdout 到 EOF → waitFor，
     * 单向流动无管道死锁；看门狗超时 destroy/forcibly 解除 read 阻塞。
     */
    private fun sh(script: String, stdin: ByteArray? = null, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShResult {
        val pb = ProcessBuilder(listOf("su", "-c", script))
        pb.redirectErrorStream(true)
        if (stdin == null) pb.redirectInput(STDIN_FROM_DEV_NULL)
        val process = pb.start()
        val timedOut = AtomicBoolean(false)
        val watchdog = Thread {
            try {
                Thread.sleep(timeoutMs)
            } catch (_: InterruptedException) {
                return@Thread
            }
            if (process.isAlive) {
                timedOut.set(true)
                runCatching { process.destroy() }
                Thread.sleep(200)
                if (process.isAlive) runCatching { process.destroyForcibly() }
            }
        }
        watchdog.isDaemon = true
        watchdog.start()
        try {
            if (stdin != null) {
                // 子进程可能提前退出（如 overwrite=false 的 exit 3）→ Broken pipe 属正常，忽略
                try {
                    process.outputStream.use { it.write(stdin); it.flush() }
                } catch (e: IOException) {
                    FileLogger.d(TAG, "stdin 写入中断（子进程已退出）: ${e.message}")
                }
            }
            val output = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var truncated = false
            while (true) {
                val n = try {
                    process.inputStream.read(buf)
                } catch (e: IOException) {
                    break // 看门狗 destroy 关管道
                }
                if (n < 0) break
                if (output.size() < MAX_OUTPUT_BYTES) output.write(buf, 0, n) else truncated = true
            }
            val exit = try {
                process.waitFor()
            } catch (e: InterruptedException) {
                -1
            }
            if (timedOut.get()) throw IOException("root shell 执行超时（>${timeoutMs}ms）")
            return ShResult(output.toString(Charsets.UTF_8.name()), exit, truncated)
        } finally {
            watchdog.interrupt()
            runCatching { process.destroy() }
        }
    }

    /** 按脚本退出码约定抛对应异常；0 直接返回。 */
    private fun ShResult.throwForError(displayPath: String) {
        when (exitCode) {
            0 -> return
            2 -> throw NoSuchFileException(displayPath)
            3 -> throw FileAlreadyExistsException(displayPath)
            4 -> throw RemoteOutputTooLargeException(
                "文件超过真机通道读取上限（${MAX_READ_BYTES / 1024 / 1024}MB）: $displayPath"
            )
            5 -> throw IOException("写入校验失败（落盘长度不符）: $displayPath")
            else -> throw IOException("root shell 失败(exit=$exitCode): ${output.take(300)}")
        }
    }

    /** 单条件 test：exit 0 → true。 */
    private fun testFlag(real: String, flag: String): Boolean =
        sh("test $flag ${q(real)}").exitCode == 0

    // ---------- 读 ----------

    override fun readFile(path: String): String = String(readBytes(path), Charsets.UTF_8)

    /**
     * 逐行读取。与本地/远程实现不同：真机通道每次迭代重新经 `su` 读全文件（非流式），
     * 受 [MAX_READ_BYTES] 上限约束；序列仍可重复迭代，单行截断由 [boundedLines] 保证。
     */
    override fun readLines(path: String): Sequence<String> =
        boundedLines { StringReader(readFile(path)) }

    override fun readBytes(path: String): ByteArray {
        val real = resolve(path)
        val r = sh(
            "sz=\$(stat -c %s ${q(real)} 2>/dev/null) || exit 2; " +
                "[ \"\$sz\" -gt $MAX_READ_BYTES ] && exit 4; " +
                "base64 < ${q(real)}"
        )
        r.throwForError(path)
        val clean = r.output.filter { !it.isWhitespace() }
        return runCatching { Base64.decode(clean, Base64.DEFAULT) }
            .getOrElse { throw IOException("base64 解码失败: $path (${it.message})") }
    }

    override fun exists(path: String): Boolean = testFlag(resolve(path), "-e")

    override fun isDirectory(path: String): Boolean = testFlag(resolve(path), "-d")

    override fun isFile(path: String): Boolean = testFlag(resolve(path), "-f")

    override fun fileSize(path: String): Long =
        sh("stat -c %s ${q(resolve(path))} 2>/dev/null").output.trim().toLongOrNull() ?: 0L

    override fun lastModified(path: String): Long =
        (sh("stat -c %Y ${q(resolve(path))} 2>/dev/null").output.trim().toLongOrNull() ?: 0L) * 1000

    override fun permissions(path: String): String {
        val a = sh("stat -c %A ${q(resolve(path))} 2>/dev/null").output.trim()
        // %A 形如 "-rw-r--r--"：取 owner 三位；取不到时 root 视角默认可读写执行
        return if (a.length >= 4) a.substring(1, 4) else "rwx"
    }

    override fun listFiles(path: String): List<FileEntry> {
        val real = resolve(path)
        // cd 进目标目录后遍历通配：名字不含路径分隔符，协议简单；逐项一次 stat 取 大小|时间|权限。
        // 空目录/无匹配时通配保留字面量，[ -e ]/[ -L ] 过滤掉。
        val script = "cd ${q(real)} 2>/dev/null || exit 2; " +
            "for f in * .[!.]* ..?*; do " +
            "[ -e \"\$f\" ] || [ -L \"\$f\" ] || continue; " +
            "if [ -d \"\$f\" ]; then t=d; elif [ -f \"\$f\" ]; then t=f; else t=o; fi; " +
            "sm=\$(stat -c '%s|%Y|%A' \"\$f\" 2>/dev/null); " +
            "printf '%s\\t%s\\t%s\\n' \"\$t\" \"\${sm:-0|0|-}\" \"\$f\"; " +
            "done"
        val r = sh(script, timeoutMs = LIST_TIMEOUT_MS)
        r.throwForError(path)
        return r.output.lineSequence()
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size < 3) return@mapNotNull null
                val name = parts.subList(2, parts.size).joinToString("\t")
                if (name.isEmpty()) return@mapNotNull null
                val meta = parts[1].split('|')
                FileEntry(
                    name = name,
                    isDirectory = parts[0] == "d",
                    size = meta.getOrNull(0)?.toLongOrNull() ?: 0L,
                    lastModified = (meta.getOrNull(1)?.toLongOrNull() ?: 0L) * 1000,
                    localFile = File(real, name),
                    permissions = meta.getOrNull(2)?.takeIf { it.length >= 4 }?.substring(1, 4) ?: "rwx"
                )
            }
            .toList()
    }

    override fun listFilesRecursive(path: String, maxDepth: Int): List<String> {
        val real = resolve(path)
        val r = sh(
            "[ -d ${q(real)} ] || exit 0; " +
                "cd ${q(real)} && find . -maxdepth $maxDepth -type f 2>/dev/null",
            timeoutMs = LIST_TIMEOUT_MS
        )
        if (r.exitCode != 0) return emptyList()
        return r.output.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.removePrefix("./") }
            .toList()
    }

    // ---------- 写 ----------

    /** 写文件本体：base64 经 stdin 管道喂 `base64 -d`，落盘后 stat 校验长度（对齐 Local 的写后校验）。 */
    private fun writeBytesInternal(path: String, bytes: ByteArray, overwrite: Boolean) {
        if (bytes.size > MAX_WRITE_BYTES) {
            throw IOException("写入内容超过真机通道上限（${MAX_WRITE_BYTES / 1024 / 1024}MB）: $path")
        }
        val real = resolve(path)
        val script = buildString {
            if (!overwrite) append("[ -e ").append(q(real)).append(" ] && exit 3; ")
            append("mkdir -p ").append(q(parentDir(real))).append(" || exit 1; ")
            append("base64 -d > ").append(q(real)).append(" || exit 1; ")
            append("sz=\$(stat -c %s ").append(q(real)).append(" 2>/dev/null || echo -1); ")
            append("[ \"\$sz\" = \"").append(bytes.size).append("\" ] || exit 5")
        }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        sh(script, stdin = b64.toByteArray(Charsets.US_ASCII)).throwForError(path)
        FileLogger.i(TAG, "写入(真机): '$path' -> $real (${bytes.size} 字节)")
    }

    override fun writeFile(path: String, content: String, overwrite: Boolean, encoding: Charset) =
        writeBytesInternal(path, content.toByteArray(encoding), overwrite)

    override fun writeBytes(path: String, bytes: ByteArray, overwrite: Boolean) =
        writeBytesInternal(path, bytes, overwrite)

    override fun writeStream(path: String, input: InputStream, overwrite: Boolean): Long {
        // 与 readBytes 同理需整体过内存（base64 经 stdin）；cap 与写上限一致。
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_WRITE_BYTES) {
                throw IOException("写入内容超过真机通道上限（${MAX_WRITE_BYTES / 1024 / 1024}MB）: $path")
            }
            acc.write(buf, 0, n)
        }
        writeBytesInternal(path, acc.toByteArray(), overwrite)
        return total
    }

    // ---------- 目录/文件操作 ----------

    override fun mkdirs(path: String) {
        // 对齐 Local（mkdirs 返回值忽略）：失败静默
        sh("mkdir -p ${q(resolve(path))} 2>/dev/null; exit 0")
    }

    override fun delete(path: String) {
        // 对齐 Local（file.delete() 返回值忽略）：不存在/失败静默
        val real = resolve(path)
        sh(
            "if [ -d ${q(real)} ] && [ ! -L ${q(real)} ]; then " +
                "rmdir ${q(real)} 2>/dev/null; " +
                "else rm -f ${q(real)} 2>/dev/null; fi; exit 0"
        )
    }

    override fun deleteRecursively(path: String) {
        val real = resolve(path)
        val r = sh("[ -e ${q(real)} ] || [ -L ${q(real)} ] || exit 0; rm -rf ${q(real)}")
        if (r.exitCode != 0) throw IOException("delete failed: $real (${r.output.take(200)})")
    }

    override fun rename(path: String, newPath: String) {
        val src = resolve(path)
        val dst = resolve(newPath)
        val script = "[ -e ${q(src)} ] || [ -L ${q(src)} ] || exit 2; " +
            "if [ -e ${q(dst)} ] || [ -L ${q(dst)} ]; then exit 3; fi; " +
            "mkdir -p ${q(parentDir(dst))} || exit 1; " +
            "mv ${q(src)} ${q(dst)} || exit 1"
        sh(script).throwForError(path)
    }

    override fun copy(path: String, newPath: String, overwrite: Boolean) {
        val src = resolve(path)
        val dst = resolve(newPath)
        if (dst == src || dst.startsWith("$src/")) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        val script = buildString {
            append("[ -e ").append(q(src)).append(" ] || [ -L ").append(q(src)).append(" ] || exit 2; ")
            append("if [ -e ").append(q(dst)).append(" ] || [ -L ").append(q(dst)).append(" ]; then ")
            if (overwrite) append("rm -rf ").append(q(dst)).append(" || exit 1; ") else append("exit 3; ")
            append("fi; ")
            append("mkdir -p ").append(q(parentDir(dst))).append(" || exit 1; ")
            append("cp -r ").append(q(src)).append(" ").append(q(dst)).append(" || exit 1")
        }
        sh(script).throwForError(path)
    }

    override fun move(path: String, newPath: String, overwrite: Boolean) {
        val src = resolve(path)
        val dst = resolve(newPath)
        if (dst == src || dst.startsWith("$src/")) {
            throw IOException("destination is the source or its descendant: $newPath")
        }
        // mv 跨文件系统自动退化为 复制+删除
        val script = buildString {
            append("[ -e ").append(q(src)).append(" ] || [ -L ").append(q(src)).append(" ] || exit 2; ")
            append("if [ -e ").append(q(dst)).append(" ] || [ -L ").append(q(dst)).append(" ]; then ")
            if (overwrite) append("rm -rf ").append(q(dst)).append(" || exit 1; ") else append("exit 3; ")
            append("fi; ")
            append("mkdir -p ").append(q(parentDir(dst))).append(" || exit 1; ")
            append("mv ").append(q(src)).append(" ").append(q(dst)).append(" || exit 1")
        }
        sh(script).throwForError(path)
    }

    // ---------- 本地互转 ----------

    override fun copyToLocal(path: String): File {
        val real = resolve(path)
        // 工作区 / sdcard 等 app 可直接读的路径免复制快路径（同 Local 语义）；
        // 仅 root 可读的路径经 su 读字节后落到缓存目录。
        val direct = File(real)
        if (direct.isFile && direct.canRead()) return direct
        val bytes = readBytes(path)
        val dir = File(context.cacheDir, "device_copy").apply { mkdirs() }
        val name = File(real).name.ifBlank { "file" }
        val tmp = File(dir, "${real.hashCode().toString(16)}-$name")
        tmp.writeBytes(bytes)
        return tmp
    }

    override fun parentPath(path: String): String? {
        val real = resolve(path).trimEnd('/')
        val idx = real.lastIndexOf('/')
        if (idx < 0) return null
        val parent = if (idx == 0) "/" else real.substring(0, idx)
        if (parent == real) return null
        return toDisplayPath(parent)
    }

    override fun toDisplayPath(path: String): String {
        val ws = workspaceRepository.currentPath().trim().trimEnd('/')
        val aicodeReal = containerInstaller.aicodeDir.absolutePath
        val p = path.trim().trimEnd('/').ifEmpty { "/" }
        return when {
            ws.isNotEmpty() && p == ws -> "~/workspace"
            ws.isNotEmpty() && p.startsWith("$ws/") -> "~/workspace/" + p.removePrefix("$ws/")
            p == aicodeReal -> "~/.aicode"
            p.startsWith("$aicodeReal/") -> "~/.aicode/" + p.removePrefix("$aicodeReal/")
            // 真机通道下其余路径即真实路径，原样回显
            else -> path
        }
    }
}
