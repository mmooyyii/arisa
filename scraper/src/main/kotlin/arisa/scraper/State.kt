package arisa.scraper

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import arisa.quark.QuarkTvClient
import java.io.File

/** 登录态, 存在 ~/.arisa/state.json, 不进配置文件. */
@Serializable
data class State(
    val cookie: String? = null,
    val tv: QuarkTvClient.TvSession? = null,
)

class StateStore(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun load(): State = if (file.exists()) json.decodeFromString(State.serializer(), file.readText()) else State()

    @Synchronized
    fun update(block: (State) -> State) {
        val next = block(load())
        file.parentFile.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(State.serializer(), next))
        tmp.renameTo(file)
        file.setReadable(false, false)
        file.setReadable(true, true)
    }
}
