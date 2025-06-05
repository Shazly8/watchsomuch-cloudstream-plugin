package com.watchsomuch

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URI

class WatchSoMuchProvider : MainAPI() { // all providers must be an instance of MainAPI
    override var mainUrl = "https://watchsomuch.to"
    override var name = "WatchSoMuch"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
    )

    override val mainPage = mainPageOf(
        "$mainUrl/?browse=popular" to "Popular Movies",
        "$mainUrl/?browse=new" to "New Movies", 
        "$mainUrl/?browse=series" to "Series",
        "$mainUrl/?browse=documentaries" to "Documentaries",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data + if (page > 1) "&page=$page" else "").document
        val home = document.select("div.movie-item, div.series-item, .movie-box").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("a")?.attr("title")?.trim() ?: 
                   this.selectFirst(".title, h3, h2")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")?.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: 
                                  this.selectFirst("img")?.attr("data-src"))
        val quality = this.selectFirst(".quality, .video-quality")?.text()
        val year = this.selectFirst(".year")?.text()?.toIntOrNull()
        
        // Check if it's a series based on URL or content
        val isSeries = href.contains("/Series/") || href.contains("series") || 
                      this.selectFirst(".series-indicator")?.text()?.isNotEmpty() == true

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
                this.year = year
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = getQualityFromString(quality)
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = app.get("$mainUrl/search/$query").document

        return searchResponse.select("div.movie-item, div.series-item, .search-result").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .movie-title, .title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(document.selectFirst(".poster img, .movie-poster img, img")?.attr("src"))
        val tags = document.select(".genre a, .genres a").map { it.text() }
        val year = document.selectFirst(".year, .release-year")?.text()?.toIntOrNull()
        val tvType = if (url.contains("/Series/") || url.contains("series")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst(".description, .synopsis, .plot")?.text()?.trim()
        val rating = document.selectFirst(".rating, .imdb-rating")?.text()?.toRatingInt()
        val duration = document.selectFirst(".duration, .runtime")?.text()

        // Extract cast from the page
        val actors = document.select(".cast a, .actors a").map {
            ActorData(Actor(it.text().trim()))
        }

        return if (tvType == TvType.TvSeries) {
            // For series, try to find episodes
            val episodes = document.select(".episode-item, .episode").mapNotNull { ep ->
                val href = ep.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                val name = ep.selectFirst(".episode-title, .title")?.text()
                val season = ep.attr("data-season")?.toIntOrNull() ?: 1
                val episode = ep.attr("data-episode")?.toIntOrNull() ?: 
                            ep.selectFirst(".episode-number")?.text()?.toIntOrNull() ?: 1
                
                Episode(
                    fixUrl(href),
                    name,
                    season,
                    episode,
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                addActors(actors)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.rating = rating
                this.duration = duration
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Look for download/streaming links
        document.select("a[href*='download'], a[href*='stream'], .download-link").forEach { link ->
            val videoUrl = link.attr("href")
            if (videoUrl.isNotEmpty() && (videoUrl.contains(".mp4") || videoUrl.contains(".mkv") || videoUrl.contains(".m3u8"))) {
                val quality = link.text().let { text ->
                    when {
                        text.contains("1080p") -> Qualities.P1080.value
                        text.contains("720p") -> Qualities.P720.value  
                        text.contains("480p") -> Qualities.P480.value
                        text.contains("4K") -> Qualities.P2160.value
                        else -> Qualities.Unknown.value
                    }
                }
                
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        fixUrl(videoUrl),
                        referer = data,
                        quality = quality,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            }
        }
        
        // Look for embedded players
        document.select("iframe, embed").forEach { iframe ->
            val iframeSrc = iframe.attr("src")
            if (iframeSrc.isNotEmpty()) {
                try {
                    loadExtractor(fixUrl(iframeSrc), data, subtitleCallback, callback)
                } catch (e: Exception) {
                    // Continue to next iframe if this one fails
                }
            }
        }

        // Look for JavaScript variables that might contain video URLs
        document.select("script").forEach { script ->
            val scriptContent = script.html()
            // Look for common video URL patterns
            val videoRegex = Regex("""["']([^"']*\.(?:mp4|mkv|m3u8|avi)[^"']*)["']""")
            videoRegex.findAll(scriptContent).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.startsWith("http")) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            name,
                            videoUrl,
                            referer = data,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }

        return true
    }

    private fun getQualityFromName(qualityName: String): Int {
        return when (qualityName.lowercase()) {
            "4k", "2160p" -> Qualities.P2160.value
            "1440p" -> Qualities.P1440.value
            "1080p" -> Qualities.P1080.value
            "720p" -> Qualities.P720.value
            "480p" -> Qualities.P480.value
            "360p" -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
