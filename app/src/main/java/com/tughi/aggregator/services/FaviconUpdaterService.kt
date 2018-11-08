package com.tughi.aggregator.services

import android.app.IntentService
import android.content.Intent
import android.graphics.BitmapFactory
import com.tughi.aggregator.App
import com.tughi.aggregator.AppDatabase
import com.tughi.aggregator.utilities.Http
import com.tughi.aggregator.utilities.toAbsoluteUrl
import okhttp3.Request
import java.util.regex.Pattern

class FaviconUpdaterService : IntentService("FaviconUpdater") {

    companion object {
        const val EXTRA_FEED_ID = "feed_id"

        fun start(feedId: Long) {
            val context = App.instance
            context.startService(Intent(context, FaviconUpdaterService::class.java).putExtra(EXTRA_FEED_ID, feedId))
        }
    }

    override fun onHandleIntent(intent: Intent?) {
        val feedId = intent?.getLongExtra(EXTRA_FEED_ID, 0) ?: return
        val feedDao = AppDatabase.instance.feedDao()
        val feed = feedDao.queryFeed(feedId)
        val feedLink = feed.link ?: return

        var icon = detectWebsiteFavicon(feedLink)
        if (icon == null) {
            // TODO: try "/favicon.ico"
            icon = detectWebsiteFavicon(feedLink)
        }

        if (icon?.content != null) {
            feedDao.updateFeed(id = feedId, faviconUrl = icon.url, faviconContent = icon.content!!)
        }
    }

    private val linkPattern = Pattern.compile("<(body)|<link([^>]+)>")
    private val relIconPattern = Pattern.compile("rel\\s*=\\s*['\"]([sS]hortcut [iI]con|icon)['\"]")
    private val hrefPattern = Pattern.compile("href\\s*=\\s*['\"]([^'\"]+)['\"]")
    private val sizesPattern = Pattern.compile("sizes\\s*=\\s*['\"]([^'\"]+)['\"]")

    private fun detectWebsiteFavicon(feedLink: String): Icon? {
        val request = Request.Builder().url(feedLink).build()
        val response = Http.client.newCall(request).execute()
        if (!response.isSuccessful) {
            return null
        }
        val responseBody = response.body() ?: return null
        val responseContent = responseBody.string()

        val icons = mutableListOf<Icon>()

        // find <head> <link>s
        val linkMatcher = linkPattern.matcher(responseContent)
        while (linkMatcher.find() && linkMatcher.group(1) == null) {
            val attributes = linkMatcher.group(2)
            // is it an icon link?
            val relIconMatcher = relIconPattern.matcher(attributes)
            if (relIconMatcher.find()) {
                val shortcut = relIconMatcher.group(1).toLowerCase().startsWith("shortcut")

                val hrefMatcher = hrefPattern.matcher(attributes)
                // does the link have the required href?
                if (hrefMatcher.find()) {
                    val iconUrl = hrefMatcher.group(1).toAbsoluteUrl(response.request().url().toString())

                    if (shortcut) {
                        icons.add(0, Icon(iconUrl))
                    } else {
                        // check size
                        val sizesMatcher = sizesPattern.matcher(attributes)
                        if (sizesMatcher.find()) {
                            val sizes = sizesMatcher.group(1).trim().toLowerCase()
                            if (sizes == "any" || sizes.split(" ".toRegex()).size > 1) {
                                // multiple sizes are not supported
                                continue
                            }
                        }

                        icons.add(Icon(iconUrl))
                    }
                }
            }
        }

        // download first available icon
        for (icon in icons) {
            val iconRequest = Request.Builder().url(icon.url).build()
            val iconResponse = Http.client.newCall(iconRequest).execute()
            if (iconResponse.isSuccessful) {
                val iconResponseBody = iconResponse.body() ?: continue
                val iconData = iconResponseBody.bytes() ?: continue

                // validate icon
                val options = BitmapFactory.Options()
                options.inJustDecodeBounds = true
                BitmapFactory.decodeByteArray(iconData, 0, iconData.size, options)
                if (options.outWidth > 0) {
                    return icon.copy(content = iconData)
                }
            }
        }

        return null
    }

    private data class Icon(val url: String, val content: ByteArray? = null) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Icon

            if (url != other.url) return false

            return true
        }

        override fun hashCode(): Int {
            return url.hashCode()
        }
    }

}