package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
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
    Shop shop = queryWithMutes(id);
    if (shop==null) {
      return Result.fail("店铺不存在");
    }
    //返回
    return Result.ok(shop);
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


  public void saveShop2Redis(Long id, Long expireSeconds){
    //1.查询店铺数据
    Shop shop = getById(id);
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
