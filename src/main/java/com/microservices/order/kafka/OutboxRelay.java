package com.microservices.order.kafka;

import com.microservices.order.entity.OutboxEvent;
import com.microservices.order.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class OutboxRelay {

    @Autowired
    private OutboxEventRepository outboxEventRepository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 500) // Run every 500ms
    public void relayUnpublishedEvents() {
        List<OutboxEvent> unpublishedEvents = outboxEventRepository.findUnpublishedEvents();

        for (OutboxEvent event : unpublishedEvents) {
            try {
                kafkaTemplate.send(event.getTopicName(), event.getPayload());
                event.setPublished(true);
                outboxEventRepository.save(event);
                log.info("Published outbox event: {} to topic: {}", event.getEventType(), event.getTopicName());
            } catch (Exception e) {
                log.error("Failed to publish outbox event: {}", event.getId(), e);
            }
        }
    }
}
