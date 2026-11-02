package com.example.aispringboot.service.convert;

import com.example.aispringboot.DTO.command.UserRegisterCommandDTO;
import com.example.aispringboot.DTO.response.UserLoginResponseDTO;
import com.example.aispringboot.entity.User;
import com.example.aispringboot.enumClass.UserStatus;

import java.time.LocalDateTime;

public class UserConvert {

    public static UserLoginResponseDTO.UerDetailResponseDTO entityToDetailResponse(User user){
        return UserLoginResponseDTO.UerDetailResponseDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .phone(user.getPhone())
                .gender(user.getGender())
                .genderDisplayName(getGenderDisplayName(user.getGender()))
                .birthday(user.getBirthday())
                .userType(user.getUserType())
                .userTypeDisplayName(user.getUserTypeDisplayName())
                .status(user.getStatus())
                .statusDisplayName(user.getStatusDisplayName())
                .displayName(user.getDisplayName())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();


    }

    public static UserLoginResponseDTO entityToLoginResponse(String token, String refreshToken,
                                                             UserLoginResponseDTO.UerDetailResponseDTO userinfo){
        return UserLoginResponseDTO.builder()
                .userinfo(userinfo)
                .token(token)
                .refreshToken(refreshToken)
                .roletype(userinfo.getUserType().toString())
                .build();
    }

    public static User regiaterCommandToEntity(UserRegisterCommandDTO userRegisterCommandDTO , String password){
        return User.builder()
                .username(userRegisterCommandDTO.getUsername())
                .email(userRegisterCommandDTO.getEmail())
                .phone(userRegisterCommandDTO.getPhone())
                .password(password)
                .nickname(userRegisterCommandDTO.getNickname())
                .phone(userRegisterCommandDTO.getPhone())
                .gender(userRegisterCommandDTO.getGender())
                .birthday(userRegisterCommandDTO.getBirthday())
                .userType(userRegisterCommandDTO.getUserType())
                .status(UserStatus.NORMAL.getCode())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 获取性别显示名称
     * @param gender 性别代码
     * @return 性别显示名称
     */
    private static String getGenderDisplayName(Integer gender) {
        if (gender == null) {
            return "未知";
        }
        switch (gender) {
            case 1:
                return "男";
            case 2:
                return "女";
            default:
                return "未知";
        }
    }

}
