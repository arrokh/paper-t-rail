package com.papertrail.api.references

import com.papertrail.api.runs.AnalysisConfigurationSnapshot

interface ScholarlyMetadataLookupFactory {
    val providerId: String
    fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup
}
