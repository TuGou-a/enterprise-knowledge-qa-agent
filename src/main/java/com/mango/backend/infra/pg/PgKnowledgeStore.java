package com.mango.backend.infra.pg;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.contract.error.Failure;
import com.mango.backend.contract.knowledge.KnowledgeStore;
import com.mango.backend.contract.model.DenseVector;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Supplier;

import static com.mango.backend.contract.knowledge.KnowledgeTypes.*;

@Repository
public class PgKnowledgeStore implements KnowledgeStore {
    private final KnowledgeMapper mapper;
    private final PlatformTransactionManager transactionManager;
    private final DataSource dataSource;
    private final int previewCharacters;

    public PgKnowledgeStore(KnowledgeMapper mapper, PlatformTransactionManager transactionManager,
                            DataSource dataSource, AgentProperties properties) {
        this.mapper = mapper;
        this.transactionManager = transactionManager;
        this.dataSource = dataSource;
        this.previewCharacters = properties.retrieval().previewCharacters();
    }

    @Override
    public Optional<CorpusSnapshot> latestReadySnapshot(Duration timeout) {
        return database(timeout, true, () -> Optional.ofNullable(mapper.latestReadySnapshot()).map(this::snapshot));
    }

    @Override
    public Optional<CorpusSnapshot> findSnapshot(String snapshotId, Duration timeout) {
        return database(timeout, true, () -> Optional.ofNullable(mapper.findSnapshot(snapshotId)).map(this::snapshot));
    }

    @Override
    public void createSnapshot(CorpusSnapshot snapshot, Duration timeout) {
        if (snapshot.state() != SnapshotState.BUILDING || snapshot.publishedAt() != null) {
            throw failure(Failure.Code.INVALID_ARGUMENT, "只能创建尚未发布的 BUILDING 快照", false);
        }
        database(timeout, false, () -> {
            mapper.createSnapshot(snapshot);
            return null;
        });
    }

    @Override
    public void insertDocuments(List<KnowledgeDocument> documents, Duration timeout) {
        database(timeout, false, () -> {
            if (documents.isEmpty()) {
                return null;
            }
            String snapshotId = documents.get(0).metadata().key().snapshotId();
            if (documents.stream().anyMatch(doc -> !snapshotId.equals(doc.metadata().key().snapshotId()))) {
                throw failure(Failure.Code.INVALID_ARGUMENT, "文档批次必须属于同一资料快照", false);
            }
            requireBuilding(snapshotId);
            mapper.insertDocuments(documents);
            return null;
        });
    }

    @Override
    public void insertChunks(List<IndexedChunk> chunks, Duration timeout) {
        database(timeout, false, () -> {
            if (chunks.isEmpty()) {
                return null;
            }
            String snapshotId = chunks.get(0).chunk().key().document().snapshotId();
            var rows = new ArrayList<KnowledgeMapper.ChunkInsert>(chunks.size());
            for (var indexed : chunks) {
                if (!snapshotId.equals(indexed.chunk().key().document().snapshotId())) {
                    throw failure(Failure.Code.INVALID_ARGUMENT, "片段批次必须属于同一资料快照", false);
                }
                rows.add(new KnowledgeMapper.ChunkInsert(indexed.chunk(), vectorText(indexed.embedding()),
                        String.join(" ", indexed.tokens())));
            }
            requireBuilding(snapshotId);
            mapper.insertChunks(rows);
            return null;
        });
    }

    @Override
    public CorpusSnapshot publishSnapshot(String snapshotId, SnapshotManifest manifest, Duration timeout) {
        return database(timeout, false, () -> {
            requireBuilding(snapshotId);
            var documents = mapper.documentHashes(snapshotId);
            long chunkCount = mapper.countChunks(snapshotId);
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("Java 运行环境缺少 SHA-256", exception);
            }
            // SQL 已按与 T01 相同的 UTF-8 字节序排列文档键；每项带四字节大端长度。
            for (var document : documents) {
                for (String value : List.of(document.documentId(), document.version(), document.contentHash())) {
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            String contentHash = HexFormat.of().formatHex(digest.digest());
            if (documents.size() != manifest.documentCount() || chunkCount != manifest.chunkCount()
                    || !contentHash.equals(manifest.contentHash())) {
                throw failure(Failure.Code.INVALID_ARGUMENT, "资料快照的数量或集合哈希与发布清单不符", false);
            }
            mapper.publishSnapshot(snapshotId, contentHash);
            return snapshot(mapper.findSnapshot(snapshotId));
        });
    }

    @Override
    public List<RecallHit> searchVector(String snapshotId, DenseVector vector, int limit, Duration timeout) {
        requireLimit(limit);
        return database(timeout, true, () -> hits(mapper.searchVector(snapshotId, vectorText(vector), limit)));
    }

    @Override
    public List<RecallHit> searchKeyword(String snapshotId, List<String> tokens, int limit, Duration timeout) {
        requireLimit(limit);
        return database(timeout, true, () -> tokens.isEmpty()
                ? List.of() : hits(mapper.searchKeyword(snapshotId, tokens, limit)));
    }

    @Override
    public TopicExpansion expandTopics(String snapshotId, List<String> topicKeys, List<ChunkKey> excluded,
                                       int limit, Duration timeout) {
        if (limit < 0) {
            throw failure(Failure.Code.INVALID_ARGUMENT, "主题扩展数量上限不能为负数", false);
        }
        if (excluded.stream().anyMatch(key -> !snapshotId.equals(key.document().snapshotId()))) {
            throw failure(Failure.Code.INVALID_ARGUMENT, "主题排除键必须属于指定资料快照", false);
        }
        return database(timeout, true, () -> {
            if (topicKeys.isEmpty()) {
                return new TopicExpansion(List.of(), false);
            }
            var rows = mapper.expandTopics(snapshotId, topicKeys, excluded, (long) limit + 1);
            boolean truncated = rows.size() > limit;
            return new TopicExpansion(rows.subList(0, Math.min(rows.size(), limit)).stream()
                    .map(this::preview).toList(), truncated);
        });
    }

    @Override
    public List<KnowledgeChunk> loadChunks(String snapshotId, List<ChunkKey> keys, Duration timeout) {
        if (keys.stream().anyMatch(key -> !snapshotId.equals(key.document().snapshotId()))
                || new HashSet<>(keys).size() != keys.size()) {
            throw failure(Failure.Code.INVALID_ARGUMENT, "批量读取必须使用同一快照内的去重片段键", false);
        }
        return database(timeout, true, () -> {
            if (keys.isEmpty()) {
                return List.of();
            }
            var chunks = new HashMap<ChunkKey, KnowledgeChunk>();
            for (var row : mapper.loadChunks(snapshotId, keys)) {
                var chunk = chunk(row);
                chunks.put(chunk.key(), chunk);
            }
            var ordered = new ArrayList<KnowledgeChunk>(keys.size());
            for (var key : keys) {
                var chunk = chunks.get(key);
                if (chunk == null) {
                    throw failure(Failure.Code.NOT_FOUND, "批量读取的片段不存在或尚未发布，未返回部分结果", false);
                }
                ordered.add(chunk);
            }
            return List.copyOf(ordered);
        });
    }

    @Override
    public Optional<ReadEvidence> read(ChunkKey key, Duration timeout) {
        return database(timeout, true, () -> {
            var rows = mapper.loadChunks(key.document().snapshotId(), List.of(key));
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            var row = rows.get(0);
            var metadata = new DocumentMetadata(key.document(), row.title(), row.versionDate(),
                    row.applicableScope(), row.supersedes(), row.documentHash());
            return Optional.of(new ReadEvidence(metadata, chunk(row)));
        });
    }

    private void requireBuilding(String snapshotId) {
        var row = mapper.lockSnapshot(snapshotId);
        if (row == null) {
            throw failure(Failure.Code.SNAPSHOT_UNAVAILABLE, "指定资料快照不存在", false);
        }
        if (row.state() != SnapshotState.BUILDING) {
            throw failure(Failure.Code.INVALID_ARGUMENT, "已发布的资料快照不能再次写入或发布", false);
        }
    }

    private CorpusSnapshot snapshot(PgRows.Snapshot row) {
        return new CorpusSnapshot(row.snapshotId(), row.state(), new IndexProfile(row.embeddingModel(),
                row.embeddingDimension(), row.preprocessingVersion(), row.tokenizerVersion(),
                row.dictionaryVersion(), row.stopWordsVersion()), row.contentHash(), row.createdAt(), row.publishedAt());
    }

    private KnowledgeChunk chunk(PgRows.Chunk row) {
        return new KnowledgeChunk(new ChunkKey(new DocumentKey(row.snapshotId(), row.documentId(), row.version()),
                row.chunkId()), row.topicKey(), row.content(), row.startOffset(), row.endOffset(), row.contentHash());
    }

    private ChunkPreview preview(PgRows.Chunk row) {
        int end = Math.min(previewCharacters, row.content().length());
        if (end > 0 && end < row.content().length() && Character.isHighSurrogate(row.content().charAt(end - 1))) {
            end--;
        }
        return new ChunkPreview(new ChunkKey(new DocumentKey(row.snapshotId(), row.documentId(), row.version()),
                row.chunkId()), row.title(), row.versionDate(), row.applicableScope(), row.topicKey(),
                row.content().substring(0, end));
    }

    private List<RecallHit> hits(List<PgRows.Chunk> rows) {
        var hits = new ArrayList<RecallHit>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            hits.add(new RecallHit(preview(rows.get(index)), index + 1, rows.get(index).rawScore()));
        }
        return List.copyOf(hits);
    }

    private String vectorText(DenseVector vector) {
        var text = new StringJoiner(",", "[", "]");
        for (float value : vector.values()) {
            text.add(Float.toString(value));
        }
        return text.toString();
    }

    private void requireLimit(int limit) {
        if (limit < 1) {
            throw failure(Failure.Code.INVALID_ARGUMENT, "查询数量上限必须为正数", false);
        }
    }

    /** 短事务是同步数据库调用的边界；不跨模型调用，也不共享可变请求状态。 */
    private <T> T database(Duration timeout, boolean readOnly, Supplier<T> operation) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw failure(Failure.Code.INVALID_ARGUMENT, "数据库超时必须为正数", false);
        }
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw failure(Failure.Code.BUDGET_EXHAUSTED, "数据库剩余预算不足一秒，未开始新查询", false);
        }
        long started = System.nanoTime();
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setReadOnly(readOnly);
        transaction.setTimeout((int) Math.min(timeout.getSeconds(), Integer.MAX_VALUE));
        try {
            return transaction.execute(status -> {
                Duration remaining = timeout.minusNanos(System.nanoTime() - started);
                if (remaining.compareTo(Duration.ofSeconds(1)) < 0) {
                    throw failure(Failure.Code.BUDGET_EXHAUSTED, "获取数据库连接后剩余预算不足一秒", false);
                }
                // 获取连接所耗时间也计入操作预算；拦截器在每条语句前重新向下取整。
                var holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(dataSource);
                holder.setTimeoutInMillis(remaining.toMillis());
                return operation.get();
            });
        } catch (DataAccessException | TransactionException | PersistenceException exception) {
            Failure.Code code = exception instanceof CannotCreateTransactionException
                    ? Failure.Code.DATABASE_UNAVAILABLE : Failure.Code.INTERNAL_ERROR;
            boolean retryable = code == Failure.Code.DATABASE_UNAVAILABLE;
            for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
                if (cause instanceof DependencyException dependency) {
                    throw dependency;
                }
                if (cause instanceof QueryTimeoutException || cause instanceof TransactionTimedOutException
                        || cause instanceof SQLTimeoutException) {
                    code = Failure.Code.TIMEOUT;
                    retryable = true;
                    break;
                }
                if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                    String state = sql.getSQLState();
                    if (state.equals("57014") || state.equals("55P03")) {
                        code = Failure.Code.TIMEOUT;
                        retryable = true;
                    } else if (state.startsWith("08") || state.startsWith("40") || state.startsWith("53")
                            || state.startsWith("57")) {
                        code = Failure.Code.DATABASE_UNAVAILABLE;
                        retryable = true;
                    } else if (state.startsWith("28")) {
                        code = Failure.Code.AUTHENTICATION_FAILED;
                    } else if (state.startsWith("23")) {
                        code = Failure.Code.INVALID_ARGUMENT;
                    } else if (state.startsWith("22")) {
                        code = Failure.Code.INVALID_RESPONSE;
                    }
                    break;
                }
            }
            String message = switch (code) {
                case TIMEOUT -> "数据库操作超时";
                case DATABASE_UNAVAILABLE -> "数据库暂时不可用";
                case AUTHENTICATION_FAILED -> "数据库认证失败";
                case INVALID_ARGUMENT -> "数据库拒绝写入：重复键或数据约束不满足";
                case INVALID_RESPONSE -> "数据库数据格式无效";
                default -> "数据库操作失败";
            };
            throw new DependencyException(new Failure(code, message, retryable), exception);
        }
    }

    private DependencyException failure(Failure.Code code, String message, boolean retryable) {
        return new DependencyException(new Failure(code, message, retryable));
    }
}
