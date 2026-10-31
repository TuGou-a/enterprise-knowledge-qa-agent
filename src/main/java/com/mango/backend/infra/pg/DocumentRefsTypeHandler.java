package com.mango.backend.infra.pg;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mango.backend.contract.knowledge.KnowledgeTypes.DocumentRef;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** 仅显式用于 supersedes 列，避免影响其他 List 参数。 */
public final class DocumentRefsTypeHandler extends BaseTypeHandler<List<DocumentRef>> {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<List<DocumentRef>> TYPE = new TypeReference<>() {};

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, List<DocumentRef> value,
                                    JdbcType jdbcType) throws SQLException {
        try {
            statement.setString(index, JSON.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            throw new SQLException("无法编码文档替代关系", "22000", exception);
        }
    }

    @Override
    public List<DocumentRef> getNullableResult(ResultSet result, String column) throws SQLException {
        return decode(result.getString(column));
    }

    @Override
    public List<DocumentRef> getNullableResult(ResultSet result, int column) throws SQLException {
        return decode(result.getString(column));
    }

    @Override
    public List<DocumentRef> getNullableResult(CallableStatement statement, int column) throws SQLException {
        return decode(statement.getString(column));
    }

    private List<DocumentRef> decode(String value) throws SQLException {
        if (value == null) {
            throw new SQLException("文档替代关系不能为空", "22000");
        }
        try {
            List<DocumentRef> refs = JSON.readValue(value, TYPE);
            if (refs == null || refs.stream().anyMatch(ref -> ref == null
                    || ref.documentId() == null || ref.documentId().isBlank()
                    || ref.version() == null || ref.version().isBlank())) {
                throw new SQLException("文档替代关系格式无效", "22000");
            }
            return List.copyOf(refs);
        } catch (JsonProcessingException exception) {
            throw new SQLException("无法读取文档替代关系", "22000", exception);
        }
    }
}
