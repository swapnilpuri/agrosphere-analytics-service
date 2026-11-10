package com.agrosphere.analytics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
@Configuration
class SecurityConfiguration {
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
            .requestMatchers("/actuator/metrics/**").access((authentication,context)-> {
                var principal=authentication.get().getPrincipal();
                if(!(principal instanceof org.springframework.security.oauth2.jwt.Jwt jwt))return new org.springframework.security.authorization.AuthorizationDecision(false);
                var realm=jwt.getClaimAsMap("realm_access");
                var roles=realm==null?null:realm.get("roles");
                return new org.springframework.security.authorization.AuthorizationDecision(roles instanceof java.util.Collection<?> values&&(values.contains("PLATFORM_ADMIN")||values.contains("PLANTATION_ADMIN")));
            })
            .anyRequest().authenticated())
            .exceptionHandling(errors -> errors.authenticationEntryPoint((request,response,error) -> unauthorized(response)))
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {}).authenticationEntryPoint((request,response,error) -> unauthorized(response))).build();
    }
    private static void unauthorized(jakarta.servlet.http.HttpServletResponse response)throws java.io.IOException {
        response.setStatus(401);response.setContentType("application/problem+json");response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,\"detail\":\"A valid Analytics session is required\"}");
    }
}
