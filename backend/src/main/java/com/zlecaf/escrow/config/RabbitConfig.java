package com.zlecaf.escrow.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology. State-change events are published to a topic exchange with
 * routing key {@code escrow.state.<STATE>}, decoupling the escrow core from the
 * n8n orchestrator that consumes them.
 */
@Configuration
public class RabbitConfig {

    public static final String EXCHANGE = "escrow.events";
    public static final String QUEUE = "escrow.events.queue";
    public static final String ROUTING_PREFIX = "escrow.state.";

    @Bean
    public TopicExchange escrowExchange() {
        return ExchangeBuilder.topicExchange(EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue escrowQueue() {
        return QueueBuilder.durable(QUEUE).build();
    }

    @Bean
    public Binding escrowBinding(Queue escrowQueue, TopicExchange escrowExchange) {
        return BindingBuilder.bind(escrowQueue).to(escrowExchange).with(ROUTING_PREFIX + "#");
    }

    @Bean
    public Jackson2JsonMessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                         Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        return template;
    }
}
