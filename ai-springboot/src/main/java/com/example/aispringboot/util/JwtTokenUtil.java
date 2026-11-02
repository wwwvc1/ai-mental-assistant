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

    public static final String CLAIM_TOKEN_TYPE = "tokenType";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";
    public static final String REQUEST_ATTRIBUTE_TOKEN = "jwtToken";
    public static final String REQUEST_ATTRIBUTE_USER_ID = "jwtUserId";

    private static ApplicationContext applicationContext;
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }
    private static JwtConfig getJwtConfig() {
        return applicationContext.getBean(JwtConfig.class);
    }

    /**
     * 生成访问令牌（默认 24 小时）
     */
    public static String generateToken(Long userId, String username, Integer roleType) {
        return generate(userId, username, roleType, TYPE_ACCESS, getJwtConfig().getExpiration());
    }

    /**
     * 生成刷新令牌（默认 7 天）
     */
    public static String generateRefreshToken(Long userId, String username, Integer roleType) {
        return generate(userId, username, roleType, TYPE_REFRESH, getJwtConfig().getRefreshExpiration());
    }

    private static String generate(Long userId, String username, Integer roleType, String tokenType, long ttlMillis) {
        try {
            JwtConfig jwtConfig = getJwtConfig();
            Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());
            Date expiration = new Date(System.currentTimeMillis() + ttlMillis);
            return JWT.create()
                    .withClaim("userId", userId)
                    .withClaim("username", username)
                    .withClaim("roleType", roleType)
                    .withClaim(CLAIM_TOKEN_TYPE, tokenType)
                    .withExpiresAt(expiration)
                    .withIssuedAt(new Date())
                    .withIssuer(ISSUER)
                    .sign(algorithm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

//    提取token：优先按配置读取 Authorization 头（支持 Bearer 前缀），兼容旧的 token 头
    public static String extractToken(HttpServletRequest request){
        if (request == null) {
            return null;
        }
        JwtConfig jwtConfig = getJwtConfig();
        String headerName = StringUtils.hasText(jwtConfig.getHeader()) ? jwtConfig.getHeader() : "Authorization";
        String raw = request.getHeader(headerName);
        if (!StringUtils.hasText(raw)) {
            raw = request.getHeader("token");
        }
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String prefix = StringUtils.hasText(jwtConfig.getTokenPrefix()) ? jwtConfig.getTokenPrefix().trim() : "Bearer";
        if (raw.regionMatches(true, 0, prefix, 0, prefix.length())) {
            raw = raw.substring(prefix.length()).trim();
        }
        return StringUtils.hasText(raw) ? raw : null;
    }


//    获取token
    public static String getCurrentToken(){
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null){
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object attribute = request.getAttribute(REQUEST_ATTRIBUTE_TOKEN);
        if (attribute instanceof String token && StringUtils.hasText(token)) {
            return token;
        }
        return extractToken(request);
    }

    /**
     * 获取当前登录用户 id（优先读取过滤器写入的请求属性）
     */
    public static Long getCurrentUserId(){
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null){
            return null;
        }
        HttpServletRequest request = attributes.getRequest();
        Object userId = request.getAttribute(REQUEST_ATTRIBUTE_USER_ID);
        if (userId instanceof Long id){
            return id;
        }
        String token = getCurrentToken();
        if (!StringUtils.hasText(token)) {
            return null;
        }
        try {
            return veryfyToken(token).getClaim("userId").asLong();
        } catch (JWTVerificationException e) {
            return null;
        }
    }

//    验证访问令牌
    public static TokenVerifyResult validateToken(String token){
        return build(veryfyToken(token, TYPE_ACCESS));
    }

//    验证刷新令牌
    public static TokenVerifyResult validateRefreshToken(String token){
        return build(veryfyToken(token, TYPE_REFRESH));
    }

    private static TokenVerifyResult build(DecodedJWT jwt) {
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

//    验证token有效性（默认按访问令牌校验）
    public static DecodedJWT veryfyToken(String token){
        return veryfyToken(token, TYPE_ACCESS);
    }

    /**
     * 验证 token 有效性并校验类型。
     * 旧 token 没有 tokenType 声明，按访问令牌处理；刷新令牌必须显式声明类型。
     */
    public static DecodedJWT veryfyToken(String token, String expectedType){
        if (!StringUtils.hasText(token)) {
            throw new JWTVerificationException("token不能为空");
        }
        JwtConfig jwtConfig = getJwtConfig();
        Algorithm algorithm = Algorithm.HMAC256(jwtConfig.getSecret());
        JWTVerifier verifier = JWT.require(algorithm)
                .withIssuer(ISSUER)
                .build();
        DecodedJWT jwt = verifier.verify(token);

        String actualType = jwt.getClaim(CLAIM_TOKEN_TYPE).asString();
        boolean typeMatches = TYPE_REFRESH.equals(expectedType)
                ? TYPE_REFRESH.equals(actualType)
                : !TYPE_REFRESH.equals(actualType);
        if (!typeMatches) {
            throw new JWTVerificationException("token类型不匹配");
        }
        return jwt;
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
