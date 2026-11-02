package com.example.aispringboot.util;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.example.aispringboot.DTO.response.UserLoginResponseDTO;
import com.example.aispringboot.common.ResultCode;
import com.example.aispringboot.config.SecurityConfig;
import com.example.aispringboot.enumClass.UserStatus;
import com.example.aispringboot.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

public class JwtAuthticationFilter extends OncePerRequestFilter {
    @Resource
    private UserService userService;

    @Override
    public boolean shouldNotFilter(HttpServletRequest request)  {
        // 预检请求直接放行，否则 CORS 预检会被当成未认证请求拦掉
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String resultUrl = request.getRequestURI();
        return SecurityConfig.isPublicPath(resultUrl);
    }

    @Override
    public void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
//        从请求头中提取token
        String token = JwtTokenUtil.extractToken(request);
        if (!StringUtils.hasText(token)) {
            reject(response, ResultCode.ACCESS_UNAUTHORIZED);
            return;
        }

//        验证token：过期 / 非法都必须返回 401，绝不能抛到容器变成 500
        JwtTokenUtil.TokenVerifyResult validationResult;
        try {
            validationResult = JwtTokenUtil.validateToken(token);
        } catch (TokenExpiredException e) {
            reject(response, ResultCode.TOKEN_EXPIRED);
            return;
        } catch (JWTVerificationException | IllegalArgumentException e) {
            reject(response, ResultCode.TOKEN_INVALID);
            return;
        } catch (RuntimeException e) {
            logger.error("JWT 校验失败", e);
            reject(response, ResultCode.TOKEN_INVALID);
            return;
        }

        if (validationResult == null || !validationResult.isValid()) {
            reject(response, ResultCode.TOKEN_INVALID);
            return;
        }

        UserLoginResponseDTO.UerDetailResponseDTO user = userService.findUserById(validationResult.getUserId());
        if (user == null || !UserStatus.NORMAL.getCode().equals(user.getStatus())) {
            reject(response, ResultCode.TOKEN_ACCESS_FORBIDDEN);
            return;
        }

        List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                new SimpleGrantedAuthority("ROLE_" + validationResult.getRoleType())
        );
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                validationResult.getUsername(),
                null,
                authorities
        );
        SecurityContextHolder.getContext().setAuthentication(authentication);
        request.setAttribute(JwtTokenUtil.REQUEST_ATTRIBUTE_TOKEN, token);
        request.setAttribute(JwtTokenUtil.REQUEST_ATTRIBUTE_USER_ID, validationResult.getUserId());

        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, ResultCode resultCode) {
        SecurityContextHolder.clearContext();
        ResponseUtil.WriteError(response, resultCode);
    }
}
