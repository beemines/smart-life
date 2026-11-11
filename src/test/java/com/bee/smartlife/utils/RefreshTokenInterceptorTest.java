package com.bee.smartlife.utils;

import com.bee.smartlife.dto.UserDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefreshTokenInterceptorTest {
    @AfterEach void cleanup(){UserHolder.removeUser();}
    @Test void failedRenewalCannotLeakIdentityIntoTheNextTokenlessRequest() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("login:token:admin")).thenReturn(Map.of("id","1","nickName","admin"));
        when(redis.expire(anyString(),anyLong(),eq(TimeUnit.MINUTES))).thenThrow(new RedisConnectionFailureException("fixture renewal failed"));
        var interceptor=new RefreshTokenInterceptor(redis);var request=new MockHttpServletRequest();request.addHeader("authorization","admin");
        assertThatThrownBy(()->interceptor.preHandle(request,new MockHttpServletResponse(),new Object())).isInstanceOf(RedisConnectionFailureException.class);
        // Spring cannot call this interceptor's afterCompletion if preHandle failed.
        assertThat(UserHolder.getUser()).isNull();
        var anonymous=new MockHttpServletRequest();interceptor.preHandle(anonymous,new MockHttpServletResponse(),new Object());
        assertThat(new LoginInterceptor().preHandle(anonymous,new MockHttpServletResponse(),new Object())).isFalse();
    }
    @Test void entryClearsOldContextAndARevokedSessionIsNotAdmitted() throws Exception {
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") HashOperations<String,Object,Object> hashes=mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        var old=new UserDTO();old.setId(1L);UserHolder.saveUser(old);
        var interceptor=new RefreshTokenInterceptor(redis);
        interceptor.preHandle(new MockHttpServletRequest(),new MockHttpServletResponse(),new Object());
        assertThat(UserHolder.getUser()).isNull();
        when(hashes.entries("login:token:revoked")).thenReturn(Map.of("id","1"));
        when(redis.expire(anyString(),anyLong(),eq(TimeUnit.MINUTES))).thenReturn(false);
        var revoked=new MockHttpServletRequest();revoked.addHeader("authorization","revoked");
        interceptor.preHandle(revoked,new MockHttpServletResponse(),new Object());assertThat(UserHolder.getUser()).isNull();
    }
}
