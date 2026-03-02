package com.oconeco.spring_search_tempo.base.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.crypto.factory.PasswordEncoderFactories
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository
import com.oconeco.spring_search_tempo.base.service.BasicUserDetailsService
import javax.sql.DataSource


@Configuration
class BasicSecurityConfig(
    private val dataSource: DataSource,
    private val basicUserDetailsService: BasicUserDetailsService,
    @org.springframework.beans.factory.annotation.Value("\${app.security.remember-me-key}")
    private val rememberMeKey: String,
    @org.springframework.beans.factory.annotation.Value("\${app.security.remember-me-cookie-name}")
    private val rememberMeCookieName: String,
    @org.springframework.beans.factory.annotation.Value("\${app.security.remember-me-validity-days:30}")
    private val rememberMeValidityDays: Int,
    @org.springframework.beans.factory.annotation.Value("\${app.security.remember-me-always:true}")
    private val alwaysRememberMe: Boolean,
    @org.springframework.beans.factory.annotation.Value("\${app.security.remember-me-persistent:true}")
    private val persistentRememberMe: Boolean
) {

    @Bean
    fun passwordEncoder(): PasswordEncoder {
        // creates hashes with {bcrypt} prefix
        return PasswordEncoderFactories.createDelegatingPasswordEncoder()
    }

    @Bean
    @Throws(Exception::class)
    fun authenticationManager(authenticationConfiguration: AuthenticationConfiguration):
            AuthenticationManager = authenticationConfiguration.authenticationManager

    /**
     * Persistent token repository for remember-me functionality.
     * Stores tokens in the persistent_logins table.
     */
    @Bean
    fun persistentTokenRepository(): PersistentTokenRepository {
        val tokenRepository = JdbcTokenRepositoryImpl()
        tokenRepository.setDataSource(dataSource)
        return tokenRepository
    }

    @Bean
    @Throws(Exception::class)
    fun basicFilterChain(http: HttpSecurity): SecurityFilterChain =
            http.cors(Customizer.withDefaults())
            .csrf { csrf -> csrf.ignoringRequestMatchers(
                "/api/**",
                "/actuator/**",
                "/crawlConfigs/**",      // All crawl config actions
                "/emailAccounts/**",     // All email account actions
                "/oneDriveAccounts/**",  // All OneDrive account actions
                "/emailFolders/**",      // All email folder actions
                "/fSFiles/**",           // All file actions
                "/fSFolders/**",         // All folder actions
                "/contentChunks/**",     // All chunk actions
                "/nlp/**",               // NLP processing
                "/batch/**"              // Batch admin actions
            ) }
            .authorizeHttpRequests { authorize -> authorize
                // Public endpoints - accessible without authentication
                .requestMatchers("/login", "/logout", "/error").permitAll()
                .requestMatchers("/css/**", "/js/**", "/images/**", "/webjars/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                // Admin-only endpoints
                .requestMatchers("/springUsers/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/springRoles/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/system/**").hasAuthority("ROLE_ADMIN")
                .requestMatchers("/actuator/**").hasAuthority("ROLE_ADMIN")
                // Everything else requires authentication
                .anyRequest().authenticated()
            }
            .formLogin { form -> form
                .loginPage("/login")
                .defaultSuccessUrl("/", true)
                .permitAll()
            }
            .rememberMe { remember -> remember
                .key(rememberMeKey)
                .userDetailsService(basicUserDetailsService)
                .tokenValiditySeconds(60 * 60 * 24 * rememberMeValidityDays)
                .rememberMeParameter("remember-me")
                .rememberMeCookieName(rememberMeCookieName)
                .alwaysRemember(alwaysRememberMe)
                .apply {
                    // Persistent token mode rotates tokens in DB (more secure).
                    // Stateless mode is more resilient in dev and avoids DB token races.
                    if (persistentRememberMe) {
                        tokenRepository(persistentTokenRepository())
                    }
                }
            }
            .logout { logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID", "SESSION", "remember-me", rememberMeCookieName)
                .permitAll()
            }
            .httpBasic { basic -> basic.realmName("Spring Search Tempo") }
            .build()

}
