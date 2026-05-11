package com.microservices.order.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${KAFKA_API_KEY:}")
    private String kafkaApiKey;

    @Value("${KAFKA_API_SECRET:}")
    private String kafkaApiSecret;

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> config = new HashMap<>();
        config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.ACKS_CONFIG, "all");
        config.put(ProducerConfig.RETRIES_CONFIG, 3);
        config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

        if (kafkaApiKey != null && !kafkaApiKey.isBlank()) {
            config.put("security.protocol", "SASL_SSL");
            config.put("sasl.mechanism", "PLAIN");
            config.put("sasl.jaas.config",
                    "org.apache.kafka.common.security.plain.PlainLoginModule required " +
                    "username=\"" + kafkaApiKey + "\" " +
                    "password=\"" + kafkaApiSecret + "\";");
        }

        return new DefaultKafkaProducerFactory<>(config);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    @Bean
    public NewTopic orderReceivedTopic() {
        return TopicBuilder.name("order.received").partitions(6).replicas(3).build();
    }

    @Bean
    public NewTopic orderStatusUpdatedTopic() {
        return TopicBuilder.name("order.status.updated").partitions(6).replicas(3).build();
    }

    @Bean
    public NewTopic orderReadyTopic() {
        return TopicBuilder.name("order.ready").partitions(6).replicas(3).build();
    }
}
