package eu.kanade.tachiyomi.extension.es.mangavault

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.nodes.Document

@Source
abstract class MangaVault : KeiSource() {

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?) =
        FilterList()

    override suspend fun getPopularManga(page: Int): MangasPage =
        parseMangaList(client.get(baseUrl))

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        parseMangaList(client.get(baseUrl))

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val document = client.get(baseUrl).asJsoup()
        val mangas = parseMangas(document)
        val filtered = if (query.isBlank()) mangas else mangas.filter {
            it.title.contains(query, ignoreCase = true)
        }
        return MangasPage(filtered, false)
    }

    private suspend fun parseMangaList(response: okhttp3.Response): MangasPage {
        return MangasPage(parseMangas(response.asJsoup()), false)
    }

    private fun parseMangas(document: Document): List<SManga> =
        document.select("a[href*=/manga/]")
            .mapNotNull { link ->
                val href = link.attr("href")
                val title = link.text().trim()
                val image = link.selectFirst("img")
                if (!href.contains("/manga/") || title.isBlank()) {
                    return@mapNotNull null
                }
                SManga.create().apply {
                    setUrlWithoutDomain(href)
                    this.title = title
                    thumbnail_url = image?.absUrl("src")
                        ?.takeIf { it.isNotBlank() }
                        ?: image?.absUrl("data-src")
                }
            }
            .distinctBy { it.url }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val document = client.get(url.toString()).asJsoup()
        return parseDetails(document)
    }

    override fun getMangaUrl(manga: SManga): String =
        "$baseUrl/${manga.url}".replace("$baseUrl//", "$baseUrl/")

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(getMangaUrl(manga)).asJsoup()
        val details = parseDetails(document)
        val updatedChapters = parseChapters(document)
        return SMangaUpdate(details, updatedChapters)
    }

    private fun parseDetails(document: Document): SManga =
        SManga.create().apply {
            title = document.selectFirst("h1")?.text()?.trim()
                ?: document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            description = document.selectFirst("meta[name=description]")?.attr("content").orEmpty()
            thumbnail_url = document.selectFirst("meta[property=og:image]")?.absUrl("content")
                ?: document.selectFirst("img")?.absUrl("src")
            genre = document.select("a[href*=/genre/]").eachText().distinct().joinToString(", ")
            status = SManga.UNKNOWN
            initialized = true
        }

    private fun parseChapters(document: Document): List<SChapter> {
        val data = document.toString()
        val regex = Regex(
            "\"chapter_number\":\\[0,([0-9]+(?:\\.[0-9]+)?)\\]"
        )
        val numbers = regex.findAll(data).mapNotNull {
            it.groupValues.getOrNull(1)?.toFloatOrNull()
        }.distinct().toList()

        return numbers.map { number ->
            SChapter.create().apply {
                chapter_number = number
                name = "Capítulo ${number.toString().removeSuffix(".0")}"
                setUrlWithoutDomain(
                    "${document.location().substringBeforeLast("/")}/${number.toString().removeSuffix(".0")}"
                )
            }
        }.sortedByDescending { it.chapter_number }
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(getChapterUrl(chapter)).asJsoup()
        val urls = document.select("img")
            .mapNotNull { image ->
                sequenceOf(
                    image.absUrl("src"),
                    image.absUrl("data-src"),
                    image.absUrl("data-lazy-src"),
                ).firstOrNull {
                    it.startsWith("https://media.mangavault.lat/")
                }
            }
            .distinct()

        return urls.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }
}
