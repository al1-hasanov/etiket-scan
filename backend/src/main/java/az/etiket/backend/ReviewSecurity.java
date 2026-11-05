package az.etiket.backend;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class ReviewSecurity {
    @Bean UserDetailsService reviewers(@Value("${review.username:reviewer}") String username,
            @Value("${review.password:}") String password) {
        var users = new InMemoryUserDetailsManager();
        if (!password.isBlank()) {
            if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
                throw new IllegalStateException("REVIEW_ADMIN_PASSWORD must contain 12 to 72 UTF-8 bytes");
            users.createUser(User.withUsername(username).password("{bcrypt}" + new BCryptPasswordEncoder().encode(password))
                .roles("REVIEWER").build());
        }
        return users;
    }

    @Bean SecurityFilterChain security(HttpSecurity http, @Value("${review.password:}") String password) throws Exception {
        http.authorizeHttpRequests(auth -> auth
            .requestMatchers("/review", "/review/**", "/api/v1/review/**").hasRole("REVIEWER")
            .anyRequest().permitAll());
        http.httpBasic(Customizer.withDefaults());
        http.requestCache(cache -> cache.disable());
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/v1/submissions"));
        http.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'")));
        http.addFilterBefore(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                String path = request.getRequestURI();
                if (password.isBlank() && (path.equals("/review") || path.startsWith("/review/")
                        || path.startsWith("/api/v1/review/") || path.equals("/api/v1/submissions") && request.getMethod().equals("POST"))) {
                    response.setStatus(503); response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"REVIEW_NOT_CONFIGURED\"}"); return;
                }
                chain.doFilter(request, response);
            }
        }, BasicAuthenticationFilter.class);
        return http.build();
    }
}
