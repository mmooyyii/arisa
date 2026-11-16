package arisa.library

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 跨版本兼容. 刮削器会自动更新, TV App 要手动更新, 所以总有「新刮削器 + 旧 App」「新 App + 旧索引」同时存在.
 * 这里的测试失败说明改动会让别的版本读不了, 先看 CLAUDE.md 里的兼容规则.
 */
class CompatTest {
    private fun resource(name: String) = javaClass.getResource("/compat/$name")!!.readText()

    /** 以前版本写出的文件, 现在必须还能读. 样本只加不删. */
    @Test fun oldFilesStillReadable() {
        val lib = LibraryCodec.json.decodeFromString(Library.serializer(), resource("library-schema1-v0.1.json"))
        assertEquals(17, lib.items.size)
        assertTrue(lib.items.all { it.title.isNotEmpty() })
        val manifest = LibraryCodec.json.decodeFromString(ReleaseManifest.serializer(), resource("latest-v0.1.json"))
        assertEquals("arisa-0.1.0.apk", manifest.app?.name)
        assertEquals(1000, manifest.versionCode)
    }

    /** Go 版刮削器 (scraper/) 写出的索引, TV App 要能读、能搜. 样本由 scraper 的 TestWriteCompatSample 生成. */
    @Test fun goScraperOutputReadable() {
        val lib = LibraryCodec.json.decodeFromString(Library.serializer(), resource("library-schema1-go.json"))
        assertEquals(Library.SCHEMA, lib.schema)
        assertEquals(5, lib.items.size)
        assertEquals(2, lib.collections.size)
        assertEquals(1, lib.unmatched.size)
        val show = lib.items.single { it.id == "tv:221851" }
        assertEquals(MediaType.TV, show.type)
        assertEquals(listOf(0, 1), show.seasons.map { it.number })
        assertEquals("zh-Hans", show.seasons.single { it.number == 1 }.episodes.single().files.single().subtitles.single().language)
        val search = LibrarySearch(lib)
        assertEquals("tv:221851", (search.search("mcdjj").first() as LibrarySearch.Hit.Item).item.id)
        assertTrue(search.search("liulangdiqiu").isNotEmpty())
    }

    /**
     * 必填 (没有默认值) 的字段冻结在这里. 旧版写的文件里没有新字段, 新字段必须带默认值;
     * 旧版 App 读新文件时也只认这些必填字段, 不能删或改名.
     */
    @Test fun requiredFieldsFrozen() {
        val expected = mapOf(
            "arisa.library.Library" to setOf("generatedAt"),
            "arisa.library.MediaItem" to setOf("id", "type", "title"),
            "arisa.library.MediaCollection" to setOf("id", "name", "cover", "items"),
            "arisa.library.MediaFile" to setOf("fid", "name", "path", "size"),
            "arisa.library.CastMember" to setOf("name"),
            "arisa.library.SubtitleFile" to setOf("fid", "name"),
            "arisa.library.Season" to setOf("number"),
            "arisa.library.Episode" to setOf("season", "episode"),
            "arisa.library.ReleaseManifest" to setOf("version", "versionCode"),
            "arisa.library.ReleaseAsset" to setOf("name", "size", "sha256"),
        )
        val actual = mutableMapOf<String, Set<String>>()
        fun walk(d: SerialDescriptor) {
            when (d.kind) {
                StructureKind.CLASS -> {
                    val name = d.serialName.removeSuffix("?")
                    if (name in actual) return
                    actual[name] = d.elementNames.filterIndexed { i, _ -> !d.isElementOptional(i) }.toSet()
                    d.elementDescriptors.forEach(::walk)
                }
                StructureKind.LIST, StructureKind.MAP -> d.elementDescriptors.forEach(::walk)
                else -> {}
            }
        }
        walk(Library.serializer().descriptor)
        walk(ReleaseManifest.serializer().descriptor)
        for ((cls, fields) in expected) assertEquals(fields, actual[cls], "$cls 的必填字段变了, 会破坏跨版本兼容")
        // 新加的类也不许有必填字段 (旧文件里没有它, 但它所在的字段有默认值时没关系, 统一要求带默认值最省事)
        val others = actual.filterKeys { it !in expected && it.startsWith("arisa.") }.filterValues { it.isNotEmpty() }
        assertEquals(emptyMap(), others, "新的必填字段会让旧文件读不了, 给它加默认值")
    }
}
