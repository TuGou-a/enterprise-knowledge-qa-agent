package com.mango.backend.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.qa.AnswerEnvelope;
import com.mango.backend.contract.qa.QaRequest;
import com.mango.backend.contract.runtime.TraceEvent;
import com.mango.backend.contract.knowledge.KnowledgeTypes.IndexProfile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class QaEvidence {
    private QaEvidence() {}

    public enum Mode { MOCK, REAL, REAL_WITH_INJECTED_FAULT }
    public record Metadata(Mode mode, Instant recordedAt, String commit, boolean workingTreeDirty,
                           String javaVersion, String frameworkVersion, IndexProfile indexProfile,
                           String model, String rerankModel, String executionMetadataFile,
                           Map<String, String> configuration) {}

    public static void write(Path parent, String caseId, QaRequest request, AnswerEnvelope response,
                             List<TraceEvent> trace, Mode mode, Map<String, String> configuration) throws Exception {
        var git = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
        String commit;
        try (var output = git.getInputStream()) {
            commit = new String(output.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
        if (git.waitFor() != 0) throw new IllegalStateException("无法记录实际 Git commit");
        var status = new ProcessBuilder("git", "status", "--porcelain").redirectErrorStream(true).start();
        boolean dirty;
        try (var output = status.getInputStream()) {
            dirty = !new String(output.readAllBytes(), StandardCharsets.UTF_8).isBlank();
        }
        if (status.waitFor() != 0) throw new IllegalStateException("无法记录工作树状态");
        var started = trace.stream().map(TraceEvent::detail).filter(TraceEvent.Started.class::isInstance)
                .map(TraceEvent.Started.class::cast).findFirst().orElseThrow();
        String executionMetadata = System.getProperty("evaluation.executionMetadata");
        var directory = parent.resolve(caseId).resolve(response.runId());
        Files.createDirectories(directory);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var writer = mapper.writerWithDefaultPrettyPrinter();
        writer.writeValue(directory.resolve("request.json").toFile(), request);
        writer.writeValue(directory.resolve("response.json").toFile(), response);
        writer.writeValue(directory.resolve("trace.json").toFile(), trace);
        var recordedAt = Instant.now();
        writer.writeValue(directory.resolve("run.json").toFile(), new Metadata(mode, recordedAt, commit, dirty,
                System.getProperty("java.version"), "2.0.4", started.indexProfile(), started.model(),
                started.rerankModel(), executionMetadata, configuration));
        String execution = executionMetadata == null
                ? "直接 Maven 调用未自动捕获完整命令与退出码，不能作为完整交付证据；应使用 scripts/run-tests.py mock 或 qa。"
                : "同次 Maven 的实际命令、起止时间和退出码见 [外层运行记录]("
                + directory.toAbsolutePath().relativize(Path.of(executionMetadata).toAbsolutePath())
                .toString().replace('\\', '/') + ")。";
        Files.writeString(directory.resolve("run.md"), "# 问答运行证据\n\n模式：`" + mode
                + "`，实际 commit：`" + commit + "`。输入、输出、Trace 与元数据均来自本次问答。\n\n"
                + "记录时间：`" + recordedAt + "`；Java：`" + System.getProperty("java.version")
                + "`；AgentScope：`2.0.4`。\n\n"
                + "任务状态：`" + response.status() + "/" + response.reason() + "`；运行标识：`" + response.runId()
                + "`；快照：`" + response.snapshotId() + "`。\n\n"
                + execution + "\n\n索引配置、模型版本和执行前提见 [run.json](run.json)。\n\n"
                + (mode == Mode.MOCK ? "确定性模型和工具替身只验证实际服务及框架流程，不证明真实模型决策或检索质量。\n"
                : "实际输出不自动等于验收通过。人工核对者：待填写；核对时间：待填写；答案支持性、冲突解释与限制：待核对。\n"));
    }
}
