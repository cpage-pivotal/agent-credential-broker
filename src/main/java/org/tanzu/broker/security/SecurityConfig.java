package org.tanzu.broker.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final WorkloadIdentityExtractor workloadIdentityExtractor;

    public SecurityConfig(WorkloadIdentityExtractor workloadIdentityExtractor) {
        this.workloadIdentityExtractor = workloadIdentityExtractor;
    }

    @Bean
    @Order(1)
    SecurityFilterChain credentialApiFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/credentials/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .x509(x509 -> x509
                .x509PrincipalExtractor(workloadIdentityExtractor)
                .userDetailsService(workloadIdentityUserDetailsService()))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain tokenExchangeFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/delegations/token")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .x509(x509 -> x509
                .x509PrincipalExtractor(workloadIdentityExtractor)
                .userDetailsService(workloadIdentityUserDetailsService()))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(Customizer.withDefaults())
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/actuator/health/**",
                    "/actuator/info",
                    "/favicon.ico",
                    "/oauth/callback"
                ).permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth -> oauth
                .defaultSuccessUrl("/", true)
            )
            .logout(l -> l
                .logoutUrl("/logout")
                .logoutSuccessUrl("/")
            );
        return http.build();
    }

    private UserDetailsService workloadIdentityUserDetailsService() {
        return username -> new User(username, "",
                AuthorityUtils.createAuthorityList("ROLE_WORKLOAD"));
    }
}
