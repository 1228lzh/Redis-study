package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

  @Resource
  private StringRedisTemplate stringRedisTemplate;


  @Override
  public Result queryById(Long id) {
    //缓存穿透
    //Shop shop = queryWithPassThrough(id);

    //互斥锁解决缓存击穿
    //Shop shop = queryWithMutes(id);

    //逻辑过期解决缓存击穿
    Shop shop = queryWithLogicalExpire(id);
    if (shop==null) {
      return Result.fail("店铺不存在");
    }
    //返回
    return Result.ok(shop);
  }

  private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

  public Shop queryWithLogicalExpire(Long id){
    //1.从redis查询商户缓存
      String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //2.判断是否存在
    if (StrUtil.isBlank(shopJson)){
      //3.未命中,返回空
      return null;
    }

    //4.命中,先把json反序列化成对象
    RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
    //这里可以使用泛型,我不会
    JSONObject data = (JSONObject) redisData.getData();
    Shop shop = JSONUtil.toBean(data, Shop.class);
    LocalDateTime expireTime = redisData.getExpireTime();
    //5.判断是否过期
    if (expireTime.isAfter(LocalDateTime.now())){
      //5.1.未过期,直接返回店铺信息
      return shop;
    }

    //5.2.已过期,需要缓存重建

    //6.缓存重建

    //6.1.获取互斥锁
    String lockKey = LOCK_SHOP_KEY +id;
    //6.2.判断是否获取成功
    boolean isLock = tryLock(lockKey);
    if (isLock){
      //6.3.成功,开启独立线程实现缓存重建
      CACHE_REBUILD_EXECUTOR.submit(()->{
        try {
          //重建缓存
          this.saveShop2Redis(id,20L);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }finally {
          //释放锁
          unlock(lockKey);
        }

      });
    }

    //6.4.返回过期的商铺信息
    return shop;
  }


  public Shop queryWithMutes(Long id){
    //1.从redis查询商户缓存
    String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //2.判断是否存在
    //3.存在,直接返回
    if (StrUtil.isNotBlank(shopJson)){
      Shop shop = JSONUtil.toBean(shopJson, Shop.class);
      return shop;
    } else if (shopJson!=null){ //判断命中的是否为空值
      //没命中空值
      return null;
    }

    //4.实现缓存重建
    //4.1 获取互斥锁
    String lockKey = "lock:shop:"+id;
    Shop shop = null;
    //ctrl+alt+t
    try {
      boolean lock = tryLock(lockKey);
      //4.2 判断是否获取成功
      if (!lock) {
        //4.3 失败,休眠并重试
        Thread.sleep(50);
        return queryWithMutes(id);
      }
      //4.4 成功,根据id查询数据库
      shop = getById(id);
      //模拟缓存重建延时
      Thread.sleep(200);

      //5.没查到,返回错误
      if (shop==null){
        //将空值写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
        //返回错误信息
        return null;
      }
      //6.查到了,写到redis里,添加TTL到期删除
      stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }finally {
      //7.释放互斥锁
      unlock(lockKey);
    }

    //8.返回
    return shop;
  }
  //解决缓存穿透
  public Shop queryWithPassThrough(Long id){
    //1.从redis查询商户缓存
    String shopJson = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + id);
    //2.判断是否存在
    //3.存在,直接返回
    if (StrUtil.isNotBlank(shopJson)){
      Shop shop = JSONUtil.toBean(shopJson, Shop.class);
      return shop;
    } else if (shopJson!=null){ //判断命中的是否为空值
      //没命中空值
      return null;
    }
    //4.不存在,根据id查询数据库
    Shop shop = getById(id);
    //5.没查到,返回错误
    if (shop==null){
      //将空值写入redis
      stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,"",CACHE_NULL_TTL, TimeUnit.MINUTES);
      //返回错误信息
      return null;
    }
    //6.查到了,写到redis里,添加TTL到期删除
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

    return shop;
  }

  //获取锁(伪 setIfAbsent
  private boolean tryLock(String key){
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
  }
  //释放锁(伪 delete
  public void unlock(String key){
    stringRedisTemplate.delete(key);
  }


  public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
    //1.查询店铺数据
    Shop shop = getById(id);
    //模拟延迟
    Thread.sleep(200);
    //2.封装逻辑过期时间
    RedisData redisData = new RedisData();
    redisData.setData(shop);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
    //3.写入redis
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
  }




























  @Override
  @Transactional//事务
  public Result update(Shop shop) {

    Long id = shop.getId();
    if (id==null) {
      return Result.fail("店铺id为空!");
    }
    //1.更新数据库
    updateById(shop);
    //2.删除缓存
    stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
    return Result.ok();
  }
}
