package com.mango.backend.qa.api;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.qa.AnswerEnvelope;
import com.mango.backend.contract.qa.QaRequest;
import com.mango.backend.contract.qa.QaService;
import com.mango.backend.result.Result;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/qa")
public class QaController {
    private final QaService qaService;
    private final AgentProperties properties;

    public QaController(QaService qaService, AgentProperties properties) {
        this.qaService = qaService;
        this.properties = properties;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Result<AnswerEnvelope>> answer(@Valid @RequestBody QaRequest request) {
        var limits = properties.limits();
        if (request.question().length() > limits.questionCharacters()) {
            throw new QaInputLimitException("question 长度不能超过 " + limits.questionCharacters() + " 个字符");
        }
        if (request.history().size() > limits.historyMessages()) {
            throw new QaInputLimitException("history 不能超过 " + limits.historyMessages() + " 条消息");
        }

        long historyCharacters = 0;
        for (int index = 0; index < request.history().size(); index++) {
            var message = request.history().get(index);
            if (message.content().length() > limits.historyMessageCharacters()) {
                throw new QaInputLimitException("history[" + index + "].content 长度不能超过 "
                        + limits.historyMessageCharacters() + " 个字符");
            }
            historyCharacters += message.content().length();
        }
        if (historyCharacters > limits.historyCharacters()) {
            throw new QaInputLimitException("history 内容累计长度不能超过 " + limits.historyCharacters() + " 个字符");
        }

        return Mono.defer(() -> qaService.answer(request))
                .switchIfEmpty(Mono.error(new IllegalStateException("问答服务未返回任务结果")))
                .map(Result::success);
    }
}
