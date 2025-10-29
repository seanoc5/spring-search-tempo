package com.oconeco.spring_search_tempo.base.config

import com.oconeco.spring_search_tempo.base.util.UserRoles
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain


@Configuration
class BasicSecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        // creates hashes with {bcrypt} prefix
        return PasswordEncoderFactories.createDelegatingPasswordEncoder()
    }

    @Bean
    @Throws(Exception::class)
    fun authenticationManager(authenticationConfiguration: AuthenticationConfiguration):
            AuthenticationManager = authenticationConfiguration.authenticationManager

    @Bean
    @Throws(Exception::class)
    fun basicFilterChain(http: HttpSecurity): SecurityFilterChain =
            http.cors(Customizer.withDefaults())
            .csrf { csrf -> csrf.ignoringRequestMatchers("/home", "/api/**", "/actuator/**") }
            .authorizeHttpRequests { authorize -> authorize
                .requestMatchers(HttpMethod.GET, "/springUsers").hasAuthority(UserRoles.LOGIN)
                .anyRequest().permitAll() }
            .httpBasic { basic -> basic.realmName("basic realm") }
            .build()

}
