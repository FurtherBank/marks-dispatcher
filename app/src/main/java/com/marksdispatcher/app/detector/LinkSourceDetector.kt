package com.marksdispatcher.app.detector

import com.marksdispatcher.app.model.LinkSource
import java.util.regex.Pattern

object LinkSourceDetector {

    private val urlPattern = Pattern.compile(
        "(?i)\\b((?:https?://|www\\.)[^\\s<>\"']+)"
    )

    private data class SourceRule(
        val id: String,
        val label: String,
        val hostPatterns: List<Regex>
    )

    private val rules = listOf(
        SourceRule(
            id = "bilibili",
            label = "哔哩哔哩",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)bilibili\.com$"""),
                Regex("""(?i)(^|\.)b23\.tv$""")
            )
        ),
        SourceRule(
            id = "youtube",
            label = "YouTube",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)youtube\.com$"""),
                Regex("""(?i)(^|\.)youtu\.be$""")
            )
        ),
        SourceRule(
            id = "weibo",
            label = "微博",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)weibo\.(com|cn)$"""),
                Regex("""(?i)(^|\.)weibo\.com\.cn$""")
            )
        ),
        SourceRule(
            id = "zhihu",
            label = "知乎",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)zhihu\.com$"""),
                Regex("""(?i)(^|\.)zhuanlan\.zhihu\.com$""")
            )
        ),
        SourceRule(
            id = "douyin",
            label = "抖音",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)douyin\.com$"""),
                Regex("""(?i)(^|\.)iesdouyin\.com$""")
            )
        ),
        SourceRule(
            id = "xiaohongshu",
            label = "小红书",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)xiaohongshu\.com$"""),
                Regex("""(?i)(^|\.)xhslink\.com$""")
            )
        ),
        SourceRule(
            id = "twitter",
            label = "X / Twitter",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)twitter\.com$"""),
                Regex("""(?i)(^|\.)x\.com$""")
            )
        ),
        SourceRule(
            id = "wechat",
            label = "微信公众号",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)mp\.weixin\.qq\.com$""")
            )
        ),
        SourceRule(
            id = "netease_music",
            label = "网易云音乐",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)music\.163\.com$""")
            )
        ),
        SourceRule(
            id = "spotify",
            label = "Spotify",
            hostPatterns = listOf(
                Regex("""(?i)(^|\.)spotify\.com$"""),
                Regex("""(?i)(^|\.)open\.spotify\.com$""")
            )
        )
    )

    private val unknownSource = LinkSource(id = "unknown", label = "未知来源")

    fun extractFirstUrl(text: String): String? {
        val matcher = urlPattern.matcher(text)
        return if (matcher.find()) {
            normalizeUrl(matcher.group(1) ?: return null)
        } else {
            null
        }
    }

    fun detectSource(url: String): LinkSource {
        val host = extractHost(url) ?: return unknownSource
        for (rule in rules) {
            if (rule.hostPatterns.any { it.containsMatchIn(host) }) {
                return LinkSource(id = rule.id, label = rule.label)
            }
        }
        return LinkSource(id = "web", label = "网页链接")
    }

    fun analyzeClipboardText(text: String): Pair<String, LinkSource>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val url = extractFirstUrl(trimmed) ?: return null
        val source = detectSource(url)
        return url to source
    }

    private fun normalizeUrl(raw: String): String {
        var url = raw.trim().trimEnd(',', '.', ';', ')', ']', '}', '"', '\'')
        if (url.startsWith("www.", ignoreCase = true)) {
            url = "https://$url"
        }
        return url
    }

    private fun extractHost(url: String): String? {
        return try {
            val normalized = if (url.startsWith("http", ignoreCase = true)) {
                url
            } else {
                "https://$url"
            }
            val uri = java.net.URI(normalized)
            uri.host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
