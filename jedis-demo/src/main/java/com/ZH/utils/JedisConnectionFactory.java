package com.ZH.utils;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class JedisConnectionFactory {
    private static final JedisPool jedispool;

    static {
        //配置连接池
        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(8);//最大连接数
        poolConfig.setMaxIdle(8);//常备空闲连接数
        poolConfig.setMinIdle(0);
        poolConfig.setMaxWaitMillis(1000);//当没有连接可用时,等待时长,默认-1,一直等
        //创建连接池对象
        jedispool = new JedisPool(poolConfig, "192.168.1.35", 6379, 1000, "123456");
    }

    public static Jedis getJedis(){
        return jedispool.getResource();
    }
}
