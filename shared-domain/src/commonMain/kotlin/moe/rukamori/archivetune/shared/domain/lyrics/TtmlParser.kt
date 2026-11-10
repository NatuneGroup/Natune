package moe.rukamori.archivetune.shared.domain.lyrics

/**
 * TTML reader with the DOM semantics of the Android BetterLyrics parser: document-order element
 * traversal, suffix tag matching, qualified attribute names, whitespace text-node handling, and a
 * fallback pass over every paragraph when the division pass finds none. Any failure yields an empty list.
 */
internal object TtmlParser {
    internal data class ParsedLine(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val words: List<ParsedWord>,
        val isBackground: Boolean = false,
        val agent: String? = null,
        val providerRomanizedText: String? = null,
        val providerRomanizedWords: List<String>? = null,
        val providerRomanizedLanguage: String? = null,
        val providerTranslationText: String? = null,
    )

    internal data class ParsedWord(
        val text: String,
        val startTime: Double,
        val endTime: Double,
        val isBackground: Boolean = false,
    )

    private class TimingContext(
        val tickRate: Double,
        val frameRate: Double,
    )

    private data class ParsedTransliteration(
        val text: String,
        val words: List<String>,
        val language: String?,
    )

    private val whitespaceRegex = Regex("\\s+")
    private val offsetRegex = Regex("""^([0-9]+(?:\.[0-9]+)?)(h|ms|m|s|f|t)$""", RegexOption.IGNORE_CASE)

    fun parseTtml(ttml: String): List<ParsedLine> =
        try {
            parseDocument(XmlTreeParser.parse(ttml)).sortedBy { it.startTime }
        } catch (_: Exception) {
            // The caller falls back to other formats on an empty list; a malformed document is not an error.
            emptyList()
        }

    /** The document-level credits: declared writers and whether translations/transliterations are machine-made. */
    fun parseCredits(ttml: String): LyricsCredits =
        try {
            readCredits(XmlTreeParser.parse(ttml))
        } catch (_: Exception) {
            LyricsCredits()
        }

    private fun readCredits(doc: XmlDocument): LyricsCredits {
        val writers = LinkedHashSet<String>()
        var translationAutoCreated = false
        var transliterationAutoCreated = false
        for (element in doc.allElements()) {
            when {
                element.tagName.endsWith("songwriter", ignoreCase = true) -> {
                    val name =
                        element.textContent().trim().takeIf { it.isNotEmpty() }
                            ?: readAttributeBySuffix(element, "name")
                    if (!name.isNullOrEmpty()) writers.add(name)
                }
                element.tagName.endsWith("transliteration", ignoreCase = true) ->
                    transliterationAutoCreated = transliterationAutoCreated || isAutoCreated(element)
                element.tagName.endsWith("translation", ignoreCase = true) ->
                    translationAutoCreated = translationAutoCreated || isAutoCreated(element)
            }
        }
        return LyricsCredits(
            songwriters = writers.toList(),
            translationAutoCreated = translationAutoCreated,
            transliterationAutoCreated = transliterationAutoCreated,
        )
    }

    /** The element or one of its `text` children carries a truthy `automaticallyCreated` attribute. */
    private fun isAutoCreated(element: XmlElement): Boolean {
        if (hasAutoCreatedFlag(element)) return true
        return element.descendantElements().any { child ->
            child.tagName.endsWith("text", ignoreCase = true) && hasAutoCreatedFlag(child)
        }
    }

    private fun hasAutoCreatedFlag(element: XmlElement): Boolean =
        element.attributes.any { attribute ->
            val local = attribute.name.substringAfterLast(':').replace("-", "")
            local.equals("automaticallyCreated", ignoreCase = true) &&
                attribute.value.trim().let { it.equals("true", true) || it == "1" || it.equals("yes", true) }
        }

    private fun parseDocument(doc: XmlDocument): List<ParsedLine> {
        val root = doc.documentElement
        val timingContext = readTimingContext(root)
        val transliterations = parseTransliterations(root)
        val translations = parseTranslations(root)

        val lines = mutableListOf<ParsedLine>()
        for (division in doc.allElements()) {
            if (!division.tagName.endsWith("div", ignoreCase = true)) continue
            for (paragraph in division.descendantElements()) {
                if (!paragraph.tagName.endsWith("p", ignoreCase = true)) continue
                val line = parseParagraph(paragraph, timingContext, transliterations, translations)
                if (line != null) lines.add(line)
            }
        }

        if (lines.isEmpty()) {
            for (paragraph in doc.allElements()) {
                if (!paragraph.tagName.endsWith("p", ignoreCase = true)) continue
                val line = parseParagraph(paragraph, timingContext, transliterations, translations)
                if (line != null) lines.add(line)
            }
        }
        return lines
    }

    private fun parseParagraph(
        paragraph: XmlElement,
        timingContext: TimingContext,
        transliterations: Map<String, ParsedTransliteration>,
        translations: Map<String, String>,
    ): ParsedLine? {
        val begin = paragraph.getAttribute("begin")
        val end = paragraph.getAttribute("end")
        val dur = paragraph.getAttribute("dur")
        if (begin.isEmpty()) return null

        val startTime = parseTime(begin, timingContext)
        val endTime =
            when {
                end.isNotEmpty() -> parseTime(end, timingContext)
                dur.isNotEmpty() -> startTime + parseTime(dur, timingContext)
                else -> startTime + 5.0
            }

        val agent =
            normalizeTtmlAgent(
                paragraph.getAttribute("ttm:agent").takeIf { it.isNotEmpty() }
                    ?: paragraph.attributes
                        .firstOrNull { it.name.endsWith("agent", ignoreCase = true) }
                        ?.value,
            )
        val lineKey = readAttributeBySuffix(paragraph, "key")
        val transliteration = lineKey?.let { transliterations[it] }
        val translation = lineKey?.let { translations[it] }

        val words = mutableListOf<ParsedWord>()
        val lineText = StringBuilder()
        parseSpanElements(paragraph, words, lineText, startTime, endTime, false, timingContext)
        words.lastOrNull()?.let { last -> words[words.lastIndex] = last.copy(text = last.text.trimEnd()) }

        if (lineText.isEmpty()) {
            val directText = paragraph.directTextContent().trim()
            if (directText.isNotEmpty()) lineText.append(directText)
        }

        if (lineText.isEmpty()) return null
        return ParsedLine(
            text = lineText.toString().trim(),
            startTime = startTime,
            endTime = endTime,
            words = words,
            isBackground = false,
            agent = agent,
            providerRomanizedText = transliteration?.text,
            providerRomanizedWords = transliteration?.words,
            providerRomanizedLanguage = transliteration?.language,
            providerTranslationText = translation,
        )
    }

    private fun parseTransliterations(root: XmlElement): Map<String, ParsedTransliteration> {
        val latinTransliterations = linkedMapOf<String, ParsedTransliteration>()
        val fallbackTransliterations = linkedMapOf<String, ParsedTransliteration>()

        for (transliterationElement in root.descendantElements()) {
            if (!transliterationElement.tagName.endsWith("transliteration", ignoreCase = true)) continue

            val language = readAttributeBySuffix(transliterationElement, "lang")
            val target =
                if (language?.contains("Latn", ignoreCase = true) == true) {
                    latinTransliterations
                } else {
                    fallbackTransliterations
                }

            for (textElement in transliterationElement.descendantElements()) {
                if (!textElement.tagName.endsWith("text", ignoreCase = true)) continue

                val lineKey = readAttributeBySuffix(textElement, "for") ?: continue
                val parsed = parseTransliterationLine(textElement, language)
                if (parsed.text.isNotBlank() && lineKey !in target) {
                    target[lineKey] = parsed
                }
            }
        }

        // Latin-script transliterations win over the fallback for the same line.
        fallbackTransliterations.putAll(latinTransliterations)
        return fallbackTransliterations
    }

    private fun parseTranslations(root: XmlElement): Map<String, String> {
        val translations = linkedMapOf<String, String>()

        for (translationElement in root.descendantElements()) {
            if (!translationElement.tagName.endsWith("translation", ignoreCase = true)) continue

            for (textElement in translationElement.descendantElements()) {
                if (!textElement.tagName.endsWith("text", ignoreCase = true)) continue

                val lineKey = readAttributeBySuffix(textElement, "for") ?: continue
                val translatedText = normalizeProvidedRomanization(textElement.textContent()) ?: continue
                translations[lineKey] = translatedText
            }
        }

        return translations
    }

    private fun parseTransliterationLine(
        element: XmlElement,
        language: String?,
    ): ParsedTransliteration {
        val lineText = StringBuilder()
        val words = mutableListOf<String>()
        parseTransliterationNodes(element, lineText, words)

        return ParsedTransliteration(
            text = normalizeProvidedRomanization(lineText.toString()).orEmpty(),
            words = words.mapNotNull { normalizeProvidedRomanization(it) },
            language = language,
        )
    }

    private fun parseTransliterationNodes(
        element: XmlElement,
        lineText: StringBuilder,
        words: MutableList<String>,
    ) {
        for (node in element.childNodes) {
            when (node) {
                is XmlElement -> {
                    if (node.tagName.endsWith("span", ignoreCase = true)) {
                        val rawText = node.textContent()
                        lineText.append(rawText)
                        val word = normalizeProvidedRomanization(rawText)
                        if (word != null) words.add(word)
                    } else {
                        parseTransliterationNodes(node, lineText, words)
                    }
                }
                is XmlText -> lineText.append(node.data)
                else -> Unit
            }
        }
    }

    private fun normalizeProvidedRomanization(text: String): String? =
        text
            .replace(whitespaceRegex, " ")
            .trim()
            .takeIf { it.isNotEmpty() }

    private fun parseSpanElements(
        element: XmlElement,
        words: MutableList<ParsedWord>,
        lineText: StringBuilder,
        lineStartTime: Double,
        lineEndTime: Double,
        isBackground: Boolean,
        timingContext: TimingContext,
    ) {
        for (node in element.childNodes) {
            when (node) {
                is XmlElement -> {
                    if (!node.tagName.endsWith("span", ignoreCase = true)) continue

                    val role =
                        node.getAttribute("role").takeIf { it.isNotEmpty() }
                            ?: node.getAttribute("ttm:role")
                    val isBgSpan = role == "x-bg" || isBackground

                    val wordBegin = node.getAttribute("begin")
                    val wordEnd = node.getAttribute("end")
                    val wordDur = node.getAttribute("dur")
                    val hasExplicitTiming = wordBegin.isNotEmpty() && (wordEnd.isNotEmpty() || wordDur.isNotEmpty())

                    val hasNestedElements = node.hasDescendantElement()
                    if (hasNestedElements && hasDirectSpanChild(node) && !hasExplicitTiming) {
                        parseSpanElements(node, words, lineText, lineStartTime, lineEndTime, isBgSpan, timingContext)
                    } else {
                        val wordText = if (hasNestedElements) node.textContent() else node.directTextContent()
                        if (wordText.isNotEmpty()) {
                            // A timed word's text is this span's text with whitespace runs collapsed to one
                            // space, because renderers join word texts verbatim. Build line.text from that
                            // SAME collapsed string: timedLyricsPresentation locates every timed word in
                            // line.text by substring (and the x-bg split locates background words there too),
                            // so raw multiple spaces/tabs/newlines left in line.text would make a collapsed
                            // word unlocatable and drop its timing range / background sweep.
                            val spacedText = wordText.replace(whitespaceRegex, " ")
                            lineText.append(spacedText)

                            if (hasExplicitTiming) {
                                val rawWordStart = parseTime(wordBegin, timingContext)
                                val rawWordEnd =
                                    if (wordEnd.isNotEmpty()) {
                                        parseTime(wordEnd, timingContext)
                                    } else {
                                        rawWordStart + parseTime(wordDur, timingContext)
                                    }
                                val wordStartTime =
                                    normalizeChildTime(
                                        raw = rawWordStart,
                                        lineStartTime = lineStartTime,
                                        lineEndTime = lineEndTime,
                                        fallback = lineStartTime,
                                    )
                                val wordEndTime =
                                    normalizeChildTime(
                                        raw = rawWordEnd,
                                        lineStartTime = lineStartTime,
                                        lineEndTime = lineEndTime,
                                        fallback = lineEndTime,
                                    ).coerceAtLeast(wordStartTime)
                                // Renderers join word texts verbatim, so a space inside the span ("Hello ")
                                // is the only word boundary and must survive; it is kept as a trailing space.
                                if (spacedText.startsWith(' ') && words.isNotEmpty() && !words.last().text.endsWith(' ')) {
                                    val lastWord = words.last()
                                    words[words.lastIndex] = lastWord.copy(text = lastWord.text + " ")
                                }
                                val wordTextWithBoundary = spacedText.trimStart()
                                if (wordTextWithBoundary.isNotBlank()) {
                                    words.add(
                                        ParsedWord(
                                            text = wordTextWithBoundary,
                                            startTime = wordStartTime,
                                            endTime = wordEndTime,
                                            isBackground = isBgSpan,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                is XmlText -> {
                    val text = node.data
                    if (text.isNotBlank()) {
                        lineText.append(text)
                    } else if (text.isNotEmpty() && !text.contains('\n')) {
                        // A same-line gap between two spans is a real space: attach it to the previous word.
                        if (words.isNotEmpty() && !words.last().text.endsWith(" ")) {
                            lineText.append(" ")
                            val lastWord = words.last()
                            words[words.lastIndex] = lastWord.copy(text = lastWord.text + " ")
                        }
                    }
                }

                else -> Unit
            }
        }
    }

    private fun hasDirectSpanChild(element: XmlElement): Boolean =
        element.childNodes.any { it is XmlElement && it.tagName.endsWith("span", ignoreCase = true) }

    private fun normalizeChildTime(
        raw: Double?,
        lineStartTime: Double,
        lineEndTime: Double,
        fallback: Double,
    ): Double {
        if (raw == null || raw.isNaN() || raw.isInfinite()) return fallback
        val lineDuration = (lineEndTime - lineStartTime).coerceAtLeast(0.0)
        val isProbablyRelative = raw < (lineStartTime - 0.25) && raw <= (lineDuration + 1.0)
        val adjusted = if (isProbablyRelative) lineStartTime + raw else raw
        return adjusted.coerceIn(lineStartTime.coerceAtLeast(0.0), lineEndTime.coerceAtLeast(lineStartTime))
    }

    private fun readTimingContext(root: XmlElement): TimingContext {
        fun getAttrBySuffix(suffix: String): String? {
            for (attribute in root.attributes) {
                if (attribute.name.endsWith(suffix, ignoreCase = true)) {
                    val value = attribute.value.trim()
                    if (value.isNotEmpty()) return value
                }
            }
            return null
        }

        val baseFrameRate = getAttrBySuffix("frameRate")?.toDoubleOrNull() ?: 30.0
        val frameRateMultiplierRaw = getAttrBySuffix("frameRateMultiplier")
        val frameRateMultiplier =
            frameRateMultiplierRaw
                ?.split(whitespaceRegex)
                ?.mapNotNull { it.toDoubleOrNull() }
                ?.takeIf { it.size == 2 && it[1] != 0.0 }
                ?.let { it[0] / it[1] }
                ?: 1.0
        val frameRate = (baseFrameRate * frameRateMultiplier).coerceAtLeast(1.0)

        val tickRate =
            getAttrBySuffix("tickRate")?.toDoubleOrNull()
                ?: (frameRate * 1.0).coerceAtLeast(1.0)

        return TimingContext(
            tickRate = tickRate,
            frameRate = frameRate,
        )
    }

    private fun readAttributeBySuffix(
        element: XmlElement,
        suffix: String,
    ): String? {
        val directValue = element.getAttribute(suffix).takeIf { it.isNotBlank() }
        if (directValue != null) return directValue

        for (attribute in element.attributes) {
            val name = attribute.name
            if (name.equals(suffix, ignoreCase = true) || name.endsWith(":$suffix", ignoreCase = true)) {
                val value = attribute.value.trim()
                if (value.isNotEmpty()) return value
            }
        }
        return null
    }

    private fun parseTime(
        timeStr: String,
        timingContext: TimingContext,
    ): Double =
        try {
            parseTimeUnchecked(timeStr, timingContext)
        } catch (_: Exception) {
            0.0
        }

    private fun parseTimeUnchecked(
        timeStr: String,
        timingContext: TimingContext,
    ): Double {
        val raw = timeStr.trim()
        if (raw.isEmpty()) return 0.0

        offsetRegex.matchEntire(raw)?.let { match ->
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0.0
            return when (match.groupValues[2].lowercase()) {
                "h" -> value * 3600.0
                "m" -> value * 60.0
                "s" -> value
                "ms" -> value / 1000.0
                "f" -> value / timingContext.frameRate
                "t" -> value / timingContext.tickRate
                else -> value
            }
        }

        val cleanClock =
            raw
                .replace(';', ':')
                .trimEnd { it.isLetter() }

        if (cleanClock.contains(":")) {
            val parts = cleanClock.split(":")
            return when (parts.size) {
                2 -> {
                    val minutes = parts[0].toDoubleOrNull() ?: 0.0
                    val seconds = parts[1].toDoubleOrNull() ?: 0.0
                    minutes * 60.0 + seconds
                }

                3 -> {
                    val hours = parts[0].toDoubleOrNull() ?: 0.0
                    val minutes = parts[1].toDoubleOrNull() ?: 0.0
                    val seconds = parts[2].toDoubleOrNull() ?: 0.0
                    hours * 3600.0 + minutes * 60.0 + seconds
                }

                4 -> {
                    val hours = parts[0].toDoubleOrNull() ?: 0.0
                    val minutes = parts[1].toDoubleOrNull() ?: 0.0
                    val seconds = parts[2].toDoubleOrNull() ?: 0.0
                    val frames = parts[3].toDoubleOrNull() ?: 0.0
                    hours * 3600.0 + minutes * 60.0 + seconds + (frames / timingContext.frameRate)
                }

                else -> cleanClock.toDoubleOrNull() ?: 0.0
            }
        }

        return raw.toDoubleOrNull() ?: 0.0
    }
}
