package com.agrosphere.analytics;
import com.agrosphere.analytics.adapter.out.persistence.ProductionJdbc;
import com.agrosphere.analytics.adapter.out.web.EstateClient;
import com.agrosphere.analytics.adapter.in.messaging.ProductionConsumer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
@ConditionalOnProperty(name="analytics.demo.enabled",havingValue="true")
public class AnalyticsRuntime {
 @Bean GeneratedAnalytics analyticsFixture(Environment env){return AnalyticsMigrationConfiguration.fixture(env);}
 @Bean ProductionJdbc productionStore(JdbcTemplate jdbc,PlatformTransactionManager tx){return new ProductionJdbc(jdbc,tx);}
 @Bean ProductionConsumer productionConsumer(GeneratedAnalytics fixture,ProductionJdbc store,RabbitTemplate rabbit,io.micrometer.core.instrument.MeterRegistry metrics){
 var consumer=new ProductionConsumer(fixture,store,rabbit);
 metrics.gauge("analytics.ingress.committed",consumer.committed);
 metrics.gauge("analytics.ingress.duplicates",consumer.duplicates);
 metrics.gauge("analytics.ingress.diagnostics",consumer.diagnostics);
 metrics.gauge("analytics.ingress.paused",consumer,c->c.paused.get()?1:0);
 return consumer;
 }
 @Bean @ConditionalOnProperty(name="analytics.ingestion.enabled",havingValue="true",matchIfMissing=true) SimpleMessageListenerContainer productionListener(ProductionConsumer consumer,ConnectionFactory connection){return consumer.container(connection);}
 @Bean EstateClient analyticsEstate(Environment env){return new EstateClient(env.getProperty("ANALYTICS_ESTATE_URL","http://localhost:8081"));}
}
