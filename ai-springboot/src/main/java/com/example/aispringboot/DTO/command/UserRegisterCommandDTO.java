package com.example.aispringboot.DTO.command;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class UserRegisterCommandDTO {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 3,max = 50, message = "用户名长度不能小于3个字符或大于50个字符")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;
    @NotBlank(message = "邮箱不能为空")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过100个字符")
    private String email;
    @Size(max = 50, message = "昵称长度不能超过50个字符")
    private String nickname;
    @NotBlank(message = "手机号不能为空")
    @Size(max = 11, message = "手机号长度不能超过11个字符")
    @Pattern(regexp = "^1[3456789]\\d{9}$", message = "手机号格式不正确")
    private String phone;
    @NotBlank(message = "密码不能为空")
    @Size(min = 6,max = 50, message = "密码长度不能小于6个字符或大于50个字符")
    private String password;
    @NotBlank(message = "确认密码不能为空")
    private String confirmPassword;
    private Integer gender;
    private Integer userType = 1;
    private LocalDate birthday;

}
