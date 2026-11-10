package com.agrosphere.analytics;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import com.agrosphere.analytics.adapter.out.persistence.ProductionJdbc;

/** Explicit internal CLI for the marked short-lived fixture; no HTTP or arbitrary scope/SQL. */
public final class OwnedRebuildMain {
    private static String env(String name) {
        var value=System.getenv(name);
        if(value==null||value.isBlank())throw new SecurityException("Missing owned fixture binding");
        return value;
    }
    public static void main(String[] args) {
        if(args.length!=0||!"true".equals(env("ANALYTICS_DEMO_ENABLED")))throw new SecurityException("Owned fixture only");
        var fixture=new GeneratedAnalytics(Path.of(env("ANALYTICS_FIXTURE_MANIFEST")),env("ANALYTICS_FIXTURE_SHA256"),Clock.systemUTC());
        fixture.verifyBindings(env("DB_URL"),env("DB_USER"),env("DB_MIGRATION_USER"),env("RABBITMQ_USER"));
        var source=new DriverManagerDataSource(env("DB_URL"),env("DB_USER"),env("DB_PASSWORD"));
        fixture.verifyDatabase(source);
        var store=new ProductionJdbc(new JdbcTemplate(source),new DataSourceTransactionManager(source));
        for(var scope:fixture.scopes()){fixture.permit(scope.estateId(),scope.blockId());store.rebuildOwned(scope.estateId(),scope.blockId());}
        System.out.println("PASS: owned Analytics daily materialization rebuilt; source ledger retained");
    }
}
