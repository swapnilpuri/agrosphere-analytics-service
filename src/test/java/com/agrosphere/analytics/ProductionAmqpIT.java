package com.agrosphere.analytics;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.testcontainers.containers.GenericContainer;
import com.github.dockerjava.api.model.*;
import com.rabbitmq.client.*;
import tools.jackson.databind.json.JsonMapper;

/** Actual AMQP authorization checks, not inferred from management permission strings. */
class ProductionAmqpIT {
    static GenericContainer<?> broker;
    static String admin="a"+UUID.randomUUID().toString().replace("-",""),password=UUID.randomUUID().toString();
    static String user="c"+UUID.randomUUID().toString().replace("-",""),consumerPassword=UUID.randomUUID().toString();
    static final String queue="agrosphere.analytics.production.v2",diagnostics="agrosphere.analytics.production.diagnostics",quarantine="agrosphere.analytics.production.quarantine.v1";
    static Connection connect(String username,String secret)throws Exception{
        var factory=new ConnectionFactory();factory.setHost("127.0.0.1");factory.setPort(broker.getMappedPort(5672));factory.setUsername(username);factory.setPassword(secret);
        factory.setAutomaticRecoveryEnabled(false);factory.setConnectionTimeout(5000);return factory.newConnection();
    }
    static void put(String path,Object body)throws Exception{
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+broker.getMappedPort(15672)+"/api/"+path))
            .header("Authorization","Basic "+Base64.getEncoder().encodeToString((admin+":"+password).getBytes(StandardCharsets.UTF_8)))
            .header("Content-Type","application/json").timeout(Duration.ofSeconds(5)).PUT(HttpRequest.BodyPublishers.ofByteArray(JsonMapper.builder().build().writeValueAsBytes(body))).build();
        assertEquals(201,HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request,HttpResponse.BodyHandlers.discarding()).statusCode());
    }
    @BeforeAll static void start()throws Exception{
        broker=new GenericContainer<>("rabbitmq:4.3.6-management").withEnv("RABBITMQ_DEFAULT_USER",admin).withEnv("RABBITMQ_DEFAULT_PASS",password)
            .withExposedPorts(5672,15672).withLabel("agrosphere.anl.fixture","owned-amqp-it");
        broker.withCreateContainerCmdModifier(cmd->cmd.getHostConfig().withPortBindings(
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(5672)),
            new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1",0),ExposedPort.tcp(15672))));broker.start();
        try(var c=connect(admin,password);var channel=c.createChannel()){
            channel.exchangeDeclare("agrosphere.domain","topic",true);
            channel.exchangeDeclare(diagnostics,"direct",true);
            channel.queueDeclare(queue,true,false,false,Map.of("x-max-length",10000));
            channel.queueDeclare(quarantine,true,false,false,Map.of("x-max-length",1000));
            channel.queueBind(quarantine,diagnostics,"quarantine");
        }
        put("users/"+user,Map.of("password",consumerPassword,"tags",List.of()));
        put("permissions/%2F/"+user,Map.of("configure","^$","read","^agrosphere\\.analytics\\.production\\.v2$","write","^agrosphere\\.analytics\\.production\\.diagnostics$"));
    }
    @AfterAll static void stop(){if(broker!=null)broker.stop();}
    @Test void runtimeCanConsumeOnlyItsQueueAndPublishConfirmedPrivateDiagnostics()throws Exception{
        try(var adminConnection=connect(admin,password);var adminChannel=adminConnection.createChannel();var connection=connect(user,consumerPassword);var channel=connection.createChannel()){
            adminChannel.confirmSelect();adminChannel.basicPublish("",queue,null,"owned synthetic fixture".getBytes(StandardCharsets.UTF_8));adminChannel.waitForConfirmsOrDie(5000);
            var message=channel.basicGet(queue,false);assertNotNull(message);channel.basicAck(message.getEnvelope().getDeliveryTag(),false);
            channel.confirmSelect();channel.basicPublish(diagnostics,"quarantine",true,MessageProperties.PERSISTENT_TEXT_PLAIN,"sanitized fixture".getBytes(StandardCharsets.UTF_8));channel.waitForConfirmsOrDie(5000);
            assertNotNull(adminChannel.basicGet(quarantine,true));
        }
    }
    @Test void runtimeCannotConfigureTopologyReadQuarantineOrPublishBusinessEvents()throws Exception{
        for(int action=0;action<3;action++)try(var connection=connect(user,consumerPassword)){
            var channel=connection.createChannel();
            final int selected=action;
            assertThrows(Exception.class,()->{
                if(selected==0)channel.queueDeclare("denied",true,false,false,null);
                else if(selected==1)channel.basicGet(quarantine,true);
                else{channel.basicPublish("agrosphere.domain","agrosphere.LatexCollectionRecorded.v2",null,new byte[0]);channel.basicQos(1);}
            });
            var reason=(AMQP.Channel.Close)channel.getCloseReason().getReason();assertEquals(403,reason.getReplyCode());
        }
    }
}
