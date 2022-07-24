package com.ZH.test;

import com.ZH.utils.JedisConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;

import java.util.Map;

public class jedisTest {
    private Jedis jedis;

    @BeforeEach
    void setUp(){
        //1.建立连接
        //jedis = new Jedis("192.168.1.35", 6379);
        jedis = JedisConnectionFactory.getJedis();
        jedis.auth("123456");
        jedis.select(0);
    }

    @Test
    void TestString(){
        //存入数据
        String result = jedis.set("name", "虎哥");
        System.out.println("result = " + result);

        String name = jedis.get("name");
        System.out.println("name = " + name);

    }

    @Test
    void testHash(){
        //插入hash数据
        jedis.hset("user:1","name","张三");
        jedis.hset("user:1","age","21");

        //获取
        String name = jedis.hget("user:1", "name");
        System.out.println("name = " + name);

        Map<String, String> map = jedis.hgetAll("user:1");
        System.out.println("map = " + map);
    }
    @AfterEach
    void tearDown(){
        if (jedis!=null){
            jedis.close();
        }
    }
}
