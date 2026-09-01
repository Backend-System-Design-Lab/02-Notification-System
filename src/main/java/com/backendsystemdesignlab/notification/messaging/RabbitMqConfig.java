package com.backendsystemdesignlab.notification.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String EXCHANGE = "notification.exchange";
    public static final String PUSH_QUEUE = "notification.push.queue";
    public static final String SMS_QUEUE = "notification.sms.queue";
    public static final String EMAIL_QUEUE = "notification.email.queue";
    public static final String PUSH_ROUTING_KEY = "notification.push";
    public static final String SMS_ROUTING_KEY = "notification.sms";
    public static final String EMAIL_ROUTING_KEY = "notification.email";

    // Exchange: 메시지를 받아서 적절한 Queue로 전달하는 라우터
    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(EXCHANGE, true, false); // Routing Key가 정확하게 일치하는 Queue로 보내기
        // durable: RabbitMQ 서버가 재시작되어도 Exchange 정의를 유지하겠다, autoDelete: Consumer 등이 없어지면 Exchange 자동 삭제
    }

    @Bean
    public Queue pushQueue() {
       return new Queue(PUSH_QUEUE, true);
    }

    @Bean
    public Queue smsQueue() {
        return new Queue(SMS_QUEUE, true);
    }

    @Bean
    public Queue emailQueue() {
        return new Queue(EMAIL_QUEUE, true);
    }

    @Bean
    public Binding pushBinding(DirectExchange notificationExchange, Queue pushQueue) {
        // pushQueue를 notificationExchange에 연결하고, notification.push라는 Routing Key로 연결해라
        return BindingBuilder
                .bind(pushQueue)
                .to(notificationExchange)
                .with(PUSH_ROUTING_KEY);
    }

    @Bean
    public Binding smsBinding(DirectExchange notificationExchange, Queue smsQueue) {
        return BindingBuilder
                .bind(smsQueue)
                .to(notificationExchange)
                .with(SMS_ROUTING_KEY);
    }

    @Bean
    public Binding emailBinding(DirectExchange notificationExchange, Queue emailQueue) {
        return BindingBuilder
                .bind(emailQueue)
                .to(notificationExchange)
                .with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
