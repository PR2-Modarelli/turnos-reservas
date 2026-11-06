package ar.com.modarelli.turnos.config;

import java.util.Base64;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SeguridadConfiguration {
    @Bean
    JwtDecoder jwtDecoder(@Value("${turnos.jwt-secret}") String secret) {
        byte[] key = Base64.getDecoder().decode(secret);
        if (key.length < 64) throw new IllegalArgumentException("La clave JWT debe tener al menos 64 bytes");
        var decoder=NimbusJwtDecoder.withSecretKey(new SecretKeySpec(key, "HmacSHA512"))
            .macAlgorithm(MacAlgorithm.HS512).build();
        org.springframework.security.oauth2.core.OAuth2TokenValidator<Jwt> camposObligatorios=jwt -> {
            if(jwt.getExpiresAt()==null || jwt.getSubject()==null || jwt.getSubject().isBlank())
                return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.failure(
                    new org.springframework.security.oauth2.core.OAuth2Error("invalid_token","Faltan campos obligatorios",null));
            return org.springframework.security.oauth2.core.OAuth2TokenValidatorResult.success();
        };
        decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(),camposObligatorios));
        return decoder;
    }
    @Bean
    JwtEncoder jwtEncoder(@Value("${turnos.jwt-secret}") String secret) {
        return new NimbusJwtEncoder(new com.nimbusds.jose.jwk.source.ImmutableSecret<>(
            new SecretKeySpec(Base64.getDecoder().decode(secret),"HmacSHA512")));
    }
    @Bean
    org.springframework.security.crypto.password.PasswordEncoder passwordEncoder() {
        return org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
    @Bean
    SecurityFilterChain seguridad(HttpSecurity http) {
        var roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("auth");
        roles.setAuthorityPrefix("");
        roles.setAuthoritiesClaimDelimiter(",");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return http.csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health").permitAll()
                .requestMatchers(org.springframework.http.HttpMethod.POST,"/api/register","/api/authenticate").permitAll()
                .requestMatchers("/api/**").hasAnyAuthority("ROLE_USER", "ROLE_ADMIN")
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(converter))).build();
    }
}
