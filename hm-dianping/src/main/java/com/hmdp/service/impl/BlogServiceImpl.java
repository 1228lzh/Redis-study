package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }



    @Override
    public Result queryBlogByid(Integer id) {
        Blog blog = getById(id);
        if (blog == null){
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        //查询blog是否被当前用户点赞
        isBlogLiked(blog);
        return null;
    }

    private void isBlogLiked(Blog blog) {
        //1.获取用户
        UserDTO user = UserHolder.getUser();
        if (user==null){
            //用户未登录,无需查询是否点赞
            return;
        }
        Long userId = user.getId();
        //2.判断是否点赞
        String key = BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result likeBlog(Long id) {
        //1.获取用户
        Long userId = UserHolder.getUser().getId();
        //2.判断是否点赞
        String key = BLOG_LIKED_KEY+id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score == null){
            //3.1未点赞
            //3.1.1 点赞数+1,也可以做定时同步,隔一段时间同步点赞数到数据库中
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.1.2 将用户id存入redis的sortedSet中 zadd key value score
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //3.2 已点赞
            //3.2.1 点赞数-1
            boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            //3.2.2 将用户id从redis的sortedSet中移除
            if (isSuccess) {
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }




        return null;
    }

    @Override
    public Result queryBlogLikes(Integer id) {
        String key = BLOG_LIKED_KEY + id;
        //1.查询前五个点赞人 zrange key 0 4
        Set<String> first5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        //2.解析出其中的用户id
        if (first5==null || first5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = first5.stream().map(Long::valueOf).collect(Collectors.toList());
        //拼接id字符串
        String idStr = StrUtil.join(",", ids);
        //3.根据id查询用户 WHERE id in (5,1) ORDER BY FILED(id,5,1)
        List<UserDTO> userDTOList = userService.query().in("id",ids).
                last("ORDER BY FILED(id,5,1)").list().
                stream().map(user -> BeanUtil.copyProperties(user, UserDTO.class)).
                collect(Collectors.toList());
        //4.返回
        return Result.ok(userDTOList);
    }


    /**
     * 查询博客用户
     * @param blog blog
     */
    private void queryBlogUser(Blog blog) {
        //通过博获取用户id
        Long userId = blog.getUserId();
        //通过id查询数据库
        User user = userService.getById(userId);
        //然后set
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
