-- 在已安装 pgvector 的 PostgreSQL、UTF-8 编码数据库中手动执行。
-- psql -X --set ON_ERROR_STOP=on --single-transaction --file src/main/resources/db/V001__knowledge.sql
-- 连接参数由 PGHOST/PGPORT/PGDATABASE/PGUSER 等环境变量及交互式密码提示提供。
DO $$
BEGIN
    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'knowledge corpus requires UTF8 server encoding';
    END IF;
END $$;

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE kb_corpus (
    snapshot_id text COLLATE "C" PRIMARY KEY,
    state text NOT NULL CHECK (state IN ('BUILDING', 'READY')),
    embedding_model text NOT NULL,
    embedding_dimension integer NOT NULL CHECK (embedding_dimension = 1024),
    preprocessing_version text NOT NULL,
    tokenizer_version text NOT NULL,
    dictionary_version text NOT NULL,
    stop_words_version text NOT NULL,
    content_hash text CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    published_at timestamptz,
    CHECK ((state = 'BUILDING' AND published_at IS NULL)
        OR (state = 'READY' AND published_at IS NOT NULL AND content_hash IS NOT NULL))
);

CREATE TABLE kb_document (
    snapshot_id text COLLATE "C" NOT NULL REFERENCES kb_corpus(snapshot_id),
    document_id text COLLATE "C" NOT NULL,
    version text COLLATE "C" NOT NULL,
    title text NOT NULL,
    version_date date NOT NULL,
    applicable_scope text,
    supersedes jsonb NOT NULL CHECK (jsonb_typeof(supersedes) = 'array'),
    content text NOT NULL,
    content_hash text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (snapshot_id, document_id, version)
);

CREATE TABLE kb_chunk (
    snapshot_id text COLLATE "C" NOT NULL,
    document_id text COLLATE "C" NOT NULL,
    version text COLLATE "C" NOT NULL,
    chunk_id text COLLATE "C" NOT NULL,
    topic_key text COLLATE "C" NOT NULL,
    content text NOT NULL,
    start_offset integer NOT NULL CHECK (start_offset >= 0),
    end_offset integer NOT NULL CHECK (end_offset > start_offset),
    content_hash text NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    search_vector tsvector NOT NULL,
    embedding vector(1024) NOT NULL,
    PRIMARY KEY (snapshot_id, document_id, version, chunk_id),
    FOREIGN KEY (snapshot_id, document_id, version)
        REFERENCES kb_document(snapshot_id, document_id, version)
);

CREATE INDEX kb_corpus_ready_idx ON kb_corpus (published_at DESC, snapshot_id COLLATE "C")
    WHERE state = 'READY';
CREATE INDEX kb_chunk_search_idx ON kb_chunk USING gin (search_vector);
CREATE INDEX kb_chunk_topic_idx ON kb_chunk
    (snapshot_id, topic_key COLLATE "C", document_id COLLATE "C", version COLLATE "C", chunk_id COLLATE "C");
