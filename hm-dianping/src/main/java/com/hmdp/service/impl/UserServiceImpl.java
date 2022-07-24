package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
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
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpSession;

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
    @Override
    public Result sendCode(String phone, HttpSession session) {
        //1.校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            //2.不符合 返回错误信息
            return Result.fail("手机号格式错误!");
        }

        //3.符合,生成验证码
        String code = RandomUtil.randomNumbers(6);
        //4.保存验证码到session
        session.setAttribute("code",code);
        session.setAttribute("phone",phone);
        //5.发送验证码
        log.debug("发送短信验证码成功,验证码:{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        //1.校验手机号格式和是否一致
        String phone = loginForm.getPhone();
        Object phone1 = session.getAttribute("phone");
        if (!phone1.toString().equals(phone)) {
            return Result.fail("手机号不一致!");
        }

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误!");
        }

        //2.校验验证码
        Object CacheCode = session.getAttribute("code");
        String loginFormCode = loginForm.getCode();
        if (CacheCode == null||!CacheCode.toString().equals(loginFormCode)){
            return Result.fail("验证码错误");
        }

        //3.验证码一致,根据手机号查询用户
        User user = query().eq("phone", phone).one();//用户的全部信息

        //4.判断用户是否存在
        if (user == null){
            //5.不存在,创建用户并保存到数据库中
            user = createUserWithPhone(phone);
        }

        //6. 保存信息到session中,注意脱敏
        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));//复制属性到另一个对象中

        return Result.ok();
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
