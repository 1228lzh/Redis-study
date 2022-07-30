package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result getList() {
        String key = "cache:typeList";
        //1.在redis中查询  range()获得指定区间,0~-1全部
        List<String> shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.存在,直接返回
        if (!shopTypeList.isEmpty()) {
            //遍历,一个个变成java对象后返回新的list
            List<ShopType> list = new ArrayList<>();
            for (String s : shopTypeList) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);//转换
                list.add(shopType);//++
            }

            return Result.ok(list);
        }
        //3.不存在,查询数据库
        List<ShopType> shopTypes = query().orderByAsc("sort").list();
        if (shopTypes==null){
            return Result.fail("店铺不存在!");
        }

        //4.写入redis
        //遍历数据库查出来的集合shopTypes,一个个变成json字符串
        for (ShopType shopType : shopTypes) {
            String s = JSONUtil.toJsonStr(shopType);//转换
            //此处的shopTypeList为一开始获得的shopTypeList
            shopTypeList.add(s);//++
        }

        //rightPushAll便于读的顺序准确
        stringRedisTemplate.opsForList().rightPushAll(key,shopTypeList);
        //5.返回
        return Result.ok(shopTypes);
    }
}
