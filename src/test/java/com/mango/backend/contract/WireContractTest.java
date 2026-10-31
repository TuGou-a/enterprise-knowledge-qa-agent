package com.mango.backend.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentKey;
import com.mango.backend.contract.qa.AnswerReason;
import com.mango.backend.contract.qa.QaRequest;
import com.mango.backend.contract.qa.QaRequest.Message;
import com.mango.backend.contract.qa.QaRequest.Role;
import com.mango.backend.contract.qa.TaskStatus;
import com.mango.backend.contract.runtime.TraceEvent;
import com.mango.backend.contract.search.SearchTypes.TopicGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WireContractTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mapsOnlyCallerHistoryRolesAndNormalizesAbsentHistory() throws JsonProcessingException {
        var request = mapper.readValue("""
                {"question":"那差旅补贴呢？","history":[
                  {"role":"user","content":"请查差旅制度"},
                  {"role":"assistant","content":"请说明具体问题"}
                ]}
                """, QaRequest.class);
        assertThat(request.history()).extracting(Message::role).containsExactly(Role.USER, Role.ASSISTANT);
        assertThat(mapper.readTree(mapper.writeValueAsString(request)).at("/history/0/role").asText()).isEqualTo("user");
        assertThat(mapper.readValue("{\"question\":\"报销期限多久？\"}", QaRequest.class).history()).isEmpty();
        assertThat(mapper.readValue("{\"question\":\"报销期限多久？\",\"history\":null}", QaRequest.class).history()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"system\"", "\"tool\"", "\"USER\"", "0", "\"0\""})
    void rejectsPrivilegedOrOrdinalHistoryRoles(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, Role.class)).isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void serializesStatusAndReasonByName() throws JsonProcessingException {
        for (var status : TaskStatus.values()) {
            var json = mapper.writeValueAsString(status);
            assertThat(json).isEqualTo("\"" + status.name() + "\"");
            assertThat(mapper.readValue(json, TaskStatus.class)).isEqualTo(status);
        }
        for (var reason : AnswerReason.values()) {
            var json = mapper.writeValueAsString(reason);
            assertThat(json).isEqualTo("\"" + reason.name() + "\"");
            assertThat(mapper.readValue(json, AnswerReason.class)).isEqualTo(reason);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "\"0\"", "\"answered\"", "\"UNKNOWN\""})
    void rejectsNumericOrUnknownTaskStatusesAndReasons(String json) {
        assertThatThrownBy(() -> mapper.readValue(json, TaskStatus.class)).isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> mapper.readValue(json, AnswerReason.class)).isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void doesNotLetCallersMutateHistoryOrTopicMembershipAfterConstruction() {
        var messages = new ArrayList<>(List.of(new Message(Role.USER, "请查制度")));
        var request = new QaRequest("差旅补贴呢？", messages);
        messages.clear();
        assertThat(request.history()).hasSize(1);
        assertThatThrownBy(() -> request.history().clear()).isInstanceOf(UnsupportedOperationException.class);

        var members = new ArrayList<ChunkKey>();
        var group = new TopicGroup("travel-allowance", members, false);
        members.add(new ChunkKey(new DocumentKey("S1", "D01", "v1"), "C01"));
        assertThat(group.members()).isEmpty();
        assertThatThrownBy(() -> group.members().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void roundTripsTraceToolParametersWithExplicitTypeDiscriminators() throws JsonProcessingException {
        var detail = new TraceEvent.ToolStarted("call-1", 1, TraceEvent.BusinessTool.SEARCH_KNOWLEDGE,
                new TraceEvent.SearchInput("报销期限", 99), new TraceEvent.SearchInput("报销期限", 6), "查找期限条款");
        var event = new TraceEvent("fixture-run", 1, Instant.parse("2026-01-01T00:00:00Z"), detail);
        var json = mapper.writeValueAsString(event);
        var tree = mapper.readTree(json);
        assertThat(tree.at("/detail/type").asText()).isEqualTo("TOOL_STARTED");
        assertThat(tree.at("/detail/requested/type").asText()).isEqualTo("SEARCH_INPUT");
        assertThat(tree.at("/detail/effective/topK").asInt()).isEqualTo(6);
        assertThat(mapper.readValue(json, TraceEvent.class)).isEqualTo(event);
    }
}
