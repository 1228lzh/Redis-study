package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long followUserId, Boolean isFollow) {

        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        String key = "follow:"+userId;
        //2.判断要关注还是要取关
        if (isFollow) {
            //关注,新增数据
            Follow follow = new Follow();
            follow.setUserId(userId).setFollowUserId(followUserId);
            boolean isSuccess = save(follow);
            if (isSuccess) {
                //添加到redis的set中
                stringRedisTemplate.opsForSet().add(key,followUserId.toString());
            }
        }else {
            //取关,删除数据 delete from tb_follow where user_id = ? and follow_user_id = ?
            boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", followUserId));
            //从set中删除
            if (isSuccess) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result followCommons(Long id) {
        //1.获取当前用户
        Long userId = UserHolder.getUser().getId();
        String key1 = "follow:"+userId;

        //2.求交集
        String key2 = "follow:"+id;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);

        if (intersect==null || intersect.isEmpty()){
            //无交集
            return Result.ok(Collections.emptyList());
        }
        //有交集
        List<Long> ids = intersect
                .stream()
                .map(Long::valueOf)
                .collect(Collectors.toList());

        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result isFollow(Long followUserId) {
        //1.获取登录用户
        Long userId = UserHolder.getUser().getId();
        //2.查询是否关注, select count(*) from tb_follow where user_id = ? and follow_user_id = ?
        Integer count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
        //返回布尔值
        return Result.ok(count>0);
    }


}
