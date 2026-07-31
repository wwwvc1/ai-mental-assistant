package com.example.aispringboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.aispringboot.DTO.command.UserLoginCommandDTO;
import com.example.aispringboot.DTO.command.UserRegisterCommandDTO;
import com.example.aispringboot.DTO.response.UserLoginResponseDTO;
import com.example.aispringboot.common.Result;
import com.example.aispringboot.entity.User;
import com.example.aispringboot.enumClass.UserType;
import com.example.aispringboot.exception.BusionessException;
import com.example.aispringboot.mapper.UserMapper;
import com.example.aispringboot.service.convert.UserConvert;
import com.example.aispringboot.util.JwtTokenUtil;
import jakarta.annotation.Resource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {
    @Resource
    private UserMapper userMapper;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    public UserLoginResponseDTO login(UserLoginCommandDTO userLoginCommandDTO){
        LambdaQueryWrapper<User> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(User::getUsername, userLoginCommandDTO.getUsername())
                .or()
                .eq(User::getEmail, userLoginCommandDTO.getUsername());

        User user = userMapper.selectOne(queryWrapper);
        if(user == null){
            throw new BusionessException("用户不存在");
        }
        String inputPassword = userLoginCommandDTO.getPassword().trim();
        if (!passwordEncoder.matches(inputPassword, user.getPassword())){
            throw new BusionessException("密码错误");
        }

        if (!user.isActive()){
            throw new BusionessException("用户被禁用,请联系管理员");
        }
            String token = JwtTokenUtil.generateToken(user.getId(), user.getUsername(), user.getUserType());
        UserLoginResponseDTO.UerDetailResponseDTO userinfo = UserConvert.entityToDetailResponse( user);

        return UserConvert.entityToLoginResponse(token, userinfo);
    }
    public UserLoginResponseDTO.UerDetailResponseDTO register(UserRegisterCommandDTO userRegisterCommandDTO){
//        校验密码是否一致
        if(!userRegisterCommandDTO.getPassword().equals(userRegisterCommandDTO.getConfirmPassword())){
            throw new BusionessException("密码不一致");
        }
//        校验用户名是否存在
        LambdaQueryWrapper<User> UsernameQuery = new LambdaQueryWrapper<>();
        UsernameQuery.eq(User::getUsername, userRegisterCommandDTO.getUsername());
        if(userMapper.selectCount(UsernameQuery) > 0){
            throw new BusionessException("用户名已存在");
        }
        LambdaQueryWrapper<User> EmailQuery = new LambdaQueryWrapper<>();
        EmailQuery.eq(User::getEmail, userRegisterCommandDTO.getEmail());
        if(userMapper.selectCount(EmailQuery) > 0){
            throw new BusionessException("邮箱已存在");
        }

        if(!UserType.isValidCode(userRegisterCommandDTO.getUserType())){
            throw new BusionessException("用户类型错误");
        }

        String password = userRegisterCommandDTO.getPassword();
        password = passwordEncoder.encode(password);
        User user = UserConvert.regiaterCommandToEntity(userRegisterCommandDTO, password);
        userMapper.insert(user);
        return UserConvert.entityToDetailResponse(user);
    }
    public  UserLoginResponseDTO.UerDetailResponseDTO getUserById(Long userId){
        User user = userMapper.selectById(userId);
        if (user == null){
            throw new BusionessException("用户不存在");
        }
        return UserConvert.entityToDetailResponse(user);
    }
}
