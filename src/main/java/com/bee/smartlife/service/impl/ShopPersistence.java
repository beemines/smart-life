package com.bee.smartlife.service.impl;

import com.bee.smartlife.entity.Shop;
import com.bee.smartlife.mapper.ShopMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShopPersistence {
    private final ShopMapper shops;
    private final JdbcTemplate jdbc;
    @Transactional(readOnly=true,propagation=Propagation.REQUIRES_NEW)
    public Shop load(Long id) {return shops.selectById(id);}
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Long create(Shop shop) {
        if(shops.insert(shop)!=1)throw new IllegalStateException("店铺保存失败");
        changed(shop.getId());return shop.getId();
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean update(Shop shop) {
        if(shops.updateById(shop)!=1)return false;
        changed(shop.getId());return true;
    }
    private void changed(Long id) {
        jdbc.update("INSERT INTO tb_shop_cache_sync(shop_id,version,synced_version) VALUES (?,1,0) ON DUPLICATE KEY UPDATE version=version+1,last_error=NULL",id);
    }
}
