package com.sina.banking.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class TransactionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    private final FanoutExchange fanoutExchange;



    public TransactionEventPublisher(RabbitTemplate rabbitTemplate, FanoutExchange fanoutExchange) {
        this.fanoutExchange = fanoutExchange;
        this.rabbitTemplate = rabbitTemplate;
    }

    // AFTER_COMMIT, not a plain @EventListener: publishing here has to happen only once the DB
    // transaction has actually committed - otherwise a rollback elsewhere in the same transaction
    // would leave a notification sent for data that never really happened. Routing key is "" because
    // a fanout exchange ignores it entirely; every bound queue gets a copy regardless.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionPosted(TransactionPostedEvent event) {
        NotificationRequest payload = new NotificationRequest(event.transactionId(), event.type(), event.status());
        String exchangeName = fanoutExchange.getName();

        log.debug("Publishing transaction-posted event: transactionId={} type={} status={}",
                event.transactionId(), event.type(), event.status());

        // Broker unreachable must never fail the banking operation that already committed - this is
        // fire-and-forget by design, same guarantee the old direct HTTP call to notification-service
        // had, just protecting against a different kind of downstream failure now.
        try {
            rabbitTemplate.convertAndSend(exchangeName, "", payload);
            log.info("Published transaction-posted event for transactionId={} to exchange={}",
                    event.transactionId(), exchangeName);
        } catch (Exception e) {
            log.warn("failed to send the event to fanout exchange {}: {}", exchangeName, e.getMessage());
        }
    }

}
