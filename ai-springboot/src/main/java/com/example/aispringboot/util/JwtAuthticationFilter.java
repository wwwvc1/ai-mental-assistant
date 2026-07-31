package com.example.aispringboot.util;

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
        String resultUrl = request.getRequestURI();
        return SecurityConfig.isPublicPath(resultUrl);
    }
    @Override
    public void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        String resultUrl = request.getRequestURI();
        String method = request.getMethod();
//        从请求头中提取token
        String token = JwtTokenUtil.extractToken(request);
        if (StringUtils.hasText(token)) {
//            验证token
            JwtTokenUtil.TokenVerifyResult validationResult = JwtTokenUtil.validateToken(token);
            if (validationResult!=null&&validationResult.isValid()){
                UserLoginResponseDTO.UerDetailResponseDTO user = userService.getUserById(validationResult.getUserId());
                if (user!=null&& UserStatus.NORMAL.getCode().equals(user.getStatus())){
                    List<SimpleGrantedAuthority> authorities = Collections.singletonList(
                            new SimpleGrantedAuthority("ROLE_"+validationResult.getRoleType())
                    );
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            validationResult.getUsername(),
                            null,
                            authorities
                    );
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    request.setAttribute("jwttoken", token);
                }else {
                    clearSecurityContext();
                    ResponseUtil.WriteError(response, ResultCode.TOKEN_ACCESS_FORBIDDEN);
                }
            }else {
                clearSecurityContext();
                ResponseUtil.WriteError(response, ResultCode.TOKEN_INVALID);
            }

        }else {
            clearSecurityContext();
            ResponseUtil.WriteError(response, ResultCode.ACCESS_UNAUTHORIZED);
            return;
        }
        chain.doFilter(request, response);


    }
    private void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }
}
