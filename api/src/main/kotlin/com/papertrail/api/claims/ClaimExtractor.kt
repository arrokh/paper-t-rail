package com.papertrail.api.claims

import com.papertrail.api.parsing.ParsedCitationContext
import com.papertrail.api.parsing.ParsedCitationOccurrence
import com.papertrail.api.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.providers.ProviderCatalog
import com.papertrail.api.providers.ProviderTrustBoundary
import org.springframework.stereotype.Component

/** A source location is an absolute, zero-based, end-exclusive UTF-16 span. */
data class AtomicClaimCandidate(
    val text: String,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
)

data class ClaimExtractionRequest(
    val contextText: String,
    val contextStartOffset: Int,
    val occurrences: List<ParsedCitationOccurrence>,
)

interface ClaimExtractorProvider {
    val providerId: String
    val version: String
    fun extract(request: ClaimExtractionRequest): List<AtomicClaimCandidate>
}

data class CitationContextClaims(
    val contextStartOffset: Int,
    val contextEndOffset: Int,
    val claims: List<AtomicClaimCandidate>,
)

/** Resolves enabled, local, version-pinned claim extractors and validates their source spans. */
@Component
class ClaimExtractionService(
    private val providerCatalog: ProviderCatalog,
    providers: List<ClaimExtractorProvider>,
) {
    private val providersById = providers.associateBy(ClaimExtractorProvider::providerId)

    init {
        require(providersById.size == providers.size) { "Claim extractor implementations must have unique provider IDs." }
    }

    fun extract(
        providerId: String,
        version: String,
        contexts: List<ParsedCitationContext>,
    ): List<CitationContextClaims> {
        val registration = providerCatalog.requireSelectable(CLAIM_EXTRACTOR_ROLE, providerId)
        require(registration.trustBoundary == ProviderTrustBoundary.LOCAL) {
            "Only local claim extractors are supported until an external provider-call gate is configured."
        }
        require(registration.version == version) {
            "The selected claim extractor configuration changed after this Analysis Run was created."
        }
        val provider = providersById[providerId]
            ?: throw IllegalStateException("No claim extractor implementation is available for the selected provider.")
        require(provider.version == version) {
            "The selected claim extractor implementation does not match this Analysis Run provenance."
        }

        return contexts.map { context ->
            val request = ClaimExtractionRequest(context.text, context.startOffset, context.occurrences)
            val candidates = provider.extract(request)
            val deduplicated = candidates
                .groupBy { it.sourceStartOffset to it.sourceEndOffset }
                .map { (_, sameSpanCandidates) ->
                    val distinctTexts = sameSpanCandidates.map(AtomicClaimCandidate::text).distinct()
                    require(distinctTexts.size == 1) {
                        "The claim extractor returned conflicting claims for one source span."
                    }
                    sameSpanCandidates.first()
                }
                .sortedWith(compareBy(AtomicClaimCandidate::sourceStartOffset, AtomicClaimCandidate::sourceEndOffset))

            deduplicated.forEach { candidate ->
                require(candidate.text.isNotBlank()) { "The claim extractor returned an empty Atomic Claim." }
                require(candidate.sourceStartOffset >= context.startOffset &&
                    candidate.sourceEndOffset <= context.endOffset &&
                    candidate.sourceStartOffset < candidate.sourceEndOffset
                ) { "The claim extractor returned a source span outside its Citation Context." }
            }
            CitationContextClaims(context.startOffset, context.endOffset, deduplicated)
        }
    }
}

/**
 * A conservative local baseline. It removes citation callouts, splits coordinated predicates when
 * their source wording can be retained, and copies a shared subject/qualifier prefix into each
 * proposition. Ambiguous coordination is kept together rather than assigning a guessed subject.
 */
@Component
class HeuristicClaimExtractor : ClaimExtractorProvider {
    override val providerId: String = "heuristic"
    override val version: String = "v1"

    override fun extract(request: ClaimExtractionRequest): List<AtomicClaimCandidate> {
        val masked = request.contextText.toCharArray()
        request.occurrences.forEach { occurrence ->
            val start = occurrence.startOffset - request.contextStartOffset
            val end = occurrence.endOffset - request.contextStartOffset
            require(start >= 0 && end <= masked.size && start < end) {
                "Citation marker span is outside its Citation Context."
            }
            require(request.contextText.substring(start, end) == occurrence.markerText) {
                "Citation marker text does not match its Citation Context source span."
            }
            for (index in start until end) masked[index] = ' '
        }
        val source = String(masked)
        val verbs = finiteVerbs(source)
        val negationOffsets = matchStartOffsets(NEGATION, source)
        val qualifierOffsets = matchStartOffsets(TRAILING_SCOPE_QUALIFIER, source)
        val result = mutableListOf<AtomicClaimCandidate>()
        var segmentStart = 0
        var inheritedSubjectPrefix: String? = null
        var prefixCacheSegmentStart = -1
        var sharedPrefixWasComputed = false
        var cachedSharedPrefix: String? = null

        COORDINATION.findAll(source).forEach { conjunction ->
            if (!conjunction.value.equals("and", ignoreCase = true)) return@forEach
            val rightStart = skipWhitespace(source, conjunction.range.last + 1)
            val rightVerb = firstVerb(verbs, rightStart, source.length) ?: return@forEach
            val leftVerb = firstVerb(verbs, segmentStart, conjunction.range.first)
            val startsWithVerb = rightVerb.start == rightStart
            val sharedPrefix = if (startsWithVerb && inheritedSubjectPrefix == null && leftVerb != null) {
                if (prefixCacheSegmentStart != segmentStart) {
                    prefixCacheSegmentStart = segmentStart
                    sharedPrefixWasComputed = false
                    cachedSharedPrefix = null
                }
                if (!sharedPrefixWasComputed) {
                    val candidatePrefix = source.substring(segmentStart, leftVerb.start).trim()
                    cachedSharedPrefix = candidatePrefix.takeIf {
                        it.isNotEmpty() && !NEGATION.containsMatchIn(it)
                    }
                    sharedPrefixWasComputed = true
                }
                cachedSharedPrefix
            } else {
                null
            }
            val prefix = if (startsWithVerb) inheritedSubjectPrefix ?: sharedPrefix else null
            val leftHasVerb = leftVerb != null
            val isIndependentClause = !startsWithVerb && leftHasVerb && rightVerb.start > rightStart
            val subjectPrefix = prefix?.takeIf { it.isNotBlank() }
            val negationScopeIsAmbiguous = containsOffsetInRange(negationOffsets, segmentStart, conjunction.range.first)
            val trailingQualifierScopeIsAmbiguous = startsWithVerb && leftVerb != null && containsOffsetInRange(
                qualifierOffsets,
                leftVerb.start + leftVerb.text.length,
                conjunction.range.first,
            )
            if (negationScopeIsAmbiguous || trailingQualifierScopeIsAmbiguous || (subjectPrefix == null && !isIndependentClause)) return@forEach

            makeCandidate(
                request.contextStartOffset,
                source,
                segmentStart,
                conjunction.range.first,
                inheritedSubjectPrefix,
            )?.let(result::add)
            segmentStart = rightStart
            inheritedSubjectPrefix = subjectPrefix
            prefixCacheSegmentStart = -1
            sharedPrefixWasComputed = false
            cachedSharedPrefix = null
        }

        makeCandidate(
            request.contextStartOffset,
            source,
            segmentStart,
            source.length,
            inheritedSubjectPrefix,
        )?.let(result::add)
        return result.distinctBy { it.sourceStartOffset to it.sourceEndOffset }
    }

    private fun makeCandidate(
        contextStartOffset: Int,
        source: String,
        start: Int,
        end: Int,
        prefix: String?,
    ): AtomicClaimCandidate? {
        var contentStart = start
        var contentEnd = end
        while (contentStart < contentEnd && source[contentStart].isWhitespace()) contentStart++
        while (contentEnd > contentStart && (source[contentEnd - 1].isWhitespace() || source[contentEnd - 1] in TRIMMABLE_ENDING)) contentEnd--
        if (contentStart >= contentEnd) return null
        val phrase = normalizeWhitespace(source.substring(contentStart, contentEnd))
        if (phrase.isBlank()) return null
        return AtomicClaimCandidate(
            text = listOfNotNull(prefix?.trim()?.takeIf(String::isNotBlank), phrase).joinToString(" "),
            sourceStartOffset = contextStartOffset + contentStart,
            sourceEndOffset = contextStartOffset + contentEnd,
        )
    }

    private fun finiteVerbs(source: String): List<Word> = WORD.findAll(source)
        .mapNotNull { match ->
            val word = match.value
            if (word.lowercase().trimEnd('’', '\'') in VERB_FORMS) Word(word, match.range.first) else null
        }
        .toList()

    private fun firstVerb(verbs: List<Word>, start: Int, end: Int): Word? {
        var low = 0
        var high = verbs.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (verbs[middle].start < start) low = middle + 1 else high = middle
        }
        return verbs.getOrNull(low)?.takeIf { it.start < end }
    }

    private fun matchStartOffsets(pattern: Regex, source: String): List<Int> =
        pattern.findAll(source).map { it.range.first }.toList()

    private fun containsOffsetInRange(offsets: List<Int>, start: Int, end: Int): Boolean {
        var low = 0
        var high = offsets.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (offsets[middle] < start) low = middle + 1 else high = middle
        }
        return offsets.getOrNull(low)?.let { it < end } == true
    }

    private fun skipWhitespace(source: String, from: Int): Int {
        var index = from
        while (index < source.length && source[index].isWhitespace()) index++
        return index
    }

    private fun normalizeWhitespace(value: String): String = value.replace(WHITESPACE, " ").trim()

    private data class Word(val text: String, val start: Int)

    companion object {
        private val COORDINATION = Regex("\\b(?:and|or|but)\\b", RegexOption.IGNORE_CASE)
        private val WORD = Regex("[\\p{L}][\\p{L}'’\\-]*")
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
        private val TRAILING_SCOPE_QUALIFIER = Regex(
            "\\b(?:in|among|for|under|with|during|after|before|within|at|by|on|over|across|between|through|without|despite|following|significantly|substantially|slightly|only|primarily|mostly|partially|consistently|statistically|clinically|possibly|probably|likely|unlikely)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val NEGATION = Regex("\\b(?:not|never|no|without|neither|nor)\\b", RegexOption.IGNORE_CASE)
        private val TRIMMABLE_ENDING = setOf(',', ';', ':', '.', '!', '?')
        private val VERB_BASES = setOf(
            "affect", "analyze", "analyse", "assess", "associate", "cause", "change", "compare", "confirm",
            "contribute", "correlate", "demonstrate", "decrease", "describe", "detect", "determine", "develop",
            "dispute", "evaluate", "examine", "explain", "find", "increase", "improve", "indicate",
            "influence", "investigate", "lead", "lower", "measure", "observe", "predict", "produce", "provide",
            "reduce", "replicate", "reproduce", "report", "remain", "reveal", "show", "support", "suggest", "use",
            "verify", "yield", "include", "contain", "occur", "establish", "identify", "present", "argue",
        )
        private val IRREGULAR_VERBS = setOf(
            "am", "are", "is", "was", "were", "be", "been", "being", "has", "have", "had", "do", "does", "did",
            "found", "felt", "left", "led", "made", "met", "put", "ran", "said", "saw", "set", "spent", "took",
            "thought", "underwent", "went", "won", "wrote", "rose", "grew", "fell", "became", "built", "held",
            "result", "resulted", "resulting",
        )
        private val VERB_FORMS = buildSet {
            addAll(IRREGULAR_VERBS)
            VERB_BASES.forEach { base ->
                add(base)
                addAll(inflections(base))
            }
        }

        private fun inflections(base: String): Set<String> = buildSet {
            add("${base}s")
            add("${base}ed")
            add("${base}ing")
            if (base.endsWith("e")) {
                add("${base}d")
                add("${base.dropLast(1)}ing")
            }
            if (base.endsWith("y")) {
                add("${base.dropLast(1)}ies")
                add("${base.dropLast(1)}ied")
            }
            if (base.endsWith("s") || base.endsWith("sh") || base.endsWith("ch") || base.endsWith("x") || base.endsWith("z") || base.endsWith("o")) {
                add("${base}es")
            }
        }
    }
}
