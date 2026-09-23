package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;

import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;

import javax.servlet.http.HttpSession;
import com.hmdp.utils.SystemConstants;
import org.springframework.stereotype.Service;

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

    @Override//发送验证码的业务代码
    public Result sendCode(String phone, HttpSession session) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // if里面是true 就意味着确实不符合。那么就返回fail的结果
            return Result.fail("手机号格式错误");
        }
        // 符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 验证码保存session
        session.setAttribute("code", code);
        // 发送验证码,一般要调用阿里云的云平台
        log.debug("发送短信验证码成功，验证码是{}", code);
        // 返回ok
        return Result.ok();

    }

    @Override//登陆的业务代码
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 2.校验验证码
        Object cacheCode = session.getAttribute("code");
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.toString().equals(code)) {
            // 3.不一致，报错
            return Result.fail("验证码错误");
        }
        // 4.一致，根据手机号查询用户，并返回到用户实体类
        User user = query().eq("phone",phone).one();
        // 5.判断用户是否存在
        if(user==null){
            // 6.不存在，用电话创建新的用户实体类
            user = creatUserWithPhone(phone);
        }
    
        //7.把用户实体类存在保存到session
            session.setAttribute("user", user);
        return null;
        
    }

    private User creatUserWithPhone(String phone) {
        //创建一个空的user对象
        User user= new User();
        user.setPhone(phone);
        user.setNickName(SystemConstants.USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        //保存用户
        save(user);
        return user;
    }

}
