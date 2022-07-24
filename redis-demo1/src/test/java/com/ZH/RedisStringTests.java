package com.ZH;

import com.ZH.redis.pojo.User;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.lang.runtime.ObjectMethods;

@SpringBootTest
class RedisStringTests {


    @Resource
    private StringRedisTemplate stringRedisTemplate;//只能对字符串进行操作


    @Test
    void testString(){
        //写入一条数据
        stringRedisTemplate.opsForValue().set("name1","虎哥");

        String name = stringRedisTemplate.opsForValue().get("name");
        System.out.println("name = " + name);
    }


    private static final ObjectMapper mapper = new ObjectMapper();
    @Test
    void testSaveUser() throws JsonProcessingException {
        //创建对象
        User user = new User("虎哥", 21);
        //手动序列化
        //String json = mapper.writeValueAsString(user);
        String json = JSON.toJSONString(user);//对象转字符串 JSON.toJSONString(user)

        //写入数据
        stringRedisTemplate.opsForValue().set("user:200",json);

        //获取数据
        String jsonUser = stringRedisTemplate.opsForValue().get("user:200");
        System.out.println(jsonUser);//{"age":21,"name":"虎哥"}

        //手动反序列化
        //User user1 = mapper.readValue(jsonUser, User.class);
        User user1 = JSON.parseObject(jsonUser, User.class);//字符串转对象,JSON.parseObject()
        System.out.println("user1 = " + user1);//user1 = User(name=虎哥, age=21)

    }

}
