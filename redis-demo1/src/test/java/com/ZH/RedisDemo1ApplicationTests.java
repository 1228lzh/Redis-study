package com.ZH;

import org.junit.jupiter.api.Test;


import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;


import javax.annotation.Resource;

@SpringBootTest
class RedisDemo1ApplicationTests {
    @Resource
    private RedisTemplate redisTemplate;//存在字符串编译问题


    @Test
    void testString() {
        // 写入一条String数据
        redisTemplate.opsForValue().set("name2", "虎哥2");
        // 获取string数据
        Object name = redisTemplate.opsForValue().get("name");
        System.out.println("name2 = " + name);
    }
    //key:\xAC\xED\x00\x05t\x00\x05name2
    //value:\xAC\xED\x00\x05t\x00\x07\xE8\x99\x8E\xE5\x93\xA52

}
