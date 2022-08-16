package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

/**
 * The type Cache client.
 * @author ZH
 */
@Slf4j
@Component
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    /**
     * Instantiates a new Cache client.
     *
     * @param stringRedisTemplate the string redis template
     */
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    /**
     * Set.
     *
     * @param key      the key
     * @param value    the value
     * @param time     the time
     * @param timeUnit the time unit
     */
    //写入
    public void set(String key, Object value, Long time, TimeUnit timeUnit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,timeUnit);
    }

    /**
     * Set with logical expire.
     *
     * @param key      the key
     * @param value    the value
     * @param time     the time
     * @param timeUnit the time unit
     */
    //设置逻辑过期时间并写入redis
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit){
        //设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * Query with pass through r.
     *
     * @param <R>        the type parameter
     * @param <ID>       the type parameter
     * @param keyPrefix  the key prefix
     * @param id         the id
     * @param type       the type
     * @param dbFallback the db fallback
     * @param time       the time
     * @param unit       the unit
     * @return the r
     */
    //缓存穿透
    public <R,ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                         Function<ID,R> dbFallback,Long time,TimeUnit unit){

        String key = keyPrefix + id;
        //1.从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
        //2.判断是否存在
        //3.存在,直接返回
        if (StrUtil.isNotBlank(json)){
            return JSONUtil.toBean(json, type);
        } else if (json!=null){ //判断命中的是否为空值
            //没命中空值
            return null;
        }
        //4.不存在,根据id查询数据库

        R r = dbFallback.apply(id);

        //5.没查到,返回错误
        if (r==null){
            //将空值写入redis
            this.set(key,"",time,unit);
            //stringRedisTemplate.opsForValue().set(key,"",time, unit);
            //返回错误信息
            return null;
        }
        //6.查到了,写到redis里,添加TTL到期删除
        this.set(key,r,time,unit);

        return r;
    }


    //逻辑过期缓存击穿
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * Query with logical expire r.
     *
     * @param <R>           the type parameter
     * @param <ID>          the type parameter
     * @param keyPrefix     the key prefix
     * @param lockKeyPrefix the lock key prefix
     * @param id            the id
     * @param type          the type
     * @param dbFallback    the db fallback
     * @param time          the time
     * @param unit          the unit
     * @return the r
     */
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,String lockKeyPrefix,ID id,Class<R> type,
                                           Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix + id;
        //1.从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(json)){
            //3.未命中,返回空
            return null;
        }

        //4.命中,先把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //这里可以使用泛型,我不会
        JSONObject data = (JSONObject) redisData.getData();
        R r = JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1.未过期,直接返回店铺信息
            return r;
        }

        //5.2.已过期,需要缓存重建

        //6.缓存重建

        //6.1.获取互斥锁
        String lockKey = lockKeyPrefix +id;
        //6.2.判断是否获取成功
        boolean isLock = tryLock(lockKey);
        if (isLock){
            //6.3.成功,开启独立线程实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockKey);
                }

            });
        }

        //6.4.返回过期的商铺信息
        return r;
    }

    /**
     *获取锁(伪 setIfAbsent
     */
    private boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    /**
     * Unlock.
     *
     * @param key the key
     */
    //释放锁(伪 delete
    public void unlock(String key){
        stringRedisTemplate.delete(key);
    }


    /**
     * Query with mutes r.
     *
     * @param <R>           the type parameter
     * @param <ID>          the type parameter
     * @param keyPrefix     the key prefix
     * @param lockKeyPrefix the lock key prefix
     * @param id            the id
     * @param type          the type
     * @param dbFallback    the db fallback
     * @param time          the time
     * @param unit          the unit
     * @return the r
     */
    //互斥锁缓存击穿
    public <R,ID> R queryWithMutes(String keyPrefix,String lockKeyPrefix,ID id,Class<R> type,
                                   Function<ID,R> dbFallback,Long time,TimeUnit unit){
        String key = keyPrefix +id;
        //1.从redis查询商户缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        //3.存在,直接返回
        if (StrUtil.isNotBlank(json)){

            return JSONUtil.toBean(json, type);
        } else if (json !=null){ //判断命中的是否为空值
            //没命中空值
            return null;
        }

        //4.实现缓存重建
        //4.1 获取互斥锁
        String lockKey = lockKeyPrefix+id;
        //ctrl+alt+t
        R r = null;
        try {
            boolean lock = tryLock(lockKey);
            //4.2 判断是否获取成功
            if (!lock) {
                //4.3 失败,休眠并重试
                Thread.sleep(50);
                //调用自己
                return queryWithMutes(keyPrefix,lockKeyPrefix,id,type,dbFallback,time,unit);
            }
            //4.4 成功,根据id查询数据库

            //模拟缓存重建延时
            Thread.sleep(200);
            r = dbFallback.apply(id);
            //5.没查到,返回错误
            if (r==null){
                //将空值写入redis
                this.set(key,"",time,unit);
                //stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
                //返回错误信息
                return null;
            }
            //6.查到了,写到redis里,添加TTL到期删除
            this.set(key,r,time,unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //7.释放互斥锁
            unlock(lockKey);
        }

        //8.返回
        return r;
    }
}
