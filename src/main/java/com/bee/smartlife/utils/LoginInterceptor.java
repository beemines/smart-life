package com.bee.smartlife.utils;

import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;
import com.bee.smartlife.security.PublicRead;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("GET".equals(request.getMethod()) && handler instanceof HandlerMethod method && method.hasMethodAnnotation(PublicRead.class)) return true;
        // 1.判断是否需要拦截（ThreadLocal中是否有用户）
        if(UserHolder.getUser() == null){
            // 没有用户，需要拦截，设置状态码
            response.setStatus(401);
            return false;
        }
        // 有用户，则放行
        return true;
    }
}
