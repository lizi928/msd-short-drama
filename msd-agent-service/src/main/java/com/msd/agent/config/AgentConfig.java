package com.msd.agent.config;

import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

import java.nio.file.Path;

/**
 * AgentScope Supervisor 配置：只负责 supervisor 主 Agent。
 *
 * <p>audit profile 不创建 Supervisor，避免 8106 进程同时暴露两套 Agent 入口。</p>
 */
@Configuration
@Profile("!audit")
@Conditional(AgentModelConfiguredCondition.class)
public class AgentConfig {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(AgentConfig.class);

    /**
     * 创建名为 supervisor 的主 Agent。
     *
     * <p>Agent 在调用之间无状态，同一会话由 AgentScope 串行化，因此使用单例；
     * 子 Agent 由 Harness 在运行时按规格文件创建。</p>
     */
    @Bean(destroyMethod = "close")
    @Lazy
    public HarnessAgent supervisor(Model model, Toolkit toolkit, AgentStateStore stateStore,
            @Value("${msd.ai.agent.workspace:.agentscope/workspace}") String workspace) {
        Path workspacePath = Path.of(workspace).toAbsolutePath().normalize();
        log.info("AgentScope workspace: {}", workspacePath);

        return HarnessAgent.builder()
                .name("supervisor")
                .agentId("supervisor")
                .sysPrompt("你是「码上短剧」的 Supervisor 调度中枢，按 AGENTS.md 的路由规则派发子 Agent 并汇总结果。")
                .model(model)
                .toolkit(toolkit)
                .stateStore(stateStore)
                .workspace(workspacePath)
                // 最小权限：保留工作区加载、子 Agent 和业务工具，关闭 shell/web/文件/记忆工具
                .disableShellTool()
                .disableWebTools()
                .disableFilesystemTools()
                .disableMemoryTools()
                .disableMemoryHooks()
                .build();
    }
}
