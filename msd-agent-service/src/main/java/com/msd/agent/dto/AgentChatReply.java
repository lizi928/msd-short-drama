package com.msd.agent.dto;

/**
 * AgentScope Supervisor 对话响应。
 *
 * @param reply           Supervisor 汇总后的回复
 * @param sessionId       本次使用的会话 ID，客户端后续复用它实现多轮
 * @param route           本次意图路由：COMPANION/AUDIT/DATA/SOCIAL/SUPERVISOR
 * @param routeSource     路由来源：RULE/CACHE/MODEL/FALLBACK
 * @param routeConfidence 路由置信度
 * @param routeLatencyMs  路由决策耗时（毫秒）
 */
public record AgentChatReply(
        String reply,
        String sessionId,
        String route,
        String routeSource,
        double routeConfidence,
        long routeLatencyMs) {
}
