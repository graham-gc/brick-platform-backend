package apiworkflow.exception;

import apiworkflow.dto.ApiResponse;
import apiworkflow.dto.CopyFlowRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.validation.Valid;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new TestController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsNotFoundAsTheStandardApiResponse() throws Exception {
        mockMvc.perform(get("/test/missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Flow not found: 999"));
    }

    @Test
    void returnsValidationErrorsAsBadRequest() throws Exception {
        mockMvc.perform(post("/test/copy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("newName must not be blank"));
    }

    @Test
    void returnsDatabaseConflictsWithoutLeakingSqlDetails() throws Exception {
        mockMvc.perform(get("/test/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("The request conflicts with existing data"));
    }

    @Test
    void hidesUnexpectedInternalErrorDetails() throws Exception {
        mockMvc.perform(get("/test/unexpected"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("An unexpected server error occurred"));
    }

    @RestController
    @RequestMapping("/test")
    private static class TestController {

        @GetMapping("/missing")
        ApiResponse<Void> missing() {
            throw new ResourceNotFoundException("Flow not found: 999");
        }

        @PostMapping("/copy")
        ApiResponse<String> copy(@Valid @RequestBody CopyFlowRequest request) {
            return ApiResponse.success(request.getNewName());
        }

        @GetMapping("/conflict")
        ApiResponse<Void> conflict() {
            throw new DataIntegrityViolationException("duplicate key: internal table details");
        }

        @GetMapping("/unexpected")
        ApiResponse<Void> unexpected() {
            throw new IllegalStateException("sensitive internal detail");
        }
    }
}
