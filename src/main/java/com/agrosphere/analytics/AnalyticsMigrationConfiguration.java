package com.agrosphere.analytics;
import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Clock;
import org.flywaydb.core.Flyway;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class AnalyticsMigrationConfiguration {
    @Bean FlywayMigrationStrategy analyticsMigrations(Environment env,DataSource source){return flyway->{
        if(!env.getProperty("analytics.demo.enabled",Boolean.class,false)){
            Flyway.configure().configuration(flyway.getConfiguration()).target("1").load().migrate();return;
        }
        var fixture=fixture(env);fixture.verifyDatabase(source);
        boolean environmental=env.getProperty("analytics.environment.enabled",Boolean.class,false);
        if(environmental)fixture.verifyEnvironmentalBindings(env.getRequiredProperty("ANALYTICS_ENVIRONMENT_RABBIT_USER"));
        Flyway.configure().configuration(flyway.getConfiguration()).target(environmental?"3":"2").load().migrate();
    };}
    public static GeneratedAnalytics fixture(Environment env){
        var result=new GeneratedAnalytics(Path.of(env.getRequiredProperty("ANALYTICS_FIXTURE_MANIFEST")),env.getRequiredProperty("ANALYTICS_FIXTURE_SHA256"),Clock.systemUTC());
        result.verifyBindings(env.getRequiredProperty("spring.datasource.url"),env.getRequiredProperty("spring.datasource.username"),env.getRequiredProperty("spring.flyway.user"),env.getRequiredProperty("spring.rabbitmq.username"));return result;
    }
}
