package com.example.aispringboot.config;


import cn.hutool.core.text.AntPathMatcher;
import com.example.aispringboot.util.JwtAuthticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableMethodSecurity
@EnableWebSecurity
public class SecurityConfig {
    public static final AntPathMatcher antPathMatcher = new AntPathMatcher();
    private static final String[] PUBLIC_PATHS ={
            "/",
            "/api/test",
            "/api/user/login",
            "/api/user/add",
            "/api/user/refresh",
            "/error",
            "/favicon.ico"
    };

    public static Boolean isPublicPath(String requestUrl){
        for (String publicPath : PUBLIC_PATHS) {
            if (antPathMatcher.match(publicPath, requestUrl)) {
                return true;
            }
        }
        return false;
    }

    @Bean
    public JwtAuthticationFilter jwtAuthticationFilter(){
        return new JwtAuthticationFilter();
    }

    /**
     * 阻止 Spring Boot 把 Filter Bean 另外注册到 Servlet 容器。
     * 否则每个请求会在 Security 过滤器链之外再执行一次 JWT 过滤器（重复查库、重复写响应）。
     */
    @Bean
    public FilterRegistrationBean<JwtAuthticationFilter> jwtFilterRegistration(JwtAuthticationFilter filter) {
        FilterRegistrationBean<JwtAuthticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public PasswordEncoder passwordEncoder(){
        return new BCryptPasswordEncoder();
    }

    /**
     * 提供 UserDetailsService 让 Spring Boot 的 UserDetailsServiceAutoConfiguration 退出，
     * 从而不再打印 "Using generated security password"。
     * 项目实际鉴权由 JwtAuthticationFilter 完成，该 UserDetailsService 不会被使用。
     */
    @Bean
    public UserDetailsService userDetailsService(){
        return username -> {
            throw new UsernameNotFoundException("No UserDetailsService is configured");
        };
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws  Exception{
        http
                .cors(Customizer.withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().permitAll()
                )
//                添加jwt 过滤器
                .addFilterBefore(jwtAuthticationFilter(), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
