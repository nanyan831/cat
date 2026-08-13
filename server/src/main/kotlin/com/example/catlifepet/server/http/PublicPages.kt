package com.example.catlifepet.server.http

internal object PublicPages {
    const val PRIVACY_PATH = "/privacy"
    private val privacyMarkdown: String by lazy {
        val stream = PublicPages::class.java.classLoader.getResourceAsStream("public/PRIVACY_POLICY.md")
            ?: error("public/PRIVACY_POLICY.md is missing from server resources.")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun privacyMarkdown(): String = privacyMarkdown

    fun privacyHtml(): String {
        val body = privacyMarkdown()
            .lineSequence()
            .joinToString("\n") { line -> line.toHtmlLine() }
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>CatLifePet 隐私政策</title>
              <style>
                :root { color-scheme: light; }
                body {
                  margin: 0;
                  background: #fff8f2;
                  color: #37251d;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
                  line-height: 1.72;
                }
                main {
                  max-width: 820px;
                  margin: 0 auto;
                  padding: 32px 18px 56px;
                }
                h1 { font-size: 28px; line-height: 1.25; margin: 0 0 18px; }
                h2 { font-size: 20px; margin: 30px 0 10px; }
                p, li { font-size: 16px; }
                ul { padding-left: 22px; }
                code {
                  background: #fff0e6;
                  border-radius: 6px;
                  padding: 2px 6px;
                }
                a { color: #d75d3f; }
              </style>
            </head>
            <body>
              <main>
                $body
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun String.toHtmlLine(): String {
        val trimmed = trim()
        if (trimmed.isEmpty()) return ""
        return when {
            trimmed.startsWith("# ") -> "<h1>${trimmed.drop(2).escapeHtml()}</h1>"
            trimmed.startsWith("## ") -> "<h2>${trimmed.drop(3).escapeHtml()}</h2>"
            trimmed.startsWith("- ") -> "<li>${trimmed.drop(2).inlineHtml()}</li>"
            else -> "<p>${trimmed.inlineHtml()}</p>"
        }
    }

    private fun String.inlineHtml(): String {
        return escapeHtml().replace(
            Regex("""https://[A-Za-z0-9./?=&_%#:-]+""")
        ) { match ->
            val url = match.value
            """<a href="$url" rel="noopener noreferrer">$url</a>"""
        }
    }

    private fun String.escapeHtml(): String = buildString(length) {
        this@escapeHtml.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }
}
