package com.bee.smartlife.service.impl;

import com.bee.smartlife.entity.BlogComments;
import com.bee.smartlife.mapper.BlogCommentsMapper;
import com.bee.smartlife.service.IBlogCommentsService;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author beemines
 * @since 2021-12-22
 */
@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

}
