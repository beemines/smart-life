package com.bee.smartlife.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.bee.smartlife.dto.LoginFormDTO;
import com.bee.smartlife.dto.Result;
import com.bee.smartlife.entity.User;

import jakarta.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author beemines
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {
    /**
     * 发送手机验证码
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     * @param loginForm 登录参数，包含手机号、验证码；或者手机号、密码
     */
    Result login(LoginFormDTO loginForm, HttpSession session);


    Result sign();

    Result signCount();
}
