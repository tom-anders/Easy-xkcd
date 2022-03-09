package de.tap.easy_xkcd.database.whatif

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.bumptech.glide.Glide
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.tap.easy_xkcd.GlideApp
import de.tap.easy_xkcd.database.ProgressStatus
import de.tap.easy_xkcd.reddit.RedditSearchApi
import de.tap.easy_xkcd.utils.JsonParser
import de.tap.easy_xkcd.utils.PrefHelper
import de.tap.easy_xkcd.utils.ThemePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.BufferedSink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import ru.gildor.coroutines.okhttp.await
import timber.log.Timber
import java.io.*
import javax.inject.Inject
import javax.inject.Singleton


data class LoadedArticle(
    val article: Article,
    val html: String,

    val refs: List<String>
) {
    val number: Int get() = article.number
    val title: String get() = article.title
    val favorite: Boolean get() = article.favorite

    companion object {
        fun none() = LoadedArticle(Article(0, "", "", false, false), "", emptyList())
    }
}

interface ArticleRepository {
    val articles: Flow<List<Article>>

    suspend fun updateDatabase()

    suspend fun getRedditThread(article: Article): Uri?

    suspend fun setFavorite(number: Int, favorite: Boolean)

    suspend fun setRead(number: Int, read: Boolean)

    suspend fun searchArticles(query: String): List<Article>

    suspend fun setAllRead()

    suspend fun setAllUnread()

    suspend fun loadArticle(number: Int): LoadedArticle?

    suspend fun downloadArticle(number: Int)

    val downloadAllArticles: Flow<ProgressStatus>

    val downloadArchiveImages: Flow<ProgressStatus>

    suspend fun deleteAllOfflineArticles()
}

@Singleton
class ArticleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefHelper: PrefHelper,
    private val themePrefs: ThemePrefs,
    private val articleDao: ArticleDao,
    private val okHttpClient: OkHttpClient,
    private val redditSearchApi: RedditSearchApi,
) : ArticleRepository {
    companion object {
        const val OFFLINE_WHATIF_PATH = "/what if/"
        const val OFFLINE_WHATIF_OVERVIEW_PATH = "/what if/overview"
    }

    override val articles: Flow<List<Article>> = articleDao.getArticles()

    override suspend fun updateDatabase() = withContext(Dispatchers.IO) {
        try {
            val document =
                Jsoup.parse(
                    JsonParser.getNewHttpClient().newCall(
                        Request.Builder()
                            .url("https://what-if.xkcd.com/archive/")
                            .build()
                    ).await().body?.string()
                )
            if (document != null) {
                val titles = document.select("h1")
                val thumbnails = document.select("img.archive-image")

                val previousNumberOfArticles = articleDao.getArticlesSuspend().size
                val migrateLegacyDatabase = (previousNumberOfArticles == 0)

                val articles = (previousNumberOfArticles + 1..titles.size).map { number ->
                    Article(
                        number = number,
                        title = titles[number - 1].text(),
                        thumbnail = "https://what-if.xkcd.com/" + thumbnails[number - 1].attr("src"), // -1 cause articles a 1-based indexed
                    ).also { article ->
                        if (migrateLegacyDatabase) {
                            article.read = prefHelper.checkRead(number)
                            article.favorite = prefHelper.checkWhatIfFav(number)
                        }

                        if (prefHelper.fullOfflineWhatIf() && prefHelper.mayDownloadDataForOfflineMode(context)) {
                            downloadArticle(number)
                        }
                    }
                }
                articleDao.insert(articles)
            }
        } catch (e: IOException) {
            Timber.e(e)
        }
    }

    private fun getImageUrlFromElement(e: Element) =
        // Usually it's only the path, but sometimes it's also the full url or http instead of https,
        // so extract the path here first just in case
        "https://what-if.xkcd.com${Uri.parse(e.attr("src")).path}"

    override suspend fun downloadArticle(number: Int) {
        withContext(Dispatchers.IO) {
            try {
                val dir = File(prefHelper.getOfflinePath(context).absolutePath + OFFLINE_WHATIF_PATH + number.toString())
                dir.mkdirs()
                val doc = Jsoup.connect("https://what-if.xkcd.com/$number").get()
                val writer = BufferedWriter(FileWriter(File(dir,  "$number.html")))
                writer.write(doc.outerHtml())
                writer.close()

                //download images
                doc.select(".illustration").mapIndexed { index, e ->
                    try {
                        GlideApp.with(context)
                            .asBitmap()
                            .load(getImageUrlFromElement(e))
                            .submit()
                            .get()?.let { bitmap ->
                                val fos = FileOutputStream(File(dir, "${index + 1}.png"))
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                                fos.flush()
                                fos.close()
                            }
                    } catch (e2: Exception) {
                        Timber.e(e2, "While downloading image #${index} for article $number element ${e}")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "At article $number")
            }
        }
    }

    override val downloadAllArticles: Flow<ProgressStatus> = flow {
        // In theory offline mode could be enabled before the whatif fragment has ever been shown
        updateDatabase()

        var max: Int
        articleDao.getArticlesSuspend()
            .also { max = it.size }
            .map {
                flow {
                    emit(downloadArticle(it.number))
                }
            }.merge()
            .collectIndexed { index, _ ->
                emit(ProgressStatus.SetProgress(index + 1, max))
            }
    }

    override val downloadArchiveImages: Flow<ProgressStatus> = flow {
        try {
            var max: Int
            val dir = File(prefHelper.getOfflinePath(context).absolutePath + OFFLINE_WHATIF_OVERVIEW_PATH)
            dir.mkdirs()
            val doc = Jsoup.connect("https://what-if.xkcd.com/archive/")
                .userAgent("Mozilla/5.0 (Windows NT 6.1; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/32.0.1700.19 Safari/537.36")
                .get()
            doc.select("img.archive-image")
                .also { max = it.size }
                .mapIndexed { index, element ->
                    flow {
                        emit(GlideApp.with(context)
                            .asBitmap()
                            .load(getImageUrlFromElement(element))
                            .submit()
                            .get()?.let { bitmap ->
                                val fos = FileOutputStream(File(dir, "${index + 1}.png"))
                                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                                fos.flush()
                                fos.close()
                            })
                    }.catch { Timber.e(it, "At archive image ${index+1}") }
                }.merge()
                .collectIndexed { index, _ ->
                    emit(ProgressStatus.SetProgress(index + 1, max))
                }
        } catch (e: Exception) {
            Timber.e(e, "While downloading archive images")
        }
    }

    override suspend fun getRedditThread(article: Article) =
        redditSearchApi.search(article.title)?.url?.let { Uri.parse(it) }

    override suspend fun setFavorite(number: Int, favorite: Boolean) {
        articleDao.setFavorite(number, favorite)
    }

    override suspend fun setRead(number: Int, read: Boolean) {
        articleDao.setRead(number, read)
    }

    override suspend fun setAllRead() {
        articleDao.setAllRead()
    }

    override suspend fun setAllUnread() {
        articleDao.setAllUnread()
    }

    override suspend fun searchArticles(query: String): List<Article> = articleDao.searchArticlesByTitle(query)

    override suspend fun loadArticle(number: Int): LoadedArticle? {
        val article = articleDao.getArticle(number) ?: return null
        articleDao.setRead(number, true)

        val doc = withContext(Dispatchers.IO) {
            if (!prefHelper.fullOfflineWhatIf()) {
                Jsoup.parse(okHttpClient.newCall(
                    Request.Builder()
                    .url("https://what-if.xkcd.com/$number")
                    .build()
                ).await().body?.string())
            } else {
                val dir = File(prefHelper.getOfflinePath(context).absolutePath
                        + OFFLINE_WHATIF_PATH + number)
                val file = File(dir, "$number.html")
                Jsoup.parse(file, "UTF-8")
            }
        }

        //append custom css
        doc.head().getElementsByTag("link").remove()
        if (themePrefs.amoledThemeEnabled()) {
            if (themePrefs.invertColors(false)) {
                doc.head().appendElement("link").attr("rel", "stylesheet").attr("type", "text/css")
                    .attr("href", "amoled_invert.css")
            } else {
                doc.head().appendElement("link").attr("rel", "stylesheet").attr("type", "text/css")
                    .attr("href", "amoled.css")
            }
        } else if (themePrefs.nightThemeEnabled()) {
            if (themePrefs.invertColors(false)) {
                doc.head().appendElement("link").attr("rel", "stylesheet").attr("type", "text/css")
                    .attr("href", "night_invert.css")
            } else {
                doc.head().appendElement("link").attr("rel", "stylesheet").attr("type", "text/css")
                    .attr("href", "night.css")
            }
        } else {
            doc.head().appendElement("link").attr("rel", "stylesheet").attr("type", "text/css")
                .attr("href", "style.css")
        }

        //fix the image links
        var count = 1
        val base = prefHelper.getOfflinePath(context).absolutePath
        for (e in doc.select(".illustration")) {
            if (!prefHelper.fullOfflineWhatIf()) {
                e.attr("src", getImageUrlFromElement(e))
            } else {
                val path = "file://$base/what if/$number/$count.png"
                e.attr("src", path)
            }
            e.attr("onclick", "img.performClick(title);")
            count++
        }

        //fix footnotes and math scripts
        if (!prefHelper.fullOfflineWhatIf()) {
            doc.select("script[src]").first()
                .attr("src", "https://cdn.mathjax.org/mathjax/latest/MathJax.js")
        } else {
            doc.select("script[src]").first().attr("src", "MathJax.js")
        }

        //remove header, footer, nav buttons
        doc.getElementById("header-wrapper").remove()
        doc.select("nav").remove()
        doc.getElementById("footer-wrapper").remove()

        //remove title
        doc.select("h1").remove()

        val refs = doc.select(".ref").map { it.select(".refbody").html() }

        doc.select(".ref").mapIndexed { n, element ->
            element.select(".refnum")
                .attr("onclick", "ref.performClick(\"${n}\")")
            element.select(".refbody").remove()
        }

        return LoadedArticle(article, doc.html(), refs)
    }

    override suspend fun deleteAllOfflineArticles() {
        File(prefHelper.getOfflinePath(context).absolutePath + OFFLINE_WHATIF_PATH).deleteRecursively()
    }
}

@Module
@InstallIn(ViewModelComponent::class, SingletonComponent::class)
class ArticleRepositoryModule {
    @Provides
    fun provideArticleRepository(
        @ApplicationContext context: Context,
        prefHelper: PrefHelper,
        themePrefs: ThemePrefs,
        articleDao: ArticleDao,
        okHttpClient: OkHttpClient,
        redditSearchApi: RedditSearchApi,
    ): ArticleRepository = ArticleRepositoryImpl(context, prefHelper, themePrefs, articleDao, okHttpClient, redditSearchApi)
}