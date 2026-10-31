package com.mango.backend.knowledge.ingest;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.config.DatabaseProperties;
import com.mango.backend.infra.model.SiliconFlowEmbeddingClient;
import com.mango.backend.infra.pg.KnowledgeMapper;
import com.mango.backend.infra.pg.PgKnowledgeStore;
import com.mango.backend.infra.pg.PgStatementTimeout;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Import;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 单独的批处理主类，不扫描问答 Controller，不要求 QaService 已实现。 */
public final class IngestionApplication {
    private IngestionApplication() {}

    public static void main(String[] args) {
        var options = new HashMap<String, String>();
        Path path;
        Duration timeout;
        try {
            for (String arg : args) {
                int separator = arg.indexOf('=');
                if (!arg.startsWith("--") || separator < 3) {
                    throw new IllegalArgumentException("导入参数必须为 --名称=值");
                }
                String name = arg.substring(2, separator);
                String value = arg.substring(separator + 1);
                if (!Set.of("corpus-file", "import-timeout-ms").contains(name)
                        || value.isBlank() || options.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException("导入参数未知、重复或为空");
                }
            }
            if (!options.containsKey("corpus-file")) {
                throw new IllegalArgumentException("必须提供 --corpus-file=<JSON 文件路径>");
            }
            path = Path.of(options.get("corpus-file"));
            long milliseconds = Long.parseLong(options.getOrDefault("import-timeout-ms", "600000"));
            if (milliseconds <= 0 || milliseconds > Long.MAX_VALUE / 1_000_000) {
                throw new IllegalArgumentException("导入总预算必须为可表示的正整数毫秒");
            }
            timeout = Duration.ofMillis(milliseconds);
        } catch (IllegalArgumentException exception) {
            System.err.println("导入失败 code=INVALID_ARGUMENT：请检查 --corpus-file 和 --import-timeout-ms 参数");
            System.exit(2);
            return;
        }
        var application = new SpringApplication(ImportConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        application.setLogStartupInfo(false);
        application.setDefaultProperties(Map.of("spring.main.banner-mode", "off", "logging.level.root", "OFF"));
        int exitCode = 0;
        try (var context = application.run()) {
            var result = context.getBean(CorpusImporter.class).importCorpus(path, timeout);
            System.out.printf("导入成功 snapshotId=%s contentHash=%s documentCount=%d chunkCount=%d%n",
                    result.snapshotId(), result.contentHash(), result.documentCount(), result.chunkCount());
        } catch (CorpusImporter.ImportException exception) {
            System.err.printf("导入失败 code=%s snapshotId=%s：%s%n", exception.failure().code(),
                    exception.snapshotId() == null ? "未创建" : exception.snapshotId(), exception.failure().message());
            if (exception.publicationAttempted()) {
                System.err.println("发布调用未确认完成，请按 snapshotId 核对实际状态；本次不自动重试。");
            } else if (exception.snapshotId() != null) {
                System.err.println("快照创建已尝试，未发布；若已写入则保持 BUILDING，下次使用新快照完整重导。");
            }
            exitCode = 1;
        } catch (RuntimeException exception) {
            System.err.println("导入失败 code=INTERNAL_ERROR：启动配置、分词资源或应用依赖初始化失败");
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    /** 只有本批处理入口显式导入此配置，普通问答应用不加载额外配置。 */
    @EnableAutoConfiguration
    @EnableConfigurationProperties({AgentProperties.class, DatabaseProperties.class})
    @MapperScan(basePackageClasses = KnowledgeMapper.class)
    @ComponentScan(basePackageClasses = SiliconFlowEmbeddingClient.class)
    @Import({CorpusImporter.class, CorpusSourceReader.class, ClauseSplitter.class,
            JiebaKnowledgeTokenizer.class, PgKnowledgeStore.class, PgStatementTimeout.class})
    static class ImportConfiguration {}
}
