package com.backendsystemdesignlab.notification.messaging;

import org.springframework.amqp.core.*;
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

    public static final String RETRY_EXCHANGE = "notification.retry.exchange";
    public static final String DLX = "notification.dlx";
    public static final String PUSH_RETRY_QUEUE = "notification.push.retry.queue";
    public static final String SMS_RETRY_QUEUE = "notification.sms.retry.queue";
    public static final String EMAIL_RETRY_QUEUE = "notification.email.retry.queue";
    public static final String PUSH_DLQ = "notification.push.dlq";
    public static final String SMS_DLQ = "notification.sms.dlq";
    public static final String EMAIL_DLQ = "notification.email.dlq";

    public static final String THROTTLE_EXCHANGE = "notification.throttle.exchange";
    public static final String PUSH_THROTTLE_QUEUE = "notification.push.throttle.queue";
    public static final String SMS_THROTTLE_QUEUE = "notification.sms.throttle.queue";
    public static final String EMAIL_THROTTLE_QUEUE = "notification.email.throttle.queue";
    public static final String PUSH_THROTTLE_ROUTING_KEY = "notification.push.throttle";
    public static final String SMS_THROTTLE_ROUTING_KEY = "notification.sms.throttle";
    public static final String EMAIL_THROTTLE_ROUTING_KEY = "notification.email.throttle";

    // Exchange: 메시지를 받아서 적절한 Queue로 전달하는 라우터
    @Bean
    public DirectExchange notificationExchange() {
        return new DirectExchange(EXCHANGE, true, false); // Routing Key가 정확하게 일치하는 Queue로 보내기
        // durable: RabbitMQ 서버가 재시작되어도 Exchange 정의를 유지하겠다, autoDelete: Consumer 등이 없어지면 Exchange 자동 삭제
    }

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    public DirectExchange throttleExchange() {
        return new DirectExchange(THROTTLE_EXCHANGE, true, false);
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
    public Queue pushRetryQueue() {
        return QueueBuilder
                .durable(PUSH_RETRY_QUEUE)
                .ttl(5000) // 5초 동안 Retry Queue에 보관
                .deadLetterExchange(EXCHANGE) // 시간이 지나면 다시 원래 Exchange
                .deadLetterRoutingKey(PUSH_ROUTING_KEY) // PUSH Queue로 돌아가도록
                .build();
    }

    @Bean
    public Queue smsRetryQueue() {
        return QueueBuilder
                .durable(SMS_RETRY_QUEUE)
                .ttl(5000)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(SMS_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue emailRetryQueue() {
        return QueueBuilder
                .durable(EMAIL_RETRY_QUEUE)
                .ttl(5000)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(EMAIL_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue pushDlq() {
        return QueueBuilder
                .durable(PUSH_DLQ)
                .build();
    }

    @Bean
    public Queue smsDlq() {
        return QueueBuilder
                .durable(SMS_DLQ)
                .build();
    }

    @Bean
    public Queue emailDlq() {
        return QueueBuilder
                .durable(EMAIL_DLQ)
                .build();
    }

    @Bean
    public Queue pushThrottleQueue() {
        return QueueBuilder
                .durable(PUSH_THROTTLE_QUEUE)
                .ttl(1000)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(PUSH_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue smsThrottleQueue() {
        return QueueBuilder
                .durable(SMS_THROTTLE_QUEUE)
                .ttl(1000)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(SMS_ROUTING_KEY)
                .build();
    }

    @Bean
    public Queue emailThrottleQueue() {
        return QueueBuilder
                .durable(EMAIL_THROTTLE_QUEUE)
                .ttl(1000)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(EMAIL_ROUTING_KEY)
                .build();
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
    public Binding pushRetryBinding(DirectExchange retryExchange, Queue pushRetryQueue) {
        return BindingBuilder
                .bind(pushRetryQueue)
                .to(retryExchange)
                .with(PUSH_ROUTING_KEY);
    }

    @Bean
    public Binding smsRetryBinding(DirectExchange retryExchange, Queue smsRetryQueue) {
        return BindingBuilder
                .bind(smsRetryQueue)
                .to(retryExchange)
                .with(SMS_ROUTING_KEY);
    }

    @Bean
    public Binding emailRetryBinding(DirectExchange retryExchange, Queue emailRetryQueue) {
        return BindingBuilder
                .bind(emailRetryQueue)
                .to(retryExchange)
                .with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public Binding pushDlqBinding(DirectExchange deadLetterExchange, Queue pushDlq) {
        return BindingBuilder
                .bind(pushDlq)
                .to(deadLetterExchange)
                .with(PUSH_ROUTING_KEY);
    }

    @Bean
    public Binding smsDlqBinding(DirectExchange deadLetterExchange, Queue smsDlq) {
        return BindingBuilder
                .bind(smsDlq)
                .to(deadLetterExchange)
                .with(SMS_ROUTING_KEY);
    }

    @Bean
    public Binding emailDlqBinding(DirectExchange deadLetterExchange, Queue emailDlq) {
        return BindingBuilder
                .bind(emailDlq)
                .to(deadLetterExchange)
                .with(EMAIL_ROUTING_KEY);
    }

    @Bean
    public Binding pushThrottleBinding(Queue pushThrottleQueue, DirectExchange throttleExchange) {
        return BindingBuilder
                .bind(pushThrottleQueue)
                .to(throttleExchange)
                .with(PUSH_THROTTLE_ROUTING_KEY);
    }

    @Bean
    public Binding smsThrottleBinding(Queue smsThrottleQueue, DirectExchange throttleExchange) {
        return BindingBuilder
                .bind(smsThrottleQueue)
                .to(throttleExchange)
                .with(SMS_THROTTLE_ROUTING_KEY);
    }

    @Bean
    public Binding emailThrottleBinding(Queue emailThrottleQueue, DirectExchange throttleExchange) {
        return BindingBuilder
                .bind(emailThrottleQueue)
                .to(throttleExchange)
                .with(EMAIL_THROTTLE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
