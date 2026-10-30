package dev.helfferich.judgelies.dataset

import java.util.Locale

import kotlin.math.abs

import dev.helfferich.judgelies.support.Hashing

/**
 * Deterministic answer variants for the planted-error arm.
 *
 * Numeric errors prefer the last four-digit year, otherwise the first number
 * outside parentheses, and they only keep a candidate whose new number is
 * absent from the passage. Entity swaps replace a multi-word proper name
 * with another one from the same passage.
 */
object VariantRules {
    const val MIN_CONTEXT_CHARS = 480
    const val MAX_CONTEXT_CHARS = 1400
    const val MIN_ANSWER_CHARS = 3
    const val MAX_ANSWER_CHARS = 42
    const val NUMERIC_TARGET = 32
    const val ENTITY_TARGET = 18

    const val SELECTION_NOTE =
        "SQuAD 1.1 dev passages of 480–1400 characters. 32 questions whose gold answer " +
            "can be made wrong by a minimal numeric edit that does not occur in the passage, " +
            "and 18 questions whose gold answer is a multi-word proper name swapped with " +
            "another name from the same passage. Titles are spread across the dev set. " +
            "The unsupported variant keeps the gold answer and replaces the passage with " +
            "the first other-article paragraph that does not contain that answer or its " +
            "salient numbers (3 digits or more)."

    private val numberRe =
        Regex("""\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?(?:st|nd|rd|th|s)?""")
    private val yearRe = Regex("""1[0-9]{3}|20[0-9]{2}""")
    private val colonRefRe = Regex("""\d\s*:\s*\d""")
    private val phraseRe = Regex("""\b([A-Z][a-z]{3,}(?:\s+[A-Z][a-z]{3,}){1,2})\b""")
    private val nameBeforeRe = Regex(""".*[A-Z][a-z]{2,}\s+""")

    private val nonNameTokens: Set<String> = """
        Education Limited Company University Government National American British French German
        January February March April May June July August September October November December
        City State World War North South East West President General Major Public Social History
        Century Group Series Service System Department Council Church Empire Republic Kingdom
        Army Navy Force Theory Board Court House Park Range International Federal Royal Great
        United Soviet Western Eastern Central Roman Catholic Orthodox Anglican Mongolian Saudi
        Arabian Nobel Prize Queen King Prince Duke Fort Lake Mount Gulf Coast River Street Hotel
        Museum Library College School Institute Program Project Town Hope Death Basin Ground
        League Laws Asia Africa Europe America Once Though Chair Design Senior Counterfeit
        Eternal Supplemental Welfare Dynastic Dramatic Principal Women Natural Wonders Advanced
        Study Bloody Creek Nutrition Assistance Cash Card Name Arts Mutual Lunar Teachers
        Reformer Prayerbook Blue Persian Cricket Football Australian Methodist Science Garden
        Castle Cars Pilot Module Radio Country Administration Revolution Industrial States
        Scottish English Canadian Canadians Ancient Victorian Newcastle Media Network Control Power Light
        Most Many Some Other Several Various Skating Minister Southern Northern
        Speed Station People Three First Second Third
    """.trim().split(Regex("""\s+""")).toSet()

    fun numericVariant(answer: String, context: String): Pair<String, String>? {
        if (answer.count { it == '(' } != answer.count { it == ')' }) return null
        if (colonRefRe.containsMatchIn(answer)) return null
        val spans = parenthesisSpans(answer)
        val matches = numberRe.findAll(answer)
            .filter { match -> spans.none { span -> match.range.first in span } }
            .toList()
        if (matches.isEmpty()) return null
        // The last year is the fact more often than an earlier one, which is often a citation.
        // Parenthetical numbers were already dropped, so a unit conversion in parentheses is not the edit.
        val years = matches.filter { yearRe.matches(it.value) }
        val ordered = years.asReversed() + matches.filter { it !in years }
        for (match in ordered) {
            for ((text, kind) in candidates(answer, match)) {
                // Keep the edit only when the new string is absent from the passage, does not
                // duplicate a number inside the answer, and does not introduce a digit sequence
                // the passage already contains.
                if (text.equals(answer, ignoreCase = true)) continue
                if (context.contains(text, ignoreCase = true)) continue
                if (hasNewDuplicate(answer, text)) continue
                val introduced = introducedDigits(answer, text)
                if (introduced.any { containsBounded(context, it) }) continue
                return text to kind
            }
        }
        return null
    }

    fun entityVariant(answer: String, context: String): String? {
        if (!okPhrase(answer) || !context.contains(answer)) return null
        val candidates = mutableListOf<String>()
        for (match in phraseRe.findAll(context)) {
            val phrase = match.groupValues[1]
            if (!okPhrase(phrase)) continue
            val left = phrase.lowercase()
            val right = answer.lowercase()
            if (left == right || left.contains(right) || right.contains(left)) continue
            if (left.split(" ").toSet().intersect(right.split(" ").toSet()).isNotEmpty()) continue
            if (phrase.split(" ").size != answer.split(" ").size) continue
            val sharedPrefix = right.zip(left).takeWhile { (a, b) -> a == b }.count()
            if (sharedPrefix >= 4) continue
            val after = context.substring(match.range.last + 1)
            if (continuesName(after)) continue
            val before = context.substring(0, match.range.first)
            if (nameBeforeRe.matches(before)) continue
            candidates.add(phrase)
        }
        return candidates
            .distinctBy { it.lowercase() }
            .sortedWith(compareBy({ abs(it.length - answer.length) }, { it.lowercase() }))
            .firstOrNull()
    }

    fun salientNumbers(answer: String): List<String> =
        numberRe.findAll(answer)
            .map { it.value.filter(Char::isDigit) }
            .filter { it.length >= 3 }
            .distinct()
            .toList()

    fun containsBounded(text: String, digits: String): Boolean {
        if (digits.isEmpty()) return false
        return Regex("(?<![0-9])${Regex.escape(digits)}(?![0-9])").containsMatchIn(text)
    }

    fun buildDataset(
        dump: SquadDump,
        numericTarget: Int = NUMERIC_TARGET,
        entityTarget: Int = ENTITY_TARGET,
    ): DatasetFile {
        require(numericTarget > 0 && entityTarget > 0)
        val paragraphs = mutableListOf<Para>()
        val numeric = linkedMapOf<String, MutableList<RawHit>>()
        val entity = linkedMapOf<String, MutableList<RawHit>>()
        dump.data.forEach { article ->
            article.paragraphs.forEachIndexed { index, paragraph ->
                val context = paragraph.context
                if (context.length !in MIN_CONTEXT_CHARS..MAX_CONTEXT_CHARS) return@forEachIndexed
                paragraphs += Para(article.title, index, context)
                paragraph.qas.forEach { qa ->
                    val answer = qa.answers.firstOrNull()?.text?.trim().orEmpty()
                    if (answer.length !in MIN_ANSWER_CHARS..MAX_ANSWER_CHARS) return@forEach
                    if (!context.contains(answer)) return@forEach
                    val numericHit = numericVariant(answer, context)
                    if (numericHit != null) {
                        numeric.getOrPut(article.title) { mutableListOf() } += RawHit(
                            id = qa.id,
                            title = article.title,
                            question = qa.question.trim(),
                            answer = answer,
                            context = context,
                            subtle = numericHit.first,
                            subtleKind = numericHit.second,
                        )
                        return@forEach
                    }
                    val swapped = entityVariant(answer, context) ?: return@forEach
                    entity.getOrPut(article.title) { mutableListOf() } += RawHit(
                        id = qa.id,
                        title = article.title,
                        question = qa.question.trim(),
                        answer = answer,
                        context = context,
                        subtle = swapped,
                        subtleKind = "entity_swap",
                    )
                }
            }
        }
        val used = mutableSetOf<String>()
        val numericPicked = pick(numeric, paragraphs, numericTarget, used)
        val entityPicked = pick(entity, paragraphs, entityTarget, used)
        if (numericPicked.size < numericTarget || entityPicked.size < entityTarget) {
            error(
                "Could only build ${numericPicked.size} numeric and ${entityPicked.size} entity items " +
                    "(wanted $numericTarget and $entityTarget).",
            )
        }
        val items = (numericPicked + entityPicked).map { (hit, donor) ->
            EvalItem(
                id = hit.id,
                title = hit.title,
                question = hit.question,
                goldAnswer = hit.answer,
                subtleKind = hit.subtleKind,
                unsupportedDonorTitle = donor.title,
                variants = listOf(
                    AnswerVariant(VariantKind.CORRECT, hit.answer, hit.context),
                    AnswerVariant(VariantKind.SUBTLE_ERROR, hit.subtle, hit.context),
                    AnswerVariant(VariantKind.UNSUPPORTED, hit.answer, donor.context),
                ),
            )
        }
        return DatasetFile(
            dataset = DatasetInfo(
                name = "SQuAD 1.1 dev subset",
                license = "CC BY-SA 4.0",
                sourceUrl = "https://rajpurkar.github.io/SQuAD-explorer/dataset/dev-v1.1.json",
                paper = "https://aclanthology.org/D16-1264/",
                citation = "Rajpurkar, P., Zhang, J., Lopyrev, K., and Liang, P. (2016). " +
                    "SQuAD: 100,000+ Questions for Machine Comprehension of Text. EMNLP.",
                wikipediaLicense = "Wikipedia passages are CC BY-SA.",
                selection = SELECTION_NOTE,
                squadVersion = dump.version ?: "1.1",
            ),
            items = items,
        )
    }

    private fun pick(
        byTitle: Map<String, List<RawHit>>,
        paragraphs: List<Para>,
        target: Int,
        used: MutableSet<String>,
    ): List<Pair<RawHit, Para>> {
        val titles = byTitle.keys.sorted()
        val chosen = mutableListOf<Pair<RawHit, Para>>()
        var round = 0
        while (chosen.size < target && round < 8) {
            val remaining = titles.filter { title -> byTitle.getValue(title).any { it.id !in used } }
            if (remaining.isEmpty()) break
            val wave = if (round == 0) {
                spread(remaining, minOf(target - chosen.size, remaining.size))
            } else {
                remaining
            }
            for (title in wave) {
                if (chosen.size >= target) break
                val usable = firstUsable(byTitle.getValue(title), paragraphs, used) ?: continue
                used += usable.first.id
                chosen += usable
            }
            round++
        }
        return chosen
    }

    private fun firstUsable(
        hits: List<RawHit>,
        paragraphs: List<Para>,
        used: Set<String>,
    ): Pair<RawHit, Para>? {
        for (hit in hits) {
            if (hit.id in used) continue
            val donor = findDonor(paragraphs, hit) ?: continue
            return hit to donor
        }
        return null
    }

    private fun findDonor(paragraphs: List<Para>, hit: RawHit): Para? {
        if (paragraphs.isEmpty()) return null
        val numbers = salientNumbers(hit.answer)
        // Hash the question id so donors are spread through the corpus, not all taken from the first article.
        val start = Hashing.fnv1a(hit.id) % paragraphs.size
        for (offset in paragraphs.indices) {
            val para = paragraphs[(start + offset) % paragraphs.size]
            if (para.title == hit.title || para.context == hit.context) continue
            if (para.context.contains(hit.answer, ignoreCase = true)) continue
            if (numbers.any { containsBounded(para.context, it) }) continue
            return para
        }
        return null
    }

    private fun spread(titles: List<String>, count: Int): List<String> {
        if (titles.isEmpty() || count <= 0) return emptyList()
        if (count >= titles.size) return titles
        if (count == 1) return listOf(titles.first())
        val used = mutableSetOf<String>()
        val picked = mutableListOf<String>()
        for (i in 0 until count) {
            // Integer division, not half-even rounding, so the stride does not depend on the language runtime.
            val start = ((i.toLong() * (titles.size - 1)) / (count - 1)).toInt()
            for (offset in titles.indices) {
                val title = titles[(start + offset) % titles.size]
                if (title !in used) {
                    used += title
                    picked += title
                    break
                }
            }
        }
        return picked
    }

    private fun candidates(answer: String, match: MatchResult): List<Pair<String, String>> {
        val raw = match.value
        val parsed = Regex("""^(\d{1,3}(?:,\d{3})+|\d+)(\.\d+)?(st|nd|rd|th|s)?$""").matchEntire(raw)
            ?: return emptyList()
        val intPart = parsed.groupValues[1]
        val fraction = parsed.groupValues[2]
        val suffix = parsed.groupValues[3]
        val commas = ',' in intPart
        val replacements: List<Pair<String, String>> = when {
            suffix == "s" -> {
                val digits = intPart.replace(",", "")
                if (digits.length < 3 || !digits.endsWith('0')) return emptyList()
                val n = digits.toInt()
                listOf(n + 10, n - 10)
                    .filter { it > 0 }
                    .map { it.toString() + "s" to "decade" }
            }
            suffix in setOf("st", "nd", "rd", "th") -> {
                val n = intPart.toInt()
                listOf(1, -1, 2).mapNotNull { delta ->
                    val next = n + delta
                    if (next <= 0) null else next.toString() + ordinalSuffix(next) to "ordinal"
                }
            }
            fraction.isNotEmpty() -> decimalReplacements(intPart, fraction)
            else -> {
                val n = intPart.replace(",", "").toInt()
                if (n <= 0) return emptyList()
                val deltas = if (yearRe.matches(intPart)) listOf(1, -1, 2, -2, 10, -10) else listOf(1, -1, 2, 10, -2)
                val kind = if (yearRe.matches(intPart)) "year" else "integer"
                deltas.mapNotNull { delta ->
                    val next = n + delta
                    if (next <= 0) null else formatInt(next, commas) to kind
                }
            }
        }
        return replacements.map { (token, kind) ->
            answer.replaceRange(match.range, token) to kind
        }
    }

    private fun decimalReplacements(intPart: String, fraction: String): List<Pair<String, String>> {
        val scale = fraction.length - 1
        val digits = (intPart.replace(",", "") + fraction.removePrefix(".")).toInt()
        return listOf(1, -1, 2).mapNotNull { step ->
            val next = digits + step
            if (next < 0) null else formatDecimal(next, scale) to "decimal"
        }
    }

    private fun formatDecimal(value: Int, scale: Int): String {
        val raw = value.toString().padStart(scale + 1, '0')
        val cut = raw.length - scale
        return raw.substring(0, cut) + "." + raw.substring(cut)
    }

    private fun formatInt(value: Int, commas: Boolean): String =
        if (commas) String.format(Locale.US, "%,d", value) else value.toString()

    private fun ordinalSuffix(n: Int): String {
        if (n % 100 in 11..13) return "th"
        return when (n % 10) {
            1 -> "st"
            2 -> "nd"
            3 -> "rd"
            else -> "th"
        }
    }

    private fun parenthesisSpans(text: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        // Track outer (...) spans so parenthetical conversions are not picked as the numeric edit.
        var depth = 0
        var start = -1
        text.forEachIndexed { index, char ->
            when (char) {
                '(' -> {
                    if (depth == 0) start = index
                    depth++
                }
                ')' -> if (depth > 0) {
                    depth--
                    if (depth == 0 && start >= 0) {
                        spans += start..index
                        start = -1
                    }
                }
            }
        }
        return spans
    }

    private fun digitCores(text: String): List<String> =
        numberRe.findAll(text).map { it.value.filter(Char::isDigit) }.filter { it.isNotEmpty() }.toList()

    private fun introducedDigits(before: String, after: String): List<String> {
        val leftover = digitCores(before).toMutableList()
        val introduced = mutableListOf<String>()
        for (core in digitCores(after)) {
            val index = leftover.indexOf(core)
            if (index >= 0) leftover.removeAt(index) else introduced += core
        }
        return introduced
    }

    private fun hasNewDuplicate(before: String, after: String): Boolean {
        val old = digitCores(before).groupingBy { it }.eachCount()
        val new = digitCores(after).groupingBy { it }.eachCount()
        return new.any { (core, count) -> count >= 2 && count > (old[core] ?: 0) }
    }

    private fun okPhrase(phrase: String): Boolean {
        if (!phraseRe.matches(phrase)) return false
        return phrase.split(" ").none { it in nonNameTokens || it.length < 4 }
    }

    private fun continuesName(after: String): Boolean {
        if (after.isEmpty() || !after[0].isWhitespace()) return false
        val rest = after.trimStart()
        val first = rest.firstOrNull() ?: return false
        val second = rest.drop(1).firstOrNull() ?: return false
        return first.isUpperCase() && second.isLowerCase()
    }

    private data class Para(val title: String, val index: Int, val context: String)

    private data class RawHit(
        val id: String,
        val title: String,
        val question: String,
        val answer: String,
        val context: String,
        val subtle: String,
        val subtleKind: String,
    )
}
