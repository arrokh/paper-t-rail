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
        val result = mutableListOf<AtomicClaimCandidate>()
        var segmentStart = 0
        var inheritedSubjectPrefix: String? = null

        COORDINATION.findAll(source).forEach { conjunction ->
            if (!conjunction.value.equals("and", ignoreCase = true)) return@forEach
            val left = source.substring(segmentStart, conjunction.range.first)
            val rightStart = skipWhitespace(source, conjunction.range.last + 1)
            val rightVerb = firstVerb(source, rightStart, source.length) ?: return@forEach
            val startsWithVerb = rightVerb.start == rightStart
            val prefix = if (startsWithVerb) {
                inheritedSubjectPrefix ?: sharedSubjectPrefix(left)
            } else {
                null
            }
            val leftHasVerb = firstVerb(source, segmentStart, conjunction.range.first) != null
            val isIndependentClause = !startsWithVerb && leftHasVerb && rightVerb.start > rightStart
            val subjectPrefix = prefix?.takeIf { it.isNotBlank() }
            val negationScopeIsAmbiguous = NEGATION.containsMatchIn(left)
            if (negationScopeIsAmbiguous || (subjectPrefix == null && !isIndependentClause)) return@forEach

            makeCandidate(
                request.contextStartOffset,
                source,
                segmentStart,
                conjunction.range.first,
                inheritedSubjectPrefix,
            )?.let(result::add)
            segmentStart = rightStart
            inheritedSubjectPrefix = subjectPrefix
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

    private fun sharedSubjectPrefix(left: String): String? {
        val firstVerb = firstVerb(left, 0, left.length) ?: return null
        val prefix = left.substring(0, firstVerb.start).trim()
        return prefix.takeIf { it.isNotEmpty() && !NEGATION.containsMatchIn(it) }
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
        val cleanedPhrase = phrase.replace(LEADING_DISCOURSE_MARKER, "").trim()
        if (cleanedPhrase.isBlank()) return null
        return AtomicClaimCandidate(
            text = listOfNotNull(prefix?.trim()?.takeIf(String::isNotBlank), cleanedPhrase).joinToString(" "),
            sourceStartOffset = contextStartOffset + contentStart,
            sourceEndOffset = contextStartOffset + contentEnd,
        )
    }

    private fun firstVerb(source: String, start: Int, end: Int): Word? = WORD.findAll(source.substring(start, end))
        .map { Word(it.value, start + it.range.first) }
        .firstOrNull { isFiniteVerb(it.text) }

    private fun isFiniteVerb(word: String): Boolean {
        val normalized = word.lowercase().trimEnd('’', '\'')
        if (normalized in IRREGULAR_VERBS) return true
        return VERB_BASES.any { base ->
            normalized == base || normalized in inflections(base)
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
        private val LEADING_DISCOURSE_MARKER = Regex("^(?:however|therefore|moreover|nevertheless|although|though),?\\s+", RegexOption.IGNORE_CASE)
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
    }
}
