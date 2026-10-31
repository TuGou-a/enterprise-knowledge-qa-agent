package com.mango.backend.contract.qa;

import reactor.core.publisher.Mono;

/** T07 实现、T08 调用；每次订阅创建独立运行，禁止重复订阅复用同一可变上下文。 */
public interface QaService {
    Mono<AnswerEnvelope> answer(QaRequest request);
}
