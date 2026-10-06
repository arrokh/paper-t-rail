package com.papertrail.api.document.validation

import com.optimaize.langdetect.LanguageDetector
import com.optimaize.langdetect.LanguageDetectorBuilder
import com.optimaize.langdetect.ngram.NgramExtractors
import com.optimaize.langdetect.profiles.LanguageProfileReader
import com.optimaize.langdetect.text.CommonTextObjectFactories
import org.springframework.stereotype.Component

@Component
class OptimaizeDocumentLanguageDetector : DocumentLanguageDetector {
    private val detector: LanguageDetector = LanguageDetectorBuilder.create(NgramExtractors.standard())
        .withProfiles(LanguageProfileReader().readAllBuiltIn())
        .build()
    private val textObjectFactory = CommonTextObjectFactories.forDetectingOnLargeText()

    override fun detect(text: String): LanguageDetection {
        val probabilities = detector.getProbabilities(textObjectFactory.forText(text))
        val best = probabilities.firstOrNull()
            ?: return LanguageDetection(null, 0.0)
        return LanguageDetection(best.locale.language, best.probability)
    }
}
