package com.sina.banking.configuration;

import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.core.FanoutExchange;

// Fanout, not direct/topic: every consumer (notification today, fraud-scoring later) needs its own
// full copy of every posted-transaction event, not a slice routed by category - a shared queue would
// instead split messages between consumers (competing consumers), which is wrong for this case.
@Configuration
public class RabbitMQConfig {

    @Value("${fanout.exchange.name}")
    private String fanoutExchangeName;

    // durable=true so the exchange itself survives a RabbitMQ restart; autoDelete=false so it isn't
    // removed just because no queue happens to be bound to it at some point.
    @Bean
    public FanoutExchange fanoutExchange() {
        return new FanoutExchange(fanoutExchangeName, true, false);
    }

    // RabbitTemplate's default converter only handles String/byte[]/Serializable - without this bean,
    // publishing a record like NotificationRequest fails at send time, not at compile time.
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
