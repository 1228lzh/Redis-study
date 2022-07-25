package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
        @Override
        public Result sendCode(String phone, HttpSession session) {
            //1.校验手机号
            if (RegexUtils.isPhoneInvalid(phone)) {
                //2.不符合 返回错误信息
                return Result.fail("手机号格式错误!");
            }

            //3.符合,生成验证码
            String code = RandomUtil.randomNumbers(6);
            //4.保存验证码到redis
//        stringRedisTemplate.opsForHash().put("login:code:phone","code",code);
//        stringRedisTemplate.opsForHash().put("login:code:phone","phone",phone);
            stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+code,code,LOGIN_CODE_TTL,TimeUnit.MINUTES);
            stringRedisTemplate.opsForValue().set(LOGIN_PHONE_KEY,phone,LOGIN_CODE_TTL,TimeUnit.MINUTES);

            //5.发送验证码
            log.debug("发送短信验证码成功,验证码:{}",code);
            return Result.ok();
        }

        @Override
        public Result login(LoginFormDTO loginForm, HttpSession session) {
            //1.校验手机号格式和是否一致
            String phone = loginForm.getPhone();//登陆时获取的phone
            String code = loginForm.getCode();

            //在发送验证码时候传入redis的phone
            String phone1 = stringRedisTemplate.opsForValue().get(LOGIN_PHONE_KEY);
            //System.out.println("phone = " + phone);
            //System.out.println("phone1 = " + phone1);
            if (!phone1.equals(phone)) {
                return Result.fail("手机号不一致,请重新输入!");
            }

            if (RegexUtils.isPhoneInvalid(phone)) {
                return Result.fail("手机号格式错误!");
            }

            //2.校验验证码

            String CacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + code);
            String loginFormCode = loginForm.getCode();
            if (CacheCode == null||!CacheCode.equals(loginFormCode)){
                return Result.fail("验证码错误");
            }

            //3.验证码一致,根据手机号查询用户
            User user = query().eq("phone", phone).one();//用户的全部信息

            //4.判断用户是否存在
            if (user == null){
                //5.不存在,创建用户并保存到数据库中
                user = createUserWithPhone(phone);
            }

            //6. 保存信息到redis中,注意脱敏
            //6.1 随机生成token,作为登陆令牌
            String token = UUID.randomUUID().toString(true);

            //6.2 将User对象转为Hash(map)存储
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);//复制属性以脱敏(UserDTO中数据更少
            Map<String, Object> userMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            //将所有值转化为String
                            .setFieldValueEditor((fieldName,filedValue)->filedValue.toString()));

            //6.3 存储
            stringRedisTemplate.opsForHash().putAll(LOGIN_TOKEN_KEY+token,userMap);//LOGIN_USER_KEY = "login:token:";
            //6.4 设置token有效期
            stringRedisTemplate.expire(LOGIN_TOKEN_KEY+token,LOGIN_USER_TTL,TimeUnit.MINUTES);
            //7. 返回token到客户端
            return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+phone);

        //2.保存用户
        save(user);

        return user;
    }

}
