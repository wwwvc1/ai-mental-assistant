package com.example.aispringboot.controller;

import com.example.aispringboot.DTO.command.RefreshTokenCommandDTO;
import com.example.aispringboot.DTO.command.UserLoginCommandDTO;
import com.example.aispringboot.DTO.command.UserRegisterCommandDTO;
import com.example.aispringboot.DTO.response.TokenRefreshResponseDTO;
import com.example.aispringboot.DTO.response.UserLoginResponseDTO;
import com.example.aispringboot.common.Result;
import com.example.aispringboot.common.ResultCode;
import com.example.aispringboot.service.UserService;
import com.example.aispringboot.util.JwtTokenUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
public class User {
    @Resource
    private UserService userService;

    @PostMapping("/login")
    public Result<UserLoginResponseDTO> login(@Valid  @RequestBody UserLoginCommandDTO userLoginCommandDTO){

        UserLoginResponseDTO result = userService.login(userLoginCommandDTO);

        return Result.ok(result);
    }

    @PostMapping("/add")
    public Result<UserLoginResponseDTO.UerDetailResponseDTO> register(@Valid @RequestBody UserRegisterCommandDTO userRegisterCommandDTO ){
        UserLoginResponseDTO.UerDetailResponseDTO result = userService.register(userRegisterCommandDTO);
        return Result.ok(result);
    }

    @PostMapping("/refresh")
    public Result<TokenRefreshResponseDTO> refresh(@Valid @RequestBody RefreshTokenCommandDTO refreshTokenCommandDTO){
        TokenRefreshResponseDTO result = userService.refresh(refreshTokenCommandDTO.getRefreshToken());
        return Result.ok(result);
    }

    @GetMapping("/current")
    public Result<UserLoginResponseDTO.UerDetailResponseDTO> getCurrentUser(){
//        如何在token中获取当前用户id
        Long userId = JwtTokenUtil.getCurrentUserId();
        if (userId == null) {
            return Result.error(ResultCode.UNAUTHORIZED.getcode(), ResultCode.UNAUTHORIZED.getmsg());
        }
        UserLoginResponseDTO.UerDetailResponseDTO result = userService.getUserById(userId);
        return Result.ok(result);
    }

    @PostMapping("/logout")
    public Result<Void> logout(){
        // JWT 无状态，服务端无需额外处理，由前端清除 token
        return Result.ok(null);
    }
}
