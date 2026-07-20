package site.yuqi.admin.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import site.yuqi.admin.security.AdminAuthFilter;
import site.yuqi.admin.security.InternalTokenFilter;

import java.util.Arrays;
import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebConfig {

    @Value("${portfolio.admin.allowed-origins:http://localhost:3000}")
    private String allowedOrigins;

    @Bean
    public FilterRegistrationBean<AdminAuthFilter> adminAuthFilterRegistration(AdminAuthFilter filter) {
        FilterRegistrationBean<AdminAuthFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(10);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<InternalTokenFilter> internalTokenFilterRegistration(InternalTokenFilter filter) {
        FilterRegistrationBean<InternalTokenFilter> reg = new FilterRegistrationBean<>(filter);
        reg.addUrlPatterns("/*");
        reg.setOrder(11);
        return reg;
    }

    /**
     * CORS filter must run BEFORE {@link AdminAuthFilter} (order 10). Otherwise
     * unauthenticated CORS preflight (OPTIONS) requests get rejected with 401
     * and no Access-Control-Allow-Origin header, and the browser blocks the
     * actual request. CorsFilter short-circuits OPTIONS preflights with a 200
     * and the appropriate headers when a matching CorsConfiguration exists.
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(Arrays.stream(allowedOrigins.split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Admin-Secret"));
        cfg.setExposedHeaders(List.of("Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);
        source.registerCorsConfiguration("/**", cfg);

        FilterRegistrationBean<CorsFilter> reg = new FilterRegistrationBean<>(new CorsFilter(source));
        reg.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return reg;
    }
}
