package com.mango.backend.contract.knowledge;

import java.util.List;

/** T04 实现并供 T05 复用；归一化、词典和停用词版本必须与固定快照一致。 */
public interface KnowledgeTokenizer {
    List<String> tokenize(String text);
}
