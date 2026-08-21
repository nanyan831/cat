package com.example.catlifepet.server.http

internal object PublicPages {
    const val PRIVACY_PATH = "/privacy"
    private val privacyMarkdown: String by lazy {
        val stream = PublicPages::class.java.classLoader.getResourceAsStream("public/PRIVACY_POLICY.md")
            ?: error("public/PRIVACY_POLICY.md is missing from server resources.")
        stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun privacyMarkdown(): String = privacyMarkdown

    fun homeHtml(): String {
        return pageShell(
            title = "CatLifePet 小猫桌宠",
            body = """
                <section class="hero">
                  <p class="eyebrow">个人作品记录</p>
                  <h1>CatLifePet 小猫桌宠</h1>
                  <p>这里记录一个 Android 小猫桌宠作品的开发过程、功能说明和隐私说明。</p>
                </section>
                <section>
                  <h2>关于这个作品</h2>
                  <p>CatLifePet 是我个人学习 Kotlin、Android 悬浮窗、提醒任务和简单 AI 对话时制作的小猫陪伴应用。桌宠可以悬浮在手机屏幕上，支持拖动、点击互动和生活提醒。</p>
                  <p>当前页面仅用于个人作品展示和必要说明。</p>
                </section>
                <section>
                  <h2>当前功能</h2>
                  <ul>
                    <li>小猫悬浮、拖动、贴边和点击互动。</li>
                    <li>喝水、吃饭、休息、睡觉等本地提醒。</li>
                    <li>登录后可以使用 AI 对话和聊天同步。</li>
                    <li>本地桌宠和提醒在断网时仍可继续使用。</li>
                  </ul>
                </section>
                <section>
                  <h2>隐私说明</h2>
                  <p>如果想了解数据保存、权限用途和删除方式，可以查看 <a href="/privacy">CatLifePet 隐私说明</a>。</p>
                </section>
                <section>
                  <h2>联系</h2>
                  <p>邮箱：1132994878@qq.com</p>
                </section>
            """.trimIndent()
        )
    }

    fun privacyHtml(): String {
        val body = privacyMarkdown()
            .lineSequence()
            .joinToString("\n") { line -> line.toHtmlLine() }
        return pageShell(
            title = "CatLifePet 隐私说明",
            body = body
        )
    }

    private fun pageShell(title: String, body: String): String {
        return """
            <!doctype html>
            <html lang="zh-CN">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${title.escapeHtml()}</title>
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
                .hero {
                  padding: 24px 0 10px;
                }
                .eyebrow {
                  color: #a45a43;
                  font-size: 14px;
                  margin: 0 0 8px;
                }
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
