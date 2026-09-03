package com.sina.banking.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class TransactionNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(TransactionNotificationListener.class);

    private final RestClient restClient;

    private final String notificationServiceUrl;


    public TransactionNotificationListener(@Value("${notification.service.url}") String notificationServiceUrl) {
        this.notificationServiceUrl = notificationServiceUrl;

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(2));
        requestFactory.setReadTimeout(Duration.ofSeconds(2));
        this.restClient = RestClient.builder()
                .baseUrl(notificationServiceUrl)
                .requestFactory(requestFactory).build(); 
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onTransactionPosted(TransactionPostedEvent event) {
        String uri = "/notifications";
        NotificationRequest payload = new NotificationRequest(event.transactionId(), event.type(), event.status());

        try {
            restClient.post().uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("failed to send the event to {}: {}", notificationServiceUrl + uri, e.getMessage());
        }
    }

}
