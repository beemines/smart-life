package com.bee.smartlife.security;

import com.bee.smartlife.utils.UserHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Grants are provisioned explicitly; no endpoint promotes the current user. */
@Service
@RequiredArgsConstructor
public class ShopPermission {
    private final JdbcTemplate jdbc;
    public long currentUser() {
        if (UserHolder.getUser()==null) throw new ApiException(HttpStatus.UNAUTHORIZED,"请先登录");
        return UserHolder.getUser().getId();
    }
    public void requireAdmin() {
        if (!admin(currentUser())) throw new ApiException(HttpStatus.FORBIDDEN,"需要管理员权限");
    }
    public void requireShopWrite(Long shopId) {
        long user=currentUser();
        if (shopId==null || shopId<=0) throw ApiException.badRequest("店铺ID不正确");
        boolean owner=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tb_shop_permission WHERE user_id=? AND shop_id=? AND role='OWNER')",Boolean.class,user,shopId));
        if (!owner && !admin(user)) throw new ApiException(HttpStatus.FORBIDDEN,"无权管理该店铺");
    }
    private boolean admin(long user) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM tb_shop_permission WHERE user_id=? AND shop_id=0 AND role='ADMIN')",Boolean.class,user));
    }
}
