package com.agrosphere.analytics.adapter.in.messaging;

import com.agrosphere.analytics.GeneratedAnalytics;
import com.agrosphere.analytics.adapter.out.persistence.EnvironmentalJdbc;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.security.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import tools.jackson.databind.json.JsonMapper;

/** Manual commit/confirm-before-ACK, finite retries and fail-closed pause. No full-body logging/DLQ. */
public final class EnvironmentalConsumer {
    public final AtomicLong committed=new AtomicLong(),duplicates=new AtomicLong(),diagnostics=new AtomicLong();
    public final AtomicBoolean paused=new AtomicBoolean();
    private final GeneratedAnalytics fixture;private final EnvironmentalJdbc store;private final RabbitTemplate rabbit;
    private final EnvironmentalEventJson parser=new EnvironmentalEventJson();
    public EnvironmentalConsumer(GeneratedAnalytics fixture,EnvironmentalJdbc store,RabbitTemplate rabbit){this.fixture=fixture;this.store=store;this.rabbit=rabbit;this.rabbit.setMandatory(true);}
    public SimpleMessageListenerContainer container(ConnectionFactory connection){
        var container=new SimpleMessageListenerContainer(connection);container.setQueueNames("agrosphere.analytics.environment.v1");
        container.setAcknowledgeMode(AcknowledgeMode.MANUAL);container.setPrefetchCount(1);container.setConcurrentConsumers(1);container.setMaxConcurrentConsumers(1);container.setAutoDeclare(false);
        container.setMissingQueuesFatal(true);container.setDefaultRequeueRejected(true);
        container.setMessageListener((ChannelAwareMessageListener)(message,channel)->{
            boolean ack=false;for(int attempt=0;attempt<4&&!ack;attempt++){
                try{
                    fixture.verify();com.agrosphere.analytics.domain.EnvironmentalObservation event;
                    try{event=parser.parse(message.getBody(),message.getMessageProperties().getReceivedRoutingKey());}
                    catch(EnvironmentalEventJson.AdmissionLimit limited){diagnostic(message.getBody(),null,"ADMISSION_LIMIT");channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);ack=true;continue;}
                    catch(IllegalArgumentException invalid){diagnostic(message.getBody(),null,"INVALID_ENVELOPE");channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);ack=true;continue;}
                    fixture.permit(event.estateId(),event.blockId());
                    var outcome=store.accept(event,Instant.now().truncatedTo(ChronoUnit.MILLIS));
                    if(outcome==com.agrosphere.analytics.domain.EnvironmentalProjection.Outcome.DUPLICATE)duplicates.incrementAndGet();else committed.incrementAndGet();
                    // Also check on duplicates: commit may have succeeded before an uncertain diagnostic/ACK.
                    if(store.hasIssue(event.eventId()))diagnostic(message.getBody(),event.eventId(),"SOURCE_EVENT_HAS_ISSUE");
                    channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);ack=true;
                }catch(SecurityException changed){break;}
                catch(Exception failure){if(failure instanceof InterruptedException){Thread.currentThread().interrupt();break;}if(attempt<3)try{Thread.sleep(1000L<<attempt);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}
            }
            if(!ack&&paused.compareAndSet(false,true)){var stop=new Thread(container::stop,"owned-environment-pause");stop.setDaemon(true);stop.start();}
        });return container;
    }
    private void diagnostic(byte[] body,UUID event,String reason)throws Exception{
        var hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));var fields=new LinkedHashMap<String,Object>();fields.put("bodySha256",hash);fields.put("reason",reason);if(event!=null)fields.put("eventId",event.toString());
        var properties=new MessageProperties();properties.setContentType("application/json");properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);properties.setMessageId(hash+":"+reason);
        var correlation=new CorrelationData(properties.getMessageId()+":"+UUID.randomUUID());
        rabbit.send("agrosphere.analytics.environment.diagnostics","quarantine",new Message(JsonMapper.builder().build().writeValueAsBytes(fields),properties),correlation);
        if(!correlation.getFuture().get(5,TimeUnit.SECONDS).ack()||correlation.getReturned()!=null)throw new IllegalStateException("Private diagnostic unconfirmed");diagnostics.incrementAndGet();
    }
}
