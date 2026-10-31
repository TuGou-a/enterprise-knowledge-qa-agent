package com.mango.backend.knowledge.ingest;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.WordDictionary;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.knowledge.KnowledgeTokenizer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public final class JiebaKnowledgeTokenizer implements KnowledgeTokenizer {
    private final JiebaSegmenter segmenter;
    private final Set<String> stopWords;

    public JiebaKnowledgeTokenizer(AgentProperties properties) {
        var index = properties.index();
        if (!"source-preserving-v1".equals(index.preprocessingVersion())
                || !"jieba-analysis-1.0.2".equals(index.tokenizerVersion())) {
            throw new IllegalArgumentException("分词器只支持 source-preserving-v1 和 jieba-analysis-1.0.2");
        }
        boolean customDictionary = index.dictionaryLocation() != null && !index.dictionaryLocation().isBlank();
        boolean customStopWords = index.stopWordsLocation() != null && !index.stopWordsLocation().isBlank();
        if (customDictionary == "builtin-v1".equals(index.dictionaryVersion())
                || customStopWords == "none-v1".equals(index.stopWordsVersion())) {
            throw new IllegalArgumentException("词典或停用词的资源路径与版本标识不匹配");
        }
        if (customDictionary) {
            var words = new HashSet<String>();
            var normalized = new ArrayList<String>();
            int lineNumber = 0;
            for (String line : readLines(index.dictionaryLocation())) {
                lineNumber++;
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                String[] fields = line.strip().split("\\s+");
                if (fields.length < 2 || fields.length > 3) {
                    throw new IllegalArgumentException("自定义词典第 " + lineNumber + " 行必须为「词语 频率 [词性]」");
                }
                double frequency;
                try {
                    frequency = Double.parseDouble(fields[1]);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("自定义词典第 " + lineNumber + " 行频率格式不正确");
                }
                String word = fields[0].toLowerCase(Locale.ROOT);
                if (!Double.isFinite(frequency) || frequency <= 0 || !words.add(word)
                        || word.codePoints().noneMatch(Character::isLetterOrDigit)) {
                    throw new IllegalArgumentException("自定义词典第 " + lineNumber + " 行词语重复、无效或频率不是有限正数");
                }
                normalized.add(word + " " + frequency);
            }
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("自定义词典没有有效词语");
            }
            // 该版本库吞掉加载 IOException；先固定已校验内容，再核对实际词典结果。
            try {
                Path temporary = Files.createTempFile("knowledge-jieba-", ".dict");
                try {
                    Files.write(temporary, normalized, StandardCharsets.UTF_8);
                    var dictionary = WordDictionary.getInstance();
                    dictionary.loadUserDict(temporary, StandardCharsets.UTF_8);
                    for (String word : words) {
                        if (!dictionary.containsWord(word) || !Double.isFinite(dictionary.getFreq(word))) {
                            throw new IllegalArgumentException("自定义词典未完成加载");
                        }
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("自定义词典临时资源无法写入或清理");
            }
        }
        var stops = new HashSet<String>();
        if (customStopWords) {
            int lineNumber = 0;
            for (String line : readLines(index.stopWordsLocation())) {
                lineNumber++;
                if (line.isBlank() || line.stripLeading().startsWith("#")) {
                    continue;
                }
                String word = line.strip().toLowerCase(Locale.ROOT);
                if (word.codePoints().anyMatch(Character::isWhitespace)
                        || word.codePoints().noneMatch(Character::isLetterOrDigit)) {
                    throw new IllegalArgumentException("停用词第 " + lineNumber + " 行必须为单个有效词语");
                }
                stops.add(word);
            }
            if (stops.isEmpty()) {
                throw new IllegalArgumentException("停用词资源没有有效词语");
            }
        }
        this.stopWords = Set.copyOf(stops);
        this.segmenter = new JiebaSegmenter();
    }

    @Override
    public List<String> tokenize(String text) {
        var result = new ArrayList<String>();
        for (var token : segmenter.process(text.toLowerCase(Locale.ROOT), JiebaSegmenter.SegMode.SEARCH)) {
            if (token.word.codePoints().anyMatch(Character::isLetterOrDigit) && !stopWords.contains(token.word)) {
                result.add(token.word);
            }
        }
        return List.copyOf(result);
    }

    private static List<String> readLines(String location) {
        Resource resource;
        if (location.startsWith("classpath:")) {
            resource = new ClassPathResource(location.substring("classpath:".length()));
        } else if (location.startsWith("file:")) {
            resource = new FileSystemResource(Path.of(URI.create(location)));
        } else {
            if (location.contains("://")) {
                throw new IllegalArgumentException("分词资源只支持本地文件或 classpath 路径");
            }
            resource = new FileSystemResource(location);
        }
        try (var reader = new BufferedReader(new InputStreamReader(resource.getInputStream(),
                StandardCharsets.UTF_8.newDecoder()))) {
            var lines = new ArrayList<String>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (IOException exception) {
            throw new IllegalArgumentException("分词资源无法读取或不是有效 UTF-8");
        }
    }
}
