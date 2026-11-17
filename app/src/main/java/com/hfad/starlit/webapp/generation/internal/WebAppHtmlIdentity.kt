package com.hfad.starlit.webapp.generation.internal

import com.hfad.starlit.webapp.generation.AppGenerator
import java.util.UUID

internal object WebAppHtmlIdentity {
    private const val WEB_APP_ID_NAME = "starlit-webapp-id"
    internal const val RUNTIME_GUARD_START = "<!-- starlit-webapp-runtime-guard:start -->"
    internal const val RUNTIME_GUARD_END = "<!-- starlit-webapp-runtime-guard:end -->"
    private val META_TAG_REGEX = Regex("<meta\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HEAD_TAG_REGEX = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val HEAD_END_TAG_REGEX = Regex("</head\\s*>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val BODY_TAG_REGEX = Regex("<body(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val WEB_APP_ID_NAME_REGEX = Regex("\\bname\\s*=\\s*['\"]$WEB_APP_ID_NAME['\"]", RegexOption.IGNORE_CASE)
    private val META_CONTENT_REGEX = Regex("\\bcontent\\s*=\\s*['\"]([^'\"]+)['\"]", RegexOption.IGNORE_CASE)
    private val META_CHARSET_REGEX = Regex("\\bcharset\\s*=", RegexOption.IGNORE_CASE)
    fun ensureWebAppIdentity(htmlContent: String): String {
        val withIdentity = ensureWebAppId(htmlContent)
        return ensureWebAppRuntimeGuard(withIdentity)
    }

    fun withStarlitWebAppUserAgent(userAgent: String?): String {
        val baseUserAgent = userAgent.orEmpty()
        return if (baseUserAgent.contains(AppGenerator.WEB_APP_USER_AGENT_TOKEN)) {
            baseUserAgent
        } else {
            "$baseUserAgent ${AppGenerator.WEB_APP_USER_AGENT_TOKEN}".trim()
        }
    }

    private fun ensureWebAppId(htmlContent: String): String {
        if (extractWebAppIdentity(htmlContent) != null) return htmlContent

        val metaTag = """<meta name="$WEB_APP_ID_NAME" content="${UUID.randomUUID()}">"""
        return insertIntoHead(htmlContent, metaTag)
    }

    private fun ensureWebAppRuntimeGuard(htmlContent: String): String {
        if (htmlContent.contains(RUNTIME_GUARD_START)) return htmlContent
        return insertIntoHead(htmlContent, WEB_APP_RUNTIME_GUARD)
    }

    private fun insertIntoHead(htmlContent: String, block: String): String {
        val indentedBlock = block.replace("\n", "\n    ")
        val headMatch = HEAD_TAG_REGEX.find(htmlContent)
        if (headMatch != null) {
            val insertAt = insertionPointInHead(htmlContent, headMatch.range.last + 1)
            return htmlContent.substring(0, insertAt) +
                    "\n    $indentedBlock" +
                    htmlContent.substring(insertAt)
        }

        val headBlock = "<head>\n    $indentedBlock\n</head>\n"
        val bodyMatch = BODY_TAG_REGEX.find(htmlContent)
        if (bodyMatch != null) {
            return htmlContent.substring(0, bodyMatch.range.first) +
                    headBlock +
                    htmlContent.substring(bodyMatch.range.first)
        }

        val htmlMatch = HTML_TAG_REGEX.find(htmlContent)
        if (htmlMatch != null) {
            val insertAt = htmlMatch.range.last + 1
            return htmlContent.substring(0, insertAt) +
                    "\n$headBlock" +
                    htmlContent.substring(insertAt)
        }

        return "$block\n$htmlContent"
    }

    private fun insertionPointInHead(htmlContent: String, headContentStart: Int): Int {
        val headEnd = HEAD_END_TAG_REGEX.find(htmlContent, headContentStart)?.range?.first
            ?: htmlContent.length
        val charsetMeta = META_TAG_REGEX.findAll(htmlContent, headContentStart)
            .firstOrNull { match ->
                match.range.first < headEnd && META_CHARSET_REGEX.containsMatchIn(match.value)
            }
        return charsetMeta?.range?.last?.plus(1) ?: headContentStart
    }

    fun extractWebAppIdentity(htmlContent: String): String? {
        return META_TAG_REGEX.findAll(htmlContent)
            .firstNotNullOfOrNull { match ->
                val tag = match.value
                if (!WEB_APP_ID_NAME_REGEX.containsMatchIn(tag)) return@firstNotNullOfOrNull null
                META_CONTENT_REGEX.find(tag)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            }
    }

    private val WEB_APP_RUNTIME_GUARD = """
        $RUNTIME_GUARD_START
        <meta name="starlit-webapp-runtime" content="1">
        <script>
        (function () {
            var bridgeAllowed = false;
            try {
                bridgeAllowed = !!(
                    window.StarlitApp &&
                    typeof window.StarlitApp.isStarlitApp === "function" &&
                    window.StarlitApp.isStarlitApp()
                );
            } catch (error) {
                bridgeAllowed = false;
            }

            var userAgentAllowed = (navigator.userAgent || "").indexOf("StarlitApp/1") !== -1;
            if (bridgeAllowed || userAgentAllowed) {
                window.__STARLIT_WEBAPP_ALLOWED__ = true;
                // 把每个 StarlitApp_<toolName> bridge 挂到 window.StarlitApp.<toolName> 下，
                // 让生成的网页写 window.StarlitApp.toast.toastShow(...) 这种自然形式。
                try {
                    if (window.StarlitApp && typeof window.StarlitApp.getToolNames === "function") {
                        var raw = window.StarlitApp.getToolNames();
                        var names = JSON.parse(raw);
                        for (var i = 0; i < names.length; i++) {
                            var bridge = window["StarlitApp_" + names[i]];
                            if (bridge) {
                                window.StarlitApp[names[i]] = bridge;
                            }
                        }
                    }
                } catch (error) { /* 别名表搭建失败时，调用方 try/catch 自行兜底 */ }
                return;
            }

            window.__STARLIT_WEBAPP_ALLOWED__ = false;
            var lockId = "starlit-open-gate";
            var styleId = "starlit-open-gate-style";
            var gateMarkup = '<section class="starlit-open-card"><h1 class="starlit-open-title">请用STARLITApp打开</h1><p class="starlit-open-text">这是由Starlit生成的网页应用，只能在STARLITApp内运行。</p></section>';
            var rendering = false;

            function installStyle() {
                if (document.getElementById(styleId)) return;
                var style = document.createElement("style");
                style.id = styleId;
                style.textContent = [
                    'html[data-starlit-locked="true"],html[data-starlit-locked="true"] body{margin:0!important;min-height:100vh!important;background:#f7f8fa!important;color:#141414!important;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif!important;}',
                    'html[data-starlit-locked="true"] body>*:not(#starlit-open-gate){display:none!important;}',
                    '#starlit-open-gate{box-sizing:border-box!important;display:flex!important;min-height:100vh!important;align-items:center!important;justify-content:center!important;padding:28px!important;text-align:center!important;background:#f7f8fa!important;}',
                    '#starlit-open-gate .starlit-open-card{box-sizing:border-box!important;width:min(100%,360px)!important;border:1px solid #e2e6ea!important;border-radius:18px!important;background:#fff!important;padding:28px 22px!important;box-shadow:0 16px 40px rgba(20,28,38,.12)!important;}',
                    '#starlit-open-gate .starlit-open-title{margin:0 0 10px!important;font-size:22px!important;line-height:1.25!important;font-weight:800!important;color:#111827!important;}',
                    '#starlit-open-gate .starlit-open-text{margin:0!important;font-size:15px!important;line-height:1.7!important;color:#5b6472!important;}'
                ].join("");
                (document.head || document.documentElement).appendChild(style);
            }

            function renderGate() {
                if (rendering) return;
                rendering = true;
                document.documentElement.setAttribute("data-starlit-locked", "true");
                installStyle();
                if (!document.body) {
                    rendering = false;
                    return;
                }
                var gate = document.getElementById(lockId);
                if (!gate) {
                    gate = document.createElement("main");
                    gate.id = lockId;
                }
                if (document.body.children.length !== 1 || document.body.firstElementChild !== gate) {
                    document.body.innerHTML = "";
                    document.body.appendChild(gate);
                }
                if (gate.innerHTML !== gateMarkup) {
                    gate.innerHTML = gateMarkup;
                }
                rendering = false;
            }

            installStyle();
            renderGate();
            if (document.readyState === "loading") {
                document.addEventListener("DOMContentLoaded", renderGate, { once: true });
            }
            if (window.MutationObserver) {
                new MutationObserver(renderGate).observe(document.documentElement, {
                    childList: true,
                    subtree: true
                });
            }
            window.setInterval(renderGate, 500);
        })();
        </script>
        $RUNTIME_GUARD_END
    """.trimIndent()

}
