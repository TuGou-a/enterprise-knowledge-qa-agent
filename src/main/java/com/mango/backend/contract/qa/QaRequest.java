package com.mango.backend.contract.qa;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/** T08 在 HTTP 边界执行 Bean Validation，并按 AgentProperties 校验长度上限。 */
public record QaRequest(@NotBlank String question, List<@NotNull @Valid Message> history) {
    public QaRequest {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public enum Role {
        @JsonProperty("user") USER,
        @JsonProperty("assistant") ASSISTANT;

        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static Role fromJson(String value) {
            return switch (value) {
                case "user" -> USER;
                case "assistant" -> ASSISTANT;
                default -> throw new IllegalArgumentException("history.role 仅接受 user 或 assistant");
            };
        }
    }
    public record Message(@NotNull Role role, @NotBlank String content) {}
}
