package com.mango.backend.qa.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.qa.*;
import com.mango.backend.contract.runtime.BudgetSnapshot;
import com.mango.backend.knowledge.T05TestFixtures;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.mango.backend.contract.search.SearchTypes.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class QaHttpTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private QaService service;
    private MockMvc mvc;
    private LocalValidatorFactoryBean validation;

    @BeforeEach
    void setUp() {
        service = mock(QaService.class);
        validation = new LocalValidatorFactoryBean();
        validation.afterPropertiesSet();
        var converter = new MappingJackson2HttpMessageConverter(mapper);
        var converters = new ArrayList<org.springframework.http.converter.HttpMessageConverter<?>>();
        converters.add(converter);
        new QaWebConfiguration(T05TestFixtures.properties()).extendMessageConverters(converters);
        mvc = MockMvcBuilders.standaloneSetup(new QaController(service, T05TestFixtures.properties()))
                .setControllerAdvice(new QaExceptionHandler()).setMessageConverters(converter)
                .setValidator(validation).setAsyncRequestTimeout(125000).build();
    }

    @AfterEach
    void closeValidator() {
        validation.close();
    }

    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void wrapsAllTaskStatusesInHttp200AfterAsyncDispatch(TaskStatus status) throws Exception {
        when(service.answer(any())).thenReturn(Mono.just(envelope(status)));
        var initial = mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"报销期限？\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(initial)).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value(status.name()))
                .andExpect(jsonPath("$.data.runId").value("test-run"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"question\":\"期限？\"}", "{\"question\":\"期限？\",\"history\":null}",
            "{\"question\":\"期限？\",\"history\":[]}"})
    void missingOrNullHistoryMeansEmptyHistory(String body) throws Exception {
        when(service.answer(any())).thenReturn(Mono.just(envelope(TaskStatus.ANSWERED)));
        dispatch(body);
        verify(service).answer(new QaRequest("期限？", List.of()));
    }

    @Test
    void preservesWhitespaceHistoryOrderAndConsecutiveRoles() throws Exception {
        when(service.answer(any())).thenReturn(Mono.just(envelope(TaskStatus.ANSWERED)));
        String body = """
                {"question":"  凭证？  ","history":[{"role":"user","content":" 差旅 "},
                {"role":"user","content":"住宿"}]}
                """;
        dispatch(body);
        verify(service).answer(new QaRequest("  凭证？  ", List.of(
                new QaRequest.Message(QaRequest.Role.USER, " 差旅 "),
                new QaRequest.Message(QaRequest.Role.USER, "住宿"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"question\":\" \"}", "{\"question\":null}",
            "{\"question\":7}", "{\"question\":true}", "{\"question\":\"甲\",\"extra\":1}",
            "{\"question\":\"甲\",\"history\":[null]}",
            "{\"question\":\"甲\",\"history\":[{\"role\":\"system\",\"content\":\"甲\"}]}",
            "{\"question\":\"甲\",\"history\":[{\"role\":0,\"content\":\"甲\"}]}",
            "{\"question\":\"甲\",\"history\":[{\"role\":\"user\",\"content\":false}]}",
            "{\"question\":\"甲\",\"history\":[{\"role\":\"user\",\"content\":\"甲\",\"secret-field\":1}]}",
            "{\"question\":\"甲\"} {}"})
    void rejectsInvalidWireInputBeforeServiceCall(String body) throws Exception {
        mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void acceptsExactLimitsAndRejectsEveryLengthOverflow() throws Exception {
        when(service.answer(any())).thenReturn(Mono.just(envelope(TaskStatus.ANSWERED)));
        var message = Map.of("role", "user", "content", "甲".repeat(4000));
        dispatch(mapper.writeValueAsString(Map.of("question", "甲".repeat(2000),
                "history", List.of(message, message, message))));
        clearInvocations(service);
        var shortMessage = Map.of("role", "user", "content", "甲");
        var invalid = List.of(Map.of("question", "甲".repeat(2001)),
                Map.of("question", "甲", "history", java.util.Collections.nCopies(21, shortMessage)),
                Map.of("question", "甲", "history", List.of(Map.of("role", "user", "content", "甲".repeat(4001)))),
                Map.of("question", "甲", "history", List.of(message, message, message, shortMessage)));
        for (var body : invalid) {
            mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body)))
                    .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
        }
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @EnumSource(Failure.Code.class)
    void mapsEveryDependencyCodeWithoutExposingUpstreamContent(Failure.Code code) throws Exception {
        when(service.answer(any())).thenReturn(Mono.error(new DependencyException(
                new Failure(code, "secret-upstream", true))));
        int expected = switch (code) {
            case TIMEOUT -> 504;
            case SNAPSHOT_UNAVAILABLE, INDEX_PROFILE_MISMATCH -> 503;
            case RATE_LIMITED, AUTHENTICATION_FAILED, UPSTREAM_UNAVAILABLE, INVALID_RESPONSE, DATABASE_UNAVAILABLE -> 502;
            default -> 500;
        };
        var result = dispatch("{\"question\":\"甲\"}");
        assertThat(result.getResponse().getStatus()).isEqualTo(expected);
        assertThat(mapper.readTree(result.getResponse().getContentAsByteArray()).path("code").asInt()).isEqualTo(expected);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("secret-upstream");
    }

    @Test
    void emptyServiceResultIsServerError() throws Exception {
        when(service.answer(any())).thenReturn(Mono.empty());
        assertThat(dispatch("{\"question\":\"甲\"}").getResponse().getStatus()).isEqualTo(500);
    }

    @Test
    void preservesMvcProtocolStatusAndAllowHeader() throws Exception {
        mvc.perform(get("/api/qa")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"));
        mvc.perform(post("/api/qa").contentType(MediaType.TEXT_PLAIN).content("甲"))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(service);
    }

    @Test
    void timeoutListenerProduces504AfterAsyncDispatch() throws Exception {
        when(service.answer(any())).thenReturn(Mono.never());
        var pending = mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"甲\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        var async = (MockAsyncContext) pending.getRequest().getAsyncContext();
        for (var listener : async.getListeners()) {
            listener.onTimeout(new AsyncEvent(async));
        }
        mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value(504));
        var configurer = mock(AsyncSupportConfigurer.class);
        new QaWebConfiguration(T05TestFixtures.properties()).configureAsyncSupport(configurer);
        verify(configurer).setDefaultTimeout(125000);
    }

    private MvcResult dispatch(String body) throws Exception {
        var initial = mvc.perform(post("/api/qa").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(initial)).andReturn();
    }

    private static AnswerEnvelope envelope(TaskStatus status) {
        var reason = switch (status) {
            case ANSWERED -> AnswerReason.EVIDENCE_SUFFICIENT;
            case CONFLICT -> AnswerReason.APPLICABILITY_UNKNOWN;
            case NO_EVIDENCE -> AnswerReason.OUT_OF_CORPUS;
            case NEEDS_CLARIFICATION -> AnswerReason.CONTEXT_REQUIRED;
            case INCOMPLETE -> AnswerReason.ROUTE_FAILED;
        };
        return new AnswerEnvelope("test-run", "snapshot", status, reason, "测试响应", List.of(), List.of(), null,
                new RetrievalSummary(false, false, new Routes(RouteStatus.OK, RouteStatus.OK, RouteStatus.OK),
                        RankingMode.RERANK, List.of(), List.of(), List.of(), List.of()),
                new BudgetSnapshot(1, 1, 6, 12, 0, 120000, 0, 0, false, false, false, false));
    }
}
