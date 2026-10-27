package arisa.scraper

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** 识别规则, skill 和无人值守的 AI 共用 (arisa-scraper guide 打印的就是它). */
fun guideText(): String = resource("guide.md")

private fun resource(name: String) =
    object {}.javaClass.getResourceAsStream("/arisa/$name")!!.readBytes().decodeToString()

/**
 * 用 Claude Code 无头模式识别一批作品. AI 只能运行 arisa-scraper 和读图片,
 * 结论通过 decide / ask 命令写进映射表和待确认列表, 这里只负责启动、限时、留日志.
 */
class AgentRunner(
    private val config: AgentConfig,
    private val home: File,
) {
    data class Result(val ok: Boolean, val summary: String, val costUsd: Double?, val logFile: File)

    private val json = Json { prettyPrint = true; encodeDefaults = false; explicitNulls = false }

    fun run(works: List<PendingWork>): Result {
        val workDir = File(home, "agent").apply { mkdirs() }
        val logDir = File(home, "logs").apply { mkdirs() }
        val logFile = File(logDir, "agent-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.log")
        val prompt = buildString {
            appendLine("待识别 ${works.size} 部:")
            appendLine("```json")
            appendLine(json.encodeToString(ListSerializer(PendingWork.serializer()), works))
            appendLine("```")
        }
        val cmd = buildList {
            addAll(listOf(config.command, "-p", "--output-format", "json", "--no-session-persistence"))
            // 不加载用户的 MCP、设置、CLAUDE.md 和 skill, 省 token 也避免干扰
            addAll(listOf("--strict-mcp-config", "--setting-sources", "", "--disable-slash-commands"))
            addAll(listOf("--tools", "Bash", "Read", "--allowedTools", "Bash(arisa-scraper:*)", "Read"))
            addAll(listOf("--system-prompt", resource("agent.md") + guideText()))
            addAll(listOf("--effort", config.effort, "--max-budget-usd", config.maxBudgetUsd.toString()))
            config.model?.let { addAll(listOf("--model", it)) }
        }
        val process = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(false).apply {
            // launchd 下 PATH 很短, 补上 arisa-scraper 常见的安装位置
            val path = environment()["PATH"].orEmpty()
            val extra = listOf("${System.getProperty("user.home")}/.local/bin", "/opt/homebrew/bin", "/usr/local/bin")
            environment()["PATH"] = (path.split(':') + extra).filter { it.isNotEmpty() }.distinct().joinToString(":")
            environment()["ARISA_HOME"] = home.path
        }.start()
        process.outputStream.use { it.write(prompt.toByteArray()) }
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val readers = listOf(
            Thread { stdout.append(process.inputStream.bufferedReader().readText()) },
            Thread { stderr.append(process.errorStream.bufferedReader().readText()) },
        ).onEach { it.start() }
        val finished = process.waitFor(config.timeoutMinutes.toLong(), TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly()
        readers.forEach { it.join(10_000) }
        logFile.writeText("$ ${cmd.joinToString(" ") { if (it.length > 80) it.take(80) + "…" else it }}\n\n$prompt\n--- stdout\n$stdout\n--- stderr\n$stderr")

        if (!finished) return Result(false, "超过 ${config.timeoutMinutes} 分钟, 已停止", null, logFile)
        val out = runCatching { Json.parseToJsonElement(stdout.toString().trim()).jsonObject }.getOrNull()
            ?: return Result(false, "AI 没有正常输出 (退出码 ${process.exitValue()}): ${stderr.toString().trim().take(200)}", null, logFile)
        val cost = out["total_cost_usd"]?.jsonPrimitive?.doubleOrNull
        val isError = out["is_error"]?.jsonPrimitive?.booleanOrNull == true
        val text = out["result"]?.jsonPrimitive?.content?.trim().orEmpty()
        val subtype = out["subtype"]?.jsonPrimitive?.content
        return when {
            isError || process.exitValue() != 0 -> Result(false, text.ifEmpty { subtype ?: "退出码 ${process.exitValue()}" }, cost, logFile)
            else -> Result(true, text, cost, logFile)
        }
    }
}
