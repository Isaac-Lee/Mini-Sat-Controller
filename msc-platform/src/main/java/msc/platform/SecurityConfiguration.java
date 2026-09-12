package msc.platform;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain security(
      HttpSecurity http,
      @Value("${msc.security.mode:oidc}") String mode,
      @Value("${msc.security.issuer-uri:}") String issuer,
      @Value("${msc.security.audience:msc-api}") String audience,
      @Value("${msc.security.local.admin-password:}") String admin,
      @Value("${msc.security.local.operator-password:}") String operator,
      @Value("${msc.security.local.requester-password:}") String requester,
      @Value("${msc.security.local.service-password:}") String service)
      throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR)
                    .permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**")
                    .permitAll()
                    .requestMatchers("/actuator/**", "/api/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/internal/**")
                    .hasRole("SERVICE")
                    .anyRequest()
                    .authenticated());
    if (mode.equals("local")) {
      for (var password : List.of(admin, operator, requester, service))
        if (password.length() < 16)
          throw new IllegalStateException(
              "Local simulation credentials must be supplied (16+ chars)");
      var encoder = new BCryptPasswordEncoder();
      var users =
          new InMemoryUserDetailsManager(
              User.withUsername("admin")
                  .password("{bcrypt}" + encoder.encode(admin))
                  .roles("ADMIN", "OPERATOR", "REQUESTER", "VIEWER")
                  .build(),
              User.withUsername("operator1")
                  .password("{bcrypt}" + encoder.encode(operator))
                  .roles("OPERATOR", "VIEWER")
                  .build(),
              User.withUsername("operator2")
                  .password("{bcrypt}" + encoder.encode(operator))
                  .roles("OPERATOR", "VIEWER")
                  .build(),
              User.withUsername("requester")
                  .password("{bcrypt}" + encoder.encode(requester))
                  .roles("REQUESTER", "VIEWER")
                  .build(),
              User.withUsername("service")
                  .password("{bcrypt}" + encoder.encode(service))
                  .roles("SERVICE")
                  .build());
      http.userDetailsService(users).httpBasic(c -> {});
    } else if (mode.equals("oidc")) {
      if (issuer.isBlank())
        throw new IllegalStateException(
            "OIDC issuer required; local auth is explicit simulation configuration only");
      var decoder = JwtDecoders.<NimbusJwtDecoder>fromIssuerLocation(issuer);
      OAuth2TokenValidator<Jwt> audienceValidator =
          jwt ->
              jwt.getAudience().contains(audience)
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(
                      new OAuth2Error("invalid_token", "Audience mismatch", null));
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
      var converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter(
          jwt -> {
            var roles = Optional.ofNullable(jwt.getClaimAsStringList("roles")).orElse(List.of());
            return roles.stream()
                .filter(Set.of("ADMIN", "OPERATOR", "REQUESTER", "VIEWER", "SERVICE")::contains)
                .map(
                    role ->
                        (org.springframework.security.core.GrantedAuthority)
                            new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
          });
      http.oauth2ResourceServer(
          o -> o.jwt(j -> j.decoder(decoder).jwtAuthenticationConverter(converter)));
    } else throw new IllegalStateException("Unknown security mode");
    return http.build();
  }
}
