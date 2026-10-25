package com.msd.agent.dto;

/**
 * AgentScope Supervisor 对话响应。
 *
 * @param reply     Supervisor 汇总后的回复
 * @param sessionId 本次使用的会话 ID，客户端后续复用它实现多轮
 */
public record AgentChatReply(String reply, String sessionId) {
}
