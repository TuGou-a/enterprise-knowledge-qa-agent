package com.mango.backend.knowledge.ingest;

import com.mango.backend.contract.knowledge.KnowledgeTypes.KnowledgeDocument;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.TEXT_ORDER;

/** 文档、片段及资料集合统一遵循 T01 的哈希约定。 */
final class CorpusHashes {
    private CorpusHashes() {}

    static String content(String content) {
        return HexFormat.of().formatHex(digest().digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    static String corpus(List<KnowledgeDocument> documents) {
        var digest = digest();
        var ordered = documents.stream().sorted(Comparator
                .comparing((KnowledgeDocument doc) -> doc.metadata().key().documentId(), TEXT_ORDER)
                .thenComparing(doc -> doc.metadata().key().version(), TEXT_ORDER)).toList();
        for (var document : ordered) {
            for (String value : List.of(document.metadata().key().documentId(),
                    document.metadata().key().version(), document.metadata().contentHash())) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Java 运行环境缺少 SHA-256", exception);
        }
    }
}
