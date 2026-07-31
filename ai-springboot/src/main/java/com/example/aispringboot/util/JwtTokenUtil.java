package com.example.aispringboot.util;


import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.aispringboot.config.JwtConfig;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Getter;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;


import java.util.Date;

@Component
public class JwtTokenUtil implements ApplicationContextAware {
    private static final String ISSUER = "mental-health-assistant";
    private static ApplicationContext applicationContext;
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    private static JwtConfig getJwtConfig() {
        return applicationContext.getBean(JwtConfig.class);
    }
    public static String generateToken(Long userId, String username, Integer roleType) {
        try {
            JwtConfig jwtConfig = getJwtConfig();
            Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());
            Date expiration = new Date(System.currentTimeMillis()+jwtConfig.getExpiration());
            String token =
                    JWT.create()
                        .withClaim("userId", userId)
                        .withClaim("username", username)
                        .withClaim("roleType", roleType)
                        .withExpiresAt(expiration)
                        .withIssuedAt(new Date())
                        .withIssuer(ISSUER)
                        .sign(algorithm);
            return token;
        } catch (Exception e) {
            throw new RuntimeException(e);

        }
    }
//    提取token
    public static String extractToken(HttpServletRequest request){
        if (request == null) {
            return null;
        }
        String tokenHeader = request.getHeader("token");
        if (StringUtils.hasText(tokenHeader)) {
            return tokenHeader;
        }
        return null;
    }


//    获取token
    public static String getCurrentToken(){
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes!=null){
            HttpServletRequest request = attributes.getRequest();
            String token = (String) request.getAttribute("jwtToken");
            if (token != null) {
                return token;
            }
            String headerToken = extractToken(request);
            return headerToken;
        }
        return null;
    }
//    验证token
    public static TokenVerifyResult validateToken(String token){
        DecodedJWT jwt = veryfyToken(token);
        Long userId = jwt.getClaim("userId").asLong();
        String username = jwt.getClaim("username").asString();
        Integer roleType = null;
        try{
            roleType = jwt.getClaim("roleType").asInt();
        }catch (Exception e){
            String roleTypeStr = jwt.getClaim("roleType").asString();
            if (StringUtils.hasText(roleTypeStr)){
                roleType = Integer.parseInt(roleTypeStr);
            }
        }
        if (userId!=null&&StringUtils.hasText(username)&&roleType!=null){
            return new TokenVerifyResult(userId, username, roleType, true);
        }
        return null;
    }

//    验证token有效性
    public static DecodedJWT veryfyToken(String token){
        if (!StringUtils.hasText(token)) {
            throw new JWTVerificationException("token不能为空");
        }
        JwtConfig jwtConfig = getJwtConfig();
        Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());
        JWTVerifier verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
        return verifier.verify(token);
    }

//    token验证返回
    @Getter
    public static class TokenVerifyResult {
        private final Long userId;
        private final String username;
        private final Integer roleType;
        private final boolean valid;

    public TokenVerifyResult(Long userId, String username, Integer roleType, boolean valid) {
        this.userId = userId;
        this.username = username;
        this.roleType = roleType;
        this.valid = valid;
    }
}
}
