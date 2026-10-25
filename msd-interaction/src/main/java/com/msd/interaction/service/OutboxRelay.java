package com.msd.interaction.service;

import com.msd.interaction.entity.OutboxEvent;
import com.msd.interaction.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Outbox 中继：把本地事务写入的待发事件投递到 Kafka。
 * 采用 at-least-once 语义，消费端必须按 eventId 幂等。
 */
@Service
public class OutboxRelay {

    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 单条消息发送等待上限，避免 Kafka 不可用时长时间占住事务。 */
    private static final long SEND_TIMEOUT_SECONDS = 5L;

    /** 指数退避上限，单位秒。 */
    private static final int MAX_BACKOFF_SECONDS = 60;

    /** last_error 列长度。 */
    private static final int MAX_ERROR_LENGTH = 500;

    // outbox 事件 Mapper
    private final OutboxEventMapper outboxEventMapper;
    // Kafka 发送客户端
    private final KafkaTemplate<String, String> kafkaTemplate;
    // 每批扫描的待发送事件数
    private final int batchSize;
    // 最大重试次数，达到后转死信
    private final int maxRetry;

    /**
     * 注入 Mapper、Kafka 客户端和 outbox 参数。
     */
    public OutboxRelay(OutboxEventMapper outboxEventMapper,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${msd.outbox.batch-size:50}") int batchSize,
                       @Value("${msd.outbox.max-retry:5}") int maxRetry) {
        this.outboxEventMapper = outboxEventMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
        this.maxRetry = maxRetry;
    }

    /**
     * 定时扫描待发送事件并逐条投递。
     * 同一批在数据库事务内处理；发送成功才标记 status=1。
     */
    @Scheduled(fixedDelayString = "${msd.outbox.relay-interval-ms:1000}")
    @Transactional
    public void relay() {
        // 1. 锁定一批到期的待发送事件，空批次直接结束
        List<OutboxEvent> events = outboxEventMapper.selectPending(batchSize);
        if (events.isEmpty()) {
            return;
        }

        // 2. 逐条同步发送，单条失败不影响同批其他事件
        for (OutboxEvent event : events) {
            try {
                kafkaTemplate.send(event.getTopic(), event.getEventKey(), event.getPayload())
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                // 3. 发送成功，记录状态和发送时间
                outboxEventMapper.markSent(event.getId());
                log.info("outbox sent: id={}, eventId={}, topic={}, key={}",
                        event.getId(), event.getEventId(), event.getTopic(), event.getEventKey());
            } catch (InterruptedException ex) {
                // 4. 线程被中断：恢复中断标记，记录重试后结束本批
                Thread.currentThread().interrupt();
                handleFailure(event, ex);
                log.warn("outbox relay interrupted, stop current batch");
                return;
            } catch (Exception ex) {
                // 5. 其他失败：按退避策略重试或转死信
                handleFailure(event, ex);
            }
        }
    }

    /**
     * 处理单条发送失败：未达上限按指数退避重试，达到上限转死信。
     */
    private void handleFailure(OutboxEvent event, Exception ex) {
        // 1. 递增重试次数并截断错误信息
        int retryCount = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        String error = truncate(String.valueOf(ex));

        // 2. 达到最大重试次数，标记死信等待人工处理
        if (retryCount >= maxRetry) {
            outboxEventMapper.markDead(event.getId(), retryCount, error);
            log.error("outbox dead: id={}, eventId={}, topic={}, retry={}, error={}",
                    event.getId(), event.getEventId(), event.getTopic(), retryCount, error);
            return;
        }

        // 3. 指数退避 2^retry 秒，上限 60 秒
        long backoffSeconds = Math.min(1L << Math.min(retryCount, 6), MAX_BACKOFF_SECONDS);
        LocalDateTime nextRetryTime = LocalDateTime.now().plusSeconds(backoffSeconds);
        outboxEventMapper.markRetry(event.getId(), retryCount, nextRetryTime, error);
        log.warn("outbox retry scheduled: id={}, eventId={}, topic={}, retry={}, backoff={}s, error={}",
                event.getId(), event.getEventId(), event.getTopic(), retryCount, backoffSeconds, error);
    }

    /**
     * 截断错误信息，保证不超过 last_error 列长度。
     */
    private String truncate(String error) {
        if (error == null) {
            return "";
        }
        return error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
    }
}
