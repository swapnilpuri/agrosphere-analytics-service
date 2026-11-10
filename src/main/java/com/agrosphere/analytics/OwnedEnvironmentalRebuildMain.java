package com.agrosphere.analytics;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import com.agrosphere.analytics.adapter.out.persistence.EnvironmentalJdbc;
import com.agrosphere.analytics.adapter.in.web.EnvironmentalController;
import com.agrosphere.analytics.domain.EnvironmentalProjection.Query;

/** No public endpoint or arbitrary target/query; use only the verified generated manifest window. */
public final class OwnedEnvironmentalRebuildMain {
    private static String env(String name) { var value=System.getenv(name);if(value==null||value.isBlank())throw new SecurityException("Missing owned fixture binding");return value; }
    public static void main(String[] args) {
        if(args.length!=0||!"true".equals(env("ANALYTICS_DEMO_ENABLED"))||!"true".equals(env("ANALYTICS_ENVIRONMENT_ENABLED")))throw new SecurityException("Owned fixture only");
        var fixture=new GeneratedAnalytics(Path.of(env("ANALYTICS_FIXTURE_MANIFEST")),env("ANALYTICS_FIXTURE_SHA256"),Clock.systemUTC());
        fixture.verifyBindings(env("DB_URL"),env("DB_USER"),env("DB_MIGRATION_USER"),env("RABBITMQ_USER"));fixture.verifyEnvironmentalBindings(env("ANALYTICS_ENVIRONMENT_RABBIT_USER"));
        var source=new DriverManagerDataSource(env("DB_URL"),env("DB_USER"),env("DB_PASSWORD"));fixture.verifyDatabase(source);
        var store=new EnvironmentalJdbc(new JdbcTemplate(source),new DataSourceTransactionManager(source));
        for(var scope:fixture.scopes()){fixture.permit(scope.estateId(),scope.blockId());store.verifyRebuildOwned(new Query(scope.estateId(),scope.blockId(),EnvironmentalController.utc(fixture.text("environmentWindowFrom")),EnvironmentalController.utc(fixture.text("environmentWindowTo"))));}
        System.out.println("PASS: owned environmental history rebuilt from immutable facts; no source/issue mutation");
    }
}
