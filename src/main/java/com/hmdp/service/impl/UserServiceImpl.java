package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import lombok.extern.slf4j.Slf4j;

import com.hmdp.utils.SystemConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    @Override//发送验证码的业务代码
    public Result sendCode(String phone) {
        // 校验手机号
        if (RegexUtils.isPhoneInvalid(phone)) {
            // if里面是true 就意味着确实不符合。那么就返回fail的结果
            return Result.fail("手机号格式错误");
        }
        // 符合生成验证码
        String code = RandomUtil.randomNumbers(6);
        // 验证码保存到 Redis，并设置 2 分钟有效期
        stringRedisTemplate.opsForValue().set(
                RedisConstants.LOGIN_CODE_KEY + phone,
                code,
                RedisConstants.LOGIN_CODE_TTL,
                TimeUnit.MINUTES
        );
        // 发送验证码,一般要调用阿里云的云平台
        log.debug("发送短信验证码成功，验证码是{}", code);
        // 返回ok
        return Result.ok();

    }

    @Override//登陆+注册业务代码
    public Result login(LoginFormDTO loginForm) {
        // 1.校验手机号
        String phone = loginForm.getPhone();
        if (RegexUtils.isPhoneInvalid(phone)) {
            // 2.如果不符合，返回错误信息
            return Result.fail("手机号格式错误！");
        }

        // 2.校验验证码
        String codeKey = RedisConstants.LOGIN_CODE_KEY + phone;
        String cacheCode = stringRedisTemplate.opsForValue().get(codeKey);
        String code = loginForm.getCode();
        if (cacheCode == null || !cacheCode.equals(code)) {
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
    
        // 7.生成随机 token，将脱敏后的用户信息保存到 Redis Hash
        String token = UUID.randomUUID().toString();
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        Map<String, Object> userMap = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );
        String tokenKey = RedisConstants.LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey, userMap);
        Boolean expirationSet = stringRedisTemplate.expire(
                tokenKey,
                RedisConstants.LOGIN_USER_TTL,
                TimeUnit.MINUTES
        );
        if (!Boolean.TRUE.equals(expirationSet)) {
            stringRedisTemplate.delete(tokenKey);
            return Result.fail("登录失败，请稍后重试");
        }

        // 8.登录态写入成功后删除本次验证码，防止重复使用
        stringRedisTemplate.delete(codeKey);
        return Result.ok(token);
        
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
