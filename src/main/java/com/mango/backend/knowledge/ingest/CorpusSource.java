package com.mango.backend.knowledge.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

/** 仅用于导入边界；通过整份资料校验后转换为现有知识契约。 */
record CorpusSource(@NotEmpty List<@NotNull @Valid Document> documents) {
    record Document(@NotBlank String documentId, @NotBlank String version,
                    @NotBlank String versionDate, @NotBlank String title,
                    String applicableScope, @NotNull List<@NotNull @Valid Replacement> supersedes,
                    @NotBlank String content,
                    @NotEmpty Map<@NotBlank String, @NotBlank String> clauseTopics) {}

    record Replacement(@NotBlank String documentId, @NotBlank String version) {}
}
