package com.codetrove.bootstrap.web;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiContractTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusResponseContainsGeneratedTraceIdInHeaderAndBody() throws Exception {
        mockMvc.perform(get("/api/v1/platform/status"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", matchesPattern("[a-f0-9]{32}")))
            .andExpect(jsonPath("$.data.name").value("CodeTrove"))
            .andExpect(jsonPath("$.data.status").value("UP"))
            .andExpect(jsonPath("$.data.schemaBaseline").value("M0"))
            .andExpect(jsonPath("$.meta.traceId", matchesPattern("[a-f0-9]{32}")));
    }

    @Test
    void validIncomingTraceIdIsPreserved() throws Exception {
        mockMvc.perform(get("/api/v1/platform/status").header("X-Trace-Id", "client-trace-42"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", "client-trace-42"))
            .andExpect(jsonPath("$.meta.traceId").value("client-trace-42"));
    }

    @Test
    void unsafeIncomingTraceIdIsReplaced() throws Exception {
        mockMvc.perform(get("/api/v1/platform/status").header("X-Trace-Id", "bad trace\r\nvalue"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Trace-Id", matchesPattern("[a-f0-9]{32}")))
            .andExpect(jsonPath("$.meta.traceId", matchesPattern("[a-f0-9]{32}")));
    }

    @Test
    void unknownApiPathUsesUnifiedErrorContract() throws Exception {
        mockMvc.perform(get("/api/v1/not-found"))
            .andExpect(status().isNotFound())
            .andExpect(header().string("X-Trace-Id", matchesPattern("[a-f0-9]{32}")))
            .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").value("Resource not found"))
            .andExpect(jsonPath("$.error.traceId", matchesPattern("[a-f0-9]{32}")));
    }
}
