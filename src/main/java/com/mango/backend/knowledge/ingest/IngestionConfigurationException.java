package com.mango.backend.knowledge.ingest;

/** 仅携带本模块生成的安全配置提示，供命令行边界穿透 Spring 初始化包装。 */
final class IngestionConfigurationException extends RuntimeException {
    IngestionConfigurationException(String message) {
        super(message);
    }

    IngestionConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
