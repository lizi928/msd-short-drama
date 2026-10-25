package com.msd.agent.service;

import com.msd.agent.dto.AgentChatReply;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Supervisor 调用服务：把 HTTP 请求转换成 AgentScope RuntimeContext 调用。
 *
 * <p>audit profile 只提供 A2A 审核服务，不创建 Supervisor 调用链。</p>
 */
@Service
@Profile("!audit")
public class SupervisorAgentService {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(SupervisorAgentService.class);

    // 懒加载的 Supervisor Agent，避免模型或 Redis 未就绪时阻塞启动
    private final ObjectProvider<HarnessAgent> agentProvider;
    // 单次对话超时时间
    private final Duration requestTimeout;

    /**
     * 注入懒加载的 Supervisor Agent 和请求超时时间。
     */
    public SupervisorAgentService(ObjectProvider<HarnessAgent> agentProvider,
            @Value("${msd.ai.agent.request-timeout:120s}") Duration requestTimeout) {
        this.agentProvider = agentProvider;
        this.requestTimeout = requestTimeout;
    }

    /**
     * 使用 userId + sessionId 调用 Supervisor；同一会话的状态由 Redis 恢复。
     */
    public Mono<AgentChatReply> chat(String userId, String sessionId,
                                     String message, Long episodeId) {
        // 1. 获取 Agent，未启用或初始化失败时抛出业务异常
        HarnessAgent agent = resolveAgent();
        // 2. 组装运行时上下文，AgentScope 据此恢复会话状态
        RuntimeContext context = RuntimeContext.builder()
                .userId(userId)
                .sessionId(sessionId)
                .build();

        // 3. 伴看场景把当前剧集 ID 放到用户消息前缀，供 companion-agent 直接使用
        String agentMessage = episodeId != null && episodeId > 0
                ? "[当前剧集ID=" + episodeId + "]\n" + message
                : message;

        log.info("Agent chat start: userId={}, sessionId={}, episodeId={}",
                userId, sessionId, episodeId);
        // 4. 调用并统一处理超时、空响应和日志
        return agent.call(new UserMessage(agentMessage), context)
                .timeout(requestTimeout)
                .switchIfEmpty(Mono.error(new AgentUnavailableException("Agent 未返回结果")))
                .map(reply -> new AgentChatReply(reply.getTextContent(), sessionId))
                .doOnSuccess(reply -> log.info(
                        "Agent chat done: userId={}, sessionId={}", userId, sessionId))
                .doOnError(ex -> log.error(
                        "Agent chat failed: userId={}, sessionId={}", userId, sessionId, ex));
    }

    /**
     * 获取 Agent；懒加载失败时转成可展示的业务异常，保留原始异常到日志。
     */
    private HarnessAgent resolveAgent() {
        try {
            HarnessAgent agent = agentProvider.getIfAvailable();
            if (agent == null) {
                throw new AgentUnavailableException(
                        "AgentScope 未启用：缺少 DEEPSEEK_API_KEY");
            }
            return agent;
        } catch (AgentUnavailableException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.error("AgentScope initialization failed", ex);
            throw new AgentUnavailableException(
                    "Agent 初始化失败，请检查模型、Redis 和工作区配置", ex);
        }
    }
}
