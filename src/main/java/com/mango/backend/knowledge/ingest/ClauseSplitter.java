package com.mango.backend.knowledge.ingest;

import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.ChunkKey;
import com.mango.backend.contract.knowledge.KnowledgeTypes.KnowledgeChunk;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
final class ClauseSplitter {
    private static final Pattern HEADING = Pattern.compile("(?m)^第([^\\r\\n]*?)条");

    List<KnowledgeChunk> split(DocumentKey key, String title, String content,
                               Map<String, String> topics, int characterLimit) {
        var matcher = HEADING.matcher(content);
        var starts = new ArrayList<Integer>();
        var bodyStarts = new ArrayList<Integer>();
        while (matcher.find()) {
            String number = Integer.toString(starts.size() + 1);
            if (!number.equals(matcher.group(1)) || matcher.end() == content.length()
                    || !Character.isWhitespace(content.charAt(matcher.end()))) {
                throw new IllegalArgumentException("文档 " + key.documentId() + " 条款编号或格式不正确，应为第" + number + "条");
            }
            starts.add(matcher.start());
            bodyStarts.add(matcher.end());
        }
        if (starts.isEmpty()) {
            throw new IllegalArgumentException("文档 " + key.documentId() + " 缺少编号条款");
        }
        var prefix = content.substring(0, starts.get(0)).lines().filter(line -> !line.isBlank()).toList();
        if (prefix.size() > 1 || (!prefix.isEmpty() && !prefix.get(0).equals(title))) {
            throw new IllegalArgumentException("文档 " + key.documentId() + " 条款前只允许标题行和空白");
        }
        if (topics.size() != starts.size()) {
            throw new IllegalArgumentException("文档 " + key.documentId() + " 条款与主题标注数量不一致");
        }
        var chunks = new ArrayList<KnowledgeChunk>(starts.size());
        for (int i = 0; i < starts.size(); i++) {
            String number = Integer.toString(i + 1);
            String topic = topics.get(number);
            if (topic == null) {
                throw new IllegalArgumentException("文档 " + key.documentId() + " 第" + number + "条缺少主题标注");
            }
            int start = starts.get(i);
            int end = i + 1 < starts.size() ? starts.get(i + 1) : content.length();
            if (content.substring(bodyStarts.get(i), end).isBlank()) {
                throw new IllegalArgumentException("文档 " + key.documentId() + " 第" + number + "条正文为空");
            }
            if (end - start > characterLimit) {
                throw new IllegalArgumentException("文档 " + key.documentId() + " 第" + number + "条超过单条字符上限 " + characterLimit);
            }
            String source = content.substring(start, end);
            chunks.add(new KnowledgeChunk(new ChunkKey(key, "clause-" + number), topic,
                    source, start, end, CorpusHashes.content(source)));
        }
        return List.copyOf(chunks);
    }
}
