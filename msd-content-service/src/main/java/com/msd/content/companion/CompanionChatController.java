package com.msd.content.companion;

import com.msd.common.result.Result;
import com.msd.content.config.CompanionProperties;
import com.msd.content.dto.CompanionChatRequest;
import com.msd.content.dto.CompanionChatResponse;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.concurrent.TimeoutException;

/**
 * AI 伴看聊天接口，经网关注入用户身份。
 */
@RestController
@RequestMapping("/internal/ai/companion")
@ConditionalOnProperty(prefix = "msd.ai.companion", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class CompanionChatController {

    // 伴看聊天服务
    private final CompanionChatService chatService;
    // 伴看配置，提供整体超时
    private final CompanionProperties properties;

    /**
     * 注入聊天服务和伴看配置。
     */
    public CompanionChatController(CompanionChatService chatService,
                                   CompanionProperties properties) {
        this.chatService = chatService;
        this.properties = properties;
    }

    /**
     * 处理一轮伴看聊天；整体超时返回固定降级回复。
     */
    @PostMapping("/chat")
    public Mono<Result<CompanionChatResponse>> chat(
            @RequestHeader(value = "X-User-Id", required = false) Long userId,
            @Valid @RequestBody CompanionChatRequest request) {
        // 1. 缺少网关注入的用户身份时拒绝，避免会话记忆串号
        if (userId == null || userId <= 0) {
            return Mono.error(new IllegalArgumentException("缺少用户身份"));
        }
        // 2. 聊天包含远程检索和模型调用，切到弹性线程池执行
        return Mono.fromCallable(() -> Result.ok(chatService.chat(userId, request)))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(Duration.ofMillis(Math.max(properties.getChat().getTimeoutMs(), 1)))
                // 3. 整体超时不抛错，返回固定兜底回复
                .onErrorResume(TimeoutException.class,
                        e -> Mono.just(Result.ok(chatService.timeoutResponse(request))));
    }
}
