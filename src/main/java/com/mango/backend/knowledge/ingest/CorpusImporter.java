package com.mango.backend.knowledge.ingest;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.knowledge.KnowledgeTokenizer;
import com.mango.backend.contract.model.EmbeddingClient;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

@Component
public final class CorpusImporter {
    private final CorpusSourceReader reader;
    private final KnowledgeTokenizer tokenizer;
    private final KnowledgeStore store;
    private final EmbeddingClient embedding;
    private final AgentProperties properties;

    CorpusImporter(CorpusSourceReader reader, KnowledgeTokenizer tokenizer, KnowledgeStore store,
                   EmbeddingClient embedding, AgentProperties properties) {
        this.reader = reader;
        this.tokenizer = tokenizer;
        this.store = store;
        this.embedding = embedding;
        this.properties = properties;
    }

    public ImportResult importCorpus(Path path, Duration timeout) {
        var budget = ImportBudget.start(timeout);
        String snapshotId = UUID.randomUUID().toString();
        boolean creationAttempted = false;
        boolean publicationAttempted = false;
        try {
            var corpus = reader.read(path, snapshotId);
            budget.remaining();
            var tokens = new ArrayList<List<String>>(corpus.chunks().size());
            for (var chunk : corpus.chunks()) {
                tokens.add(tokenizer.tokenize(chunk.content()));
                budget.remaining();
            }
            var index = properties.index();
            var profile = new IndexProfile(properties.siliconflow().embeddingModel(), 1024,
                    index.preprocessingVersion(), index.tokenizerVersion(),
                    index.dictionaryVersion(), index.stopWordsVersion());
            var snapshot = new CorpusSnapshot(snapshotId, SnapshotState.BUILDING, profile,
                    corpus.contentHash(), Instant.now(), null);
            var creationTimeout = budget.callTimeout(properties.timeouts().databaseMs());
            creationAttempted = true;
            store.createSnapshot(snapshot, creationTimeout);
            int batchSize = properties.limits().embeddingBatchSize();
            for (int start = 0; start < corpus.documents().size(); start += batchSize) {
                int end = Math.min(start + batchSize, corpus.documents().size());
                store.insertDocuments(corpus.documents().subList(start, end),
                        budget.callTimeout(properties.timeouts().databaseMs()));
            }
            for (int start = 0; start < corpus.chunks().size(); start += batchSize) {
                int end = Math.min(start + batchSize, corpus.chunks().size());
                var chunks = corpus.chunks().subList(start, end);
                var vectors = embedding.embed(chunks.stream().map(KnowledgeChunk::content).toList(),
                        budget.callTimeout(properties.timeouts().embeddingMs())).vectors();
                budget.remaining();
                var indexed = new ArrayList<IndexedChunk>(chunks.size());
                for (int i = 0; i < chunks.size(); i++) {
                    indexed.add(new IndexedChunk(chunks.get(i), vectors.get(i), tokens.get(start + i)));
                }
                store.insertChunks(indexed, budget.callTimeout(properties.timeouts().databaseMs()));
            }
            var manifest = new SnapshotManifest(corpus.documents().size(), corpus.chunks().size(), corpus.contentHash());
            var publicationTimeout = budget.callTimeout(properties.timeouts().databaseMs());
            publicationAttempted = true;
            store.publishSnapshot(snapshotId, manifest, publicationTimeout);
            return new ImportResult(snapshotId, corpus.contentHash(), manifest.documentCount(), manifest.chunkCount());
        } catch (DependencyException exception) {
            throw new ImportException(exception.failure(), creationAttempted ? snapshotId : null,
                    publicationAttempted, exception);
        } catch (IllegalArgumentException exception) {
            throw new ImportException(new Failure(Failure.Code.INVALID_ARGUMENT, exception.getMessage(), false),
                    creationAttempted ? snapshotId : null, publicationAttempted, exception);
        } catch (RuntimeException exception) {
            throw new ImportException(new Failure(Failure.Code.INTERNAL_ERROR, "资料导入发生未预期错误", false),
                    creationAttempted ? snapshotId : null, publicationAttempted, exception);
        }
    }

    public record ImportResult(String snapshotId, String contentHash, int documentCount, int chunkCount) {}

    public static final class ImportException extends RuntimeException {
        private final Failure failure;
        private final String snapshotId;
        private final boolean publicationAttempted;

        private ImportException(Failure failure, String snapshotId, boolean publicationAttempted, RuntimeException cause) {
            super(failure.message(), cause);
            this.failure = failure;
            this.snapshotId = snapshotId;
            this.publicationAttempted = publicationAttempted;
        }

        public Failure failure() { return failure; }
        public String snapshotId() { return snapshotId; }
        public boolean publicationAttempted() { return publicationAttempted; }
    }

    private record ImportBudget(long startedAt, long timeoutNanos) {
        static ImportBudget start(Duration timeout) {
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("导入总预算必须为正数");
            }
            return new ImportBudget(System.nanoTime(), timeout.toNanos());
        }

        Duration remaining() {
            if (Thread.currentThread().isInterrupted()) {
                throw new DependencyException(new Failure(Failure.Code.UPSTREAM_UNAVAILABLE, "资料导入已中断", false));
            }
            long remaining = timeoutNanos - (System.nanoTime() - startedAt);
            if (remaining <= 0) {
                throw new DependencyException(new Failure(Failure.Code.BUDGET_EXHAUSTED, "资料导入总预算已耗尽", false));
            }
            return Duration.ofNanos(remaining);
        }

        Duration callTimeout(long configuredMs) {
            var remaining = remaining();
            var configured = Duration.ofMillis(configuredMs);
            return remaining.compareTo(configured) < 0 ? remaining : configured;
        }
    }
}
