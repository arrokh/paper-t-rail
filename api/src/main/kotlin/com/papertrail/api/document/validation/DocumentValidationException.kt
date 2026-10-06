package com.papertrail.api.document.validation

class DocumentValidationException(val code: String, override val message: String) : RuntimeException(message)
