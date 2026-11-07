package com.bee.smartlife.service.impl;

import com.bee.smartlife.entity.UserInfo;
import com.bee.smartlife.mapper.UserInfoMapper;
import com.bee.smartlife.service.IUserInfoService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author beemines
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
