package com.msd.common.event;

/**
 * Kafka 消费者组常量。
 */
public final class KafkaGroups {

    /**
     * 工具类禁止实例化。
     */
    private KafkaGroups() {
    }

    /** drama 互动计数消费者组。 */
    public static final String DRAMA_COUNTER = "msd-drama-counter-v1";

    /** interaction 评论计数消费者组。 */
    public static final String INTERACTION_COUNTER = "msd-interaction-counter-v1";
}
