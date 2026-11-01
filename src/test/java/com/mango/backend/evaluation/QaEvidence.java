package com.mango.backend.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mango.backend.contract.qa.AnswerEnvelope;
import com.mango.backend.contract.qa.QaRequest;
import com.mango.backend.contract.runtime.TraceEvent;

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
                           String javaVersion, String frameworkVersion, Map<String, String> configuration) {}

    public static void write(Path parent, String caseId, QaRequest request, AnswerEnvelope response,
                             List<TraceEvent> trace, Mode mode, Map<String, String> configuration) throws Exception {
        var git = new ProcessBuilder("git", "rev-parse", "HEAD").start();
        String commit = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (git.waitFor() != 0) throw new IllegalStateException("无法记录实际 Git commit");
        var status = new ProcessBuilder("git", "status", "--porcelain").start();
        boolean dirty = !new String(status.getInputStream().readAllBytes(), StandardCharsets.UTF_8).isBlank();
        if (status.waitFor() != 0) throw new IllegalStateException("无法记录工作树状态");
        var directory = parent.resolve(caseId).resolve(response.runId());
        Files.createDirectories(directory);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var writer = mapper.writerWithDefaultPrettyPrinter();
        writer.writeValue(directory.resolve("request.json").toFile(), request);
        writer.writeValue(directory.resolve("response.json").toFile(), response);
        writer.writeValue(directory.resolve("trace.json").toFile(), trace);
        writer.writeValue(directory.resolve("run.json").toFile(), new Metadata(mode, Instant.now(), commit, dirty,
                System.getProperty("java.version"), "2.0.4", configuration));
        Files.writeString(directory.resolve("run.md"), "# 问答运行证据\n\n模式：`" + mode
                + "`，实际 commit：`" + commit + "`。输入、输出、Trace 与元数据均来自本次问答。\n\n"
                + "任务状态：`" + response.status() + "/" + response.reason() + "`；运行标识：`" + response.runId()
                + "`；快照：`" + response.snapshotId() + "`。\n\n"
                + "复现配置见 run.json；Maven 实际命令和退出码由证据脚本的外层 run.json 或执行者另记。\n\n"
                + (mode == Mode.MOCK ? "确定性模型和工具替身只验证实际服务及框架流程，不证明真实模型决策或检索质量。\n"
                : "实际输出不自动等于验收通过。人工核对者、核对时间、答案支持性、冲突解释与限制待补充。\n"));
    }
}
