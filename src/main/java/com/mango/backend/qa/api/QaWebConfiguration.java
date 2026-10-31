package com.mango.backend.qa.api;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.qa.QaRequest;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
public class QaWebConfiguration implements WebMvcConfigurer {
    private final AgentProperties properties;

    public QaWebConfiguration(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        for (var converter : converters) {
            if (converter instanceof MappingJackson2HttpMessageConverter jsonConverter) {
                // 使用类型专属副本，保留 Boot 配置且不改变模型草稿的解析规则。
                var requestMapper = jsonConverter.getObjectMapper().copy()
                        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .setDefaultSetterInfo(JsonSetter.Value.forContentNulls(Nulls.FAIL));
                requestMapper.coercionConfigFor(LogicalType.Textual)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
                jsonConverter.registerObjectMappersForType(QaRequest.class,
                        registrations -> registrations.put(MediaType.APPLICATION_JSON, requestMapper));
            }
        }
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // 应用先结束运行并组装结果，MVC 再以多出的 5 秒作为传输层兜底。
        configurer.setDefaultTimeout(properties.timeouts().requestMs() + 5_000);
    }
}
