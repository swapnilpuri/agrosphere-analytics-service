package com.agrosphere.analytics;

import com.agrosphere.analytics.adapter.in.messaging.EnvironmentalConsumer;
import com.agrosphere.analytics.adapter.out.persistence.EnvironmentalJdbc;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name={"analytics.demo.enabled","analytics.environment.enabled"},havingValue="true")
public class EnvironmentalRuntime {
    @Bean EnvironmentalJdbc environmentalStore(JdbcTemplate jdbc,PlatformTransactionManager tx) { return new EnvironmentalJdbc(jdbc,tx); }
    /** Own connection is held inside this lifecycle; existing production autoconfiguration is untouched. */
    public static final class Ingress implements AutoCloseable {
        public final CachingConnectionFactory connection;
        public final EnvironmentalConsumer consumer;
        Ingress(GeneratedAnalytics fixture,EnvironmentalJdbc store,Environment env,io.micrometer.core.instrument.MeterRegistry metrics) {
            String user=env.getRequiredProperty("ANALYTICS_ENVIRONMENT_RABBIT_USER");fixture.verifyEnvironmentalBindings(user);
            connection=new CachingConnectionFactory(env.getProperty("spring.rabbitmq.host","localhost"),env.getProperty("spring.rabbitmq.port",Integer.class,5672));
            connection.setUsername(user);connection.setPassword(env.getRequiredProperty("ANALYTICS_ENVIRONMENT_RABBIT_PASSWORD"));
            connection.setPublisherConfirmType(ConfirmType.CORRELATED);connection.setPublisherReturns(true);
            consumer=new EnvironmentalConsumer(fixture,store,new RabbitTemplate(connection));
            metrics.gauge("analytics.environment.committed",consumer.committed);metrics.gauge("analytics.environment.duplicates",consumer.duplicates);
            metrics.gauge("analytics.environment.diagnostics",consumer.diagnostics);metrics.gauge("analytics.environment.paused",consumer,c->c.paused.get()?1:0);
        }
        public void close() { connection.destroy(); }
    }
    @Bean(destroyMethod="close") Ingress environmentalIngress(GeneratedAnalytics fixture,EnvironmentalJdbc store,Environment env,io.micrometer.core.instrument.MeterRegistry metrics) { return new Ingress(fixture,store,env,metrics); }
    @Bean @ConditionalOnProperty(name="analytics.environment.ingestion.enabled",havingValue="true",matchIfMissing=true)
    SimpleMessageListenerContainer environmentalListener(Ingress ingress) { return ingress.consumer.container(ingress.connection); }
}
