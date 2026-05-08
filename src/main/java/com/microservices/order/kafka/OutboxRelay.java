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

    @Scheduled(fixedDelay = 500)
    public void relayUnpublishedEvents() {
        List<OutboxEvent> unpublished = outboxEventRepository.findUnpublishedEvents();

        for (OutboxEvent event : unpublished) {
            kafkaTemplate.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                    .whenComplete((result, ex) -> {
                        if (ex == null) {
                            event.setPublished(true);
                            outboxEventRepository.save(event);
                            log.info("Published [{}] → topic={} partition={} offset={}",
                                    event.getId(),
                                    result.getRecordMetadata().topic(),
                                    result.getRecordMetadata().partition(),
                                    result.getRecordMetadata().offset());
                        } else {
                            log.error("Failed to publish event id={}: {}", event.getId(), ex.getMessage());
                        }
                    });
        }
    }
}
