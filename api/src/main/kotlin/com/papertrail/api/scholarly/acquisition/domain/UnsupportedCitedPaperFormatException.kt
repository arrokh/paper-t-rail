package com.papertrail.api.scholarly.acquisition.domain

class UnsupportedCitedPaperFormatException : IllegalArgumentException("Cited full text must be a PDF or plain text file.")
