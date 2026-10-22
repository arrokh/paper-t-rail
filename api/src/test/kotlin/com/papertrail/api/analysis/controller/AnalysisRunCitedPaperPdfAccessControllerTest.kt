package com.papertrail.api.analysis.controller

import com.papertrail.api.analysis.service.AnalysisRunService
import com.papertrail.api.analysis.service.CitedPaperAssetPdfAccessService
import com.papertrail.api.infrastructure.http.ApiExceptionHandler
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.UUID

class AnalysisRunCitedPaperPdfAccessControllerTest {
    private val analysisRunService = Mockito.mock(AnalysisRunService::class.java)
    private val citedPaperAssetPdfAccessService = Mockito.mock(CitedPaperAssetPdfAccessService::class.java)
    private val mockMvc: MockMvc = MockMvcBuilders
        .standaloneSetup(AnalysisRunController(analysisRunService, citedPaperAssetPdfAccessService))
        .setControllerAdvice(ApiExceptionHandler())
        .build()

    @Test
    fun `returns a safe structured bad request for malformed run and asset identifiers`() {
        val assetId = UUID.randomUUID()
        val runId = UUID.randomUUID()

        mockMvc.perform(get("/api/v1/analysis-runs/not-a-uuid/cited-paper-assets/$assetId/pdf-access"))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("The request contains invalid fields."))
        mockMvc.perform(get("/api/v1/analysis-runs/$runId/cited-paper-assets/not-a-uuid/pdf-access"))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("The request contains invalid fields."))

        Mockito.verifyNoInteractions(citedPaperAssetPdfAccessService)
    }
}
