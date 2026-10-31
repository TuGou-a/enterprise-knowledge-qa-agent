package com.mango.backend.knowledge.ingest;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.mango.backend.contract.config.AgentProperties;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

@Component
final class CorpusSourceReader {
    private final ObjectMapper mapper;
    private final Validator validator;
    private final ClauseSplitter splitter;
    private final int characterLimit;

    CorpusSourceReader(ObjectMapper mapper, Validator validator, ClauseSplitter splitter, AgentProperties properties) {
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        this.mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        this.validator = validator;
        this.splitter = splitter;
        this.characterLimit = properties.limits().embeddingTextCharacters();
    }

    PreparedCorpus read(Path path, String snapshotId) {
        CorpusSource source;
        try (var input = Files.newInputStream(path)) {
            source = mapper.readValue(input, CorpusSource.class);
        } catch (JsonMappingException exception) {
            String field = exception.getPath().stream()
                    .map(reference -> reference.getFieldName() == null
                            ? "[" + reference.getIndex() + "]" : reference.getFieldName())
                    .reduce((left, right) -> left + "." + right).orElse("documents");
            throw new IllegalArgumentException("资料 JSON 字段格式不正确：" + field);
        } catch (JsonProcessingException exception) {
            var location = exception.getLocation();
            String position = location == null ? "" : "（第 " + location.getLineNr() + " 行，第 " + location.getColumnNr() + " 列）";
            throw new IllegalArgumentException("资料不是有效的单一 JSON 对象，或包含重复字段" + position);
        } catch (IOException exception) {
            throw new IllegalArgumentException("资料文件无法读取");
        }
        if (source == null) {
            throw new IllegalArgumentException("资料必须是包含 documents 的 JSON 对象");
        }
        var violations = validator.validate(source);
        if (!violations.isEmpty()) {
            String field = violations.stream().map(violation -> violation.getPropertyPath().toString())
                    .sorted().findFirst().orElseThrow();
            throw new IllegalArgumentException("资料字段缺失、为空或类型不正确：" + field);
        }
        var documents = new ArrayList<KnowledgeDocument>();
        var chunks = new ArrayList<KnowledgeChunk>();
        var keys = new HashSet<DocumentRef>();
        for (var document : source.documents()) {
            var ref = new DocumentRef(document.documentId(), document.version());
            if (!keys.add(ref)) {
                throw new IllegalArgumentException("资料包含重复文档版本：" + document.documentId());
            }
            if (document.applicableScope() != null && document.applicableScope().isBlank()) {
                throw new IllegalArgumentException("文档 " + document.documentId() + " applicableScope 必须为非空白字符串或 null");
            }
            LocalDate versionDate;
            try {
                if (!document.versionDate().matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
                    throw new DateTimeParseException("日期格式不正确", document.versionDate(), 0);
                }
                versionDate = LocalDate.parse(document.versionDate());
            } catch (DateTimeParseException exception) {
                throw new IllegalArgumentException("文档 " + document.documentId() + " versionDate 必须为有效的 YYYY-MM-DD 日期");
            }
            var replacements = document.supersedes().stream()
                    .map(value -> new DocumentRef(value.documentId(), value.version())).toList();
            if (new HashSet<>(replacements).size() != replacements.size() || replacements.contains(ref)) {
                throw new IllegalArgumentException("文档 " + document.documentId() + " 替代关系重复或指向自身");
            }
            var key = new DocumentKey(snapshotId, document.documentId(), document.version());
            var metadata = new DocumentMetadata(key, document.title(), versionDate, document.applicableScope(),
                    replacements, CorpusHashes.content(document.content()));
            documents.add(new KnowledgeDocument(metadata, document.content()));
            chunks.addAll(splitter.split(key, document.title(), document.content(), document.clauseTopics(), characterLimit));
        }
        for (var document : documents) {
            if (!keys.containsAll(document.metadata().supersedes())) {
                throw new IllegalArgumentException("文档 " + document.metadata().key().documentId() + " 替代关系指向本份资料中不存在的文档版本");
            }
        }
        return new PreparedCorpus(List.copyOf(documents), List.copyOf(chunks), CorpusHashes.corpus(documents));
    }

    record PreparedCorpus(List<KnowledgeDocument> documents, List<KnowledgeChunk> chunks, String contentHash) {}
}
