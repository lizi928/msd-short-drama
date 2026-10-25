package com.msd.drama.config;

import com.msd.common.event.KafkaTopics;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Kafka 消费容错配置：重试 3 次后把消息投递到死信 topic。
 */
@Configuration
public class KafkaConsumerConfig {

    /**
     * 默认错误处理器：每次重试间隔 1 秒，共重试 3 次，仍失败则进入 DLQ。
     */
    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // 1. 所有死信统一投递到互动 DLQ topic
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(KafkaTopics.INTERACTION_DLQ, -1));

        // 2. 固定间隔重试 3 次，之后交给 recoverer
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
    }
}
